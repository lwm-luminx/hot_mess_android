package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import social.hotmess.android.AppGraph
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.ChatComposer
import social.hotmess.android.ui.components.ChatThread
import social.hotmess.android.ui.components.ChatThreadMessage
import social.hotmess.android.ui.components.HereNowStrip
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.Presence
import social.hotmess.android.ui.components.PromptBar
import social.hotmess.android.ui.components.PromptBarState
import social.hotmess.android.ui.components.RichContent
import social.hotmess.android.ui.components.RoomBanner
import social.hotmess.android.ui.components.RoomBannerKind
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.BlockedUser
import social.hotmess.core.ChatClearMark
import social.hotmess.core.ChatKind
import social.hotmess.core.ChatMutes
import social.hotmess.core.ChatPrompts
import social.hotmess.core.ChatRoom
import social.hotmess.core.FriendDirectory
import social.hotmess.core.FriendState
import social.hotmess.core.PingPick
import social.hotmess.core.PromptJump
import social.hotmess.core.RecordId
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import social.hotmess.core.RoomPeople
import social.hotmess.core.VenueChatConnection
import social.hotmess.core.Venue
import social.hotmess.core.VenueMessage
import social.hotmess.core.opensProfile
import java.time.Instant

/**
 * The live chat room for one venue, or one locale, kept across configuration changes. Only people at
 * the venue (or out in the locale) get in, so the position is reported when the room opens and every
 * few minutes while it's open.
 */
class VenueChatModel(private val graph: AppGraph, private val room: ChatRoom) : ViewModel() {
    /** AWAY: the server says this person isn't at the venue (or in the locale), or has left. */
    enum class Status { CONNECTING, CONNECTED, OFFLINE, AWAY }

    private val _messages = MutableStateFlow<List<VenueMessage>>(emptyList())
    val messages: StateFlow<List<VenueMessage>> = _messages.asStateFlow()

    private val _status = MutableStateFlow(Status.CONNECTING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** They're in the room from outside its place, which only admins can do. */
    private val _outOfRange = MutableStateFlow(false)
    val outOfRange: StateFlow<Boolean> = _outOfRange.asStateFlow()

    /**
     * Who is in the room now, by lowercase user id, with their names and photos where the room sends
     * them: the roster on joining, then presence events. Someone who left stays listed, offline, until
     * their `recentUntil`. Empty while disconnected.
     */
    private val _people = MutableStateFlow(RoomPeople())
    val people: StateFlow<RoomPeople> = _people.asStateFlow()

    /**
     * People outside the room whom a notification reaches, by lowercase user id: a yellow dot. The room
     * only says so about the viewer's friends, and to admins.
     */
    private val _reachable = MutableStateFlow<Set<String>>(emptySet())
    val reachable: StateFlow<Set<String>> = _reachable.asStateFlow()

    /**
     * Where the transcript marks what has cleared from what is current: an admin clearing the room, or (for its
     * admins, who still see them) the lines older than the room shows everyone else.
     */
    private val _clearMark = MutableStateFlow<ChatClearMark?>(null)
    val clearMark: StateFlow<ChatClearMark?> = _clearMark.asStateFlow()

    /**
     * Friend requests sent from this room's profile sheet, by lowercase user id. The room's roster doesn't
     * carry requests, so this is what "Requested" knows; it lasts while the room's model does.
     */
    private val _friendRequests = MutableStateFlow<Map<String, FriendState>>(emptyMap())
    val friendRequests: StateFlow<Map<String, FriendState>> = _friendRequests.asStateFlow()

    /** The request on its way, by lowercase user id. */
    private val _addingFriend = MutableStateFlow<String?>(null)
    val addingFriend: StateFlow<String?> = _addingFriend.asStateFlow()

    /** Asks [userId] to be friends (`sendFriendRequest`); [onError] gets what went wrong, in words for people. */
    fun addFriend(userId: String, onError: (String) -> Unit) {
        val id = userId.lowercase()
        if (_addingFriend.value != null || _friendRequests.value[id] != null) return
        _addingFriend.value = id
        viewModelScope.launch {
            try {
                val status = graph.api.sendFriendRequest(userId)
                _friendRequests.value = _friendRequests.value + (id to FriendState.afterRequest(status))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(ApiError.of(e).message ?: "That didn't go through. Try again in a moment.")
            } finally {
                _addingFriend.value = null
            }
        }
    }

    /** A line on its way to the room; the composer waits for it. */
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private var job: Job? = null
    private var heartbeat: Job? = null
    private var prune: Job? = null

    /**
     * Follows the room on the app's shared cable while the screen is showing. The cable reconnects after
     * a drop and joins the room again on the same flow: offline until then, and ChatJoined refreshes the room.
     * Only when the server ends the room's operation does this subscribe again itself, after a backoff.
     */
    fun connect() {
        if (job?.isActive == true) return
        if (heartbeat?.isActive != true) {
            heartbeat = viewModelScope.launch {
                while (true) {
                    delay(HEARTBEAT_MS)
                    graph.location.reportAgain()
                }
            }
        }
        if (prune?.isActive != true) {
            // Whoever was here recently drops off the list once their time is up.
            prune = viewModelScope.launch {
                while (true) {
                    delay(PRUNE_MS)
                    _people.value = _people.value.pruned()
                }
            }
        }
        job = viewModelScope.launch {
            _status.value = Status.CONNECTING
            // The server checks for a recent position at the venue (or in the locale) before letting anyone in.
            graph.location.reportAgain()
            // The cable runs GraphQL for one audience: the configured one, the session's, or the host's.
            var backoff = 1_000L
            var audienceId: String? = null
            while (audienceId == null) {
                audienceId = try {
                    graph.api.client.audienceId()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (audienceId == null) {
                    _status.value = Status.OFFLINE
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000L)
                    _status.value = Status.CONNECTING
                }
            }
            backoff = 1_000L
            while (true) {
                var away = false
                var ended = false
                VenueChatConnection(room, audienceId, graph.cable).events().takeWhile { event ->
                    when (event) {
                        VenueChatConnection.Event.Connected -> {
                            _status.value = Status.CONNECTED
                            backoff = 1_000L
                        }
                        is VenueChatConnection.Event.Received -> receive(event.message)
                        is VenueChatConnection.Event.Range -> _outOfRange.value = event.outOfRange
                        is VenueChatConnection.Event.Roster -> {
                            // All the viewer's friends, by full name, so the room and its lines show them that way.
                            graph.api.friends.record(event.friends)
                            _people.value = _people.value.roster(event.online, event.people, event.recent)
                        }
                        is VenueChatConnection.Event.PresenceChanged -> {
                            _people.value = if (event.online) {
                                _people.value.joined(event.userId, event.name, event.avatarUrl)
                            } else {
                                // Listed until recentUntil, offline (or with the push dot), when the room says so.
                                _people.value.left(event.userId, event.recentUntil, event.reachable)
                            }
                            val id = event.userId.lowercase()
                            _reachable.value = if (event.reachable) _reachable.value + id else _reachable.value - id
                        }
                        is VenueChatConnection.Event.History -> history(event.messages, event.pinned, event.expiresBefore)
                        is VenueChatConnection.Event.Pin -> pin(event.id, event.announcement)
                        // An admin removed a line, or cleared the room; a pinned announcement goes with its line.
                        is VenueChatConnection.Event.Removed -> _messages.value = _messages.value.filterNot { it.id == event.id }
                        is VenueChatConnection.Event.Cleared -> {
                            // Lines sent since stay; an admin's clearing is marked where it happened.
                            _messages.value = ChatClearMark.remaining(_messages.value, event.at)
                            _clearMark.value = ChatClearMark.afterClear(_clearMark.value, _messages.value, event.at, event.expired)
                        }
                        is VenueChatConnection.Event.Disconnected -> {
                            // Reconnecting (or over): the next roster says who's here; until then nobody is known to be.
                            _status.value = Status.OFFLINE
                            _people.value = RoomPeople()
                            _reachable.value = emptySet()
                        }
                        VenueChatConnection.Event.Ended -> ended = true
                        VenueChatConnection.Event.Rejected, VenueChatConnection.Event.Left -> away = true
                    }
                    !away
                }.collect()
                if (away) {
                    // Reconnecting won't help until they're back; "Try again" subscribes again.
                    _status.value = Status.AWAY
                    return@launch
                }
                _status.value = Status.OFFLINE
                // Cable drops are the cable's to reconnect; only an operation the server ended is ours to
                // start over. Anything else (signed out, refused) stays offline.
                if (!ended) return@launch
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                _status.value = Status.CONNECTING
            }
        }
    }

    /** Adds a line. A line the room sends again (by its id) replaces the first copy. */
    private fun receive(line: VenueMessage) {
        if (line.reachable && !line.isFromListing) _reachable.value = _reachable.value + line.userId.lowercase()
        // An older server's roster has no names: a line someone sends names them in "Here now".
        if (!line.isFromListing) _people.value = _people.value.described(line.userId, line.name, line.avatarUrl)
        val current = _messages.value
        val index = current.indexOfFirst { it.id == line.id }
        _messages.value = if (index >= 0) current.toMutableList().also { it[index] = line } else current + line
    }

    /**
     * What the room sent on joining replaces what was shown, so a reconnect doesn't repeat lines. A
     * pinned announcement older than the lines the room shows goes first, so its bar has it.
     */
    private fun history(messages: List<VenueMessage>, pinned: VenueMessage?, expiresBefore: Instant?) {
        val lines = messages
        val older = pinned?.takeIf { p -> lines.none { it.id == p.id } }
        _messages.value = listOfNotNull(older) + lines
        _clearMark.value = ChatClearMark.forHistory(lines, expiresBefore)
        _reachable.value = lines.filter { it.reachable && !it.isFromListing }.map { it.userId.lowercase() }.toSet()
    }

    /** An announcement was pinned (replacing whichever was) or unpinned. */
    private fun pin(id: String, announcement: VenueMessage?) {
        val lines = _messages.value.map { line ->
            val pinned = announcement != null && line.id == id
            if (line.pinned == pinned) line else line.copy(pinned = pinned)
        }
        _messages.value = if (announcement != null && lines.none { it.id == id }) lines + announcement else lines
    }

    fun disconnect() {
        job?.cancel()
        job = null
        heartbeat?.cancel()
        heartbeat = null
        prune?.cancel()
        prune = null
        _people.value = RoomPeople()
        _reachable.value = emptySet()
    }

    /**
     * Says [body] in the room with `sendMyChatMessage`, answering the prompt [promptId] when given, then
     * tells [onResult] whether it went. The room delivers the line to everyone following it, this person
     * included, and the copy the mutation returns shows it at once; the room's copy then replaces it by id.
     * A failed send leaves the draft as it was.
     */
    fun send(body: String, promptId: String? = null, onResult: (Boolean) -> Unit) {
        val text = body.trim()
        if (text.isEmpty() || _sending.value || _status.value != Status.CONNECTED) return onResult(false)
        _sending.value = true
        viewModelScope.launch {
            val sent = try {
                graph.api.sendChatMessage(room, text, promptId)?.let(::receive)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                _sending.value = false
            }
            onResult(sent)
        }
    }

    override fun onCleared() = disconnect()

    private companion object {
        const val HEARTBEAT_MS = 4 * 60 * 1000L
        const val PRUNE_MS = 15 * 1000L
    }
}

@Composable
fun VenueChatScreen(room: ChatRoom, name: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    // A room opened from a link or a push comes without its name: the venue's, or the locale's when it's the app's.
    val venueName by produceState(name.ifBlank { roomNameFallback(room, graph) }, room, name) {
        if (name.isNotBlank() || room.kind != ChatRoom.Kind.VENUE) return@produceState
        try {
            value = graph.api.venueOverview(room.id).venue.name.ifBlank { value }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
    val model = viewModel(key = "chat-${room.kind}-${room.id}") { VenueChatModel(graph, room) }
    val isLocale = room.kind == ChatRoom.Kind.LOCALE
    val messages by model.messages.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val outOfRange by model.outOfRange.collectAsStateWithLifecycle()
    val people by model.people.collectAsStateWithLifecycle()
    val reachable by model.reachable.collectAsStateWithLifecycle()
    val clearMark by model.clearMark.collectAsStateWithLifecycle()
    val user by graph.session.user.collectAsStateWithLifecycle()
    val safety by graph.safety.state.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    // Reporting and blocking from a long press on someone's line, and the terms asked before the first message.
    var acting by remember { mutableStateOf<VenueMessage?>(null) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    var blocking by remember { mutableStateOf<BlockedUser?>(null) }
    var askingTerms by remember { mutableStateOf(false) }
    // The profile sheet tapping someone opens, and the Ping sheet it can open.
    var profile by remember { mutableStateOf<ChatProfile?>(null) }
    var friendError by remember { mutableStateOf<String?>(null) }
    var pinging by remember { mutableStateOf(false) }
    val friendRequests by model.friendRequests.collectAsStateWithLifecycle()
    val addingFriend by model.addingFriend.collectAsStateWithLifecycle()
    // Who this reader muted in this room, on this device.
    val allMuted by graph.chatMutes.state.collectAsStateWithLifecycle()
    LaunchedEffect(room) { graph.chatMutes.muted(room) }
    val muted = allMuted[ChatMutes.key(room)].orEmpty()
    // The prompt being answered (its id), and the quiet-room cards this reader said "Not now" to.
    var answering by rememberSaveable { mutableStateOf<String?>(null) }
    var dismissedPrompts by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LaunchedEffect(Unit) { if (graph.safety.state.value == null) graph.safety.refresh() }

    DisposableEffect(model) {
        model.connect()
        onDispose { model.disconnect() }
    }

    val sending by model.sending.collectAsStateWithLifecycle()

    fun send() {
        if (graph.safety.needsTerms) {
            askingTerms = true
            return
        }
        val text = draft
        val promptId = answering
        // Clears only what was sent, not anything typed while it went; an answer that went ends answering.
        model.send(text, promptId) { sent ->
            if (sent && draft == text) draft = ""
            if (sent && answering == promptId) answering = null
        }
    }

    ScreenScaffold(title = venueName, onBack = navigator::back) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            if (status == VenueChatModel.Status.AWAY) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Message(
                        Icons.AutoMirrored.Rounded.Chat,
                        if (isLocale) "Only for people out in $venueName" else "Only for people at $venueName",
                        if (isLocale) {
                            "The room opens when you're out in $venueName and not at a venue. Venues have their own chat."
                        } else {
                            "The room opens when you're there. Your location has to be on so Hot Mess can tell."
                        },
                        action = "Try again" to { model.disconnect(); model.connect() },
                    )
                }
                return@Column
            }
            val banner = when {
                outOfRange -> RoomBannerKind.RANGE
                status == VenueChatModel.Status.CONNECTING -> RoomBannerKind.CONNECTING
                status != VenueChatModel.Status.CONNECTED -> RoomBannerKind.OFFLINE
                else -> null
            }
            if (banner != null) RoomBanner(banner, venueName, isLocale = isLocale)
            val userId = user?.id
            val friends = graph.api.friends
            // Who's here, the viewer then friends first; a roster can add friends' full names, so it redraws the thread too.
            val hereNow = remember(people, userId, safety) {
                RoomPeople.hereNow(people.byId.values, userId) { friends.fullName(it) }
                    .filterNot { safety?.isBlocked(it.userId) == true }
            }
            HereNowStrip(
                hereNow,
                reachable,
                viewerId = userId,
                onOpenPerson = { person ->
                    friendError = null
                    profile = ChatProfile(
                        person.userId,
                        person.name?.takeIf { it.isNotBlank() } ?: "Someone",
                        person.avatarUrl,
                        friend = person.friend || friends.fullName(person.userId) != null,
                    )
                },
            )
            // Blocked people's lines leave the room at once; the API stops sending new ones.
            val shown = remember(messages, safety) { messages.filterNot { !it.isFromListing && safety?.isBlocked(it.userId) == true } }
            // Muted people's lines leave this room only, for this reader; answer counts still include them.
            val unmuted = remember(shown, muted) { ChatMutes.visible(shown, muted) }
            val notDismissed = remember(unmuted, dismissedPrompts) { unmuted.filterNot { it.kind == ChatKind.PROMPT && it.id in dismissedPrompts } }
            val thread = remember(notDismissed, userId, people) {
                notDismissed.map { it.threadMessage(userId, friends, notDismissed, people) }
            }
            // The night's pinned prompt (the newest), the PromptBar's; and the prompt being answered.
            val pinnedPrompt = remember(shown) { ChatPrompts.pinned(shown)?.prompt }
            val answeringPrompt = remember(shown, answering) {
                answering?.let { id -> shown.firstNotNullOfOrNull { line -> line.prompt?.takeIf { it.id == id } } }
            }
            // Green for everyone in the room, yellow for friends outside it whom a notification reaches.
            val presence = remember(people, reachable) {
                val push = reachable + people.byId.values.filter { it.isRecent && it.reachable }.map { it.userId }
                push.associateWith { Presence.PUSH } + people.online.associateWith { Presence.ONLINE }
            }
            ChatThread(
                thread,
                venueName,
                Modifier.weight(1f).fillMaxWidth(),
                presence = presence,
                isLocale = isLocale,
                onOpenEvent = { id -> navigator.open(AppRoute.EventDetail(RecordId.normalize(id) ?: id.lowercase())) },
                onMessageActions = { line -> acting = shown.firstOrNull { it.id == line.id } },
                onOpenProfile = { line ->
                    shown.firstOrNull { it.id == line.id }?.let { sent ->
                        friendError = null
                        profile = ChatProfile(
                            sent.userId.lowercase(),
                            line.senderName?.takeIf { it.isNotBlank() } ?: "Someone",
                            sent.avatarUrl,
                            friend = line.friend,
                        )
                    }
                },
                onOpenPrompt = { promptId -> PromptJump.to(promptId, shown) },
                promptBar = pinnedPrompt?.let { prompt ->
                    @Composable { bar: PromptBarState ->
                        val answers = remember(shown, prompt.id) { ChatPrompts.answerCount(shown, prompt.id) }
                        val clock by produceState(Instant.now(), prompt.nextAt) {
                            while (true) {
                                delay(60_000L)
                                value = Instant.now()
                            }
                        }
                        PromptBar(
                            prompt,
                            footer = ChatPrompts.footer(prompt, answers, at = clock),
                            onAnswer = { answering = prompt.id },
                            collapsed = bar.collapsed,
                            answerCount = answers,
                            highlighted = bar.highlighted,
                            onExpand = bar.onExpand,
                        )
                    }
                },
                onAnswerPrompt = { line -> line.prompt?.let { answering = it.id } },
                onDismissPrompt = { line ->
                    dismissedPrompts = dismissedPrompts + line.id
                    if (answering == line.prompt?.id) answering = null
                },
                clearMark = clearMark,
            )
            ChatComposer(
                value = draft,
                onValueChange = { draft = it },
                onSend = ::send,
                sendEnabled = draft.isNotBlank() && !sending && status == VenueChatModel.Status.CONNECTED,
                answering = answeringPrompt?.text,
                onStopAnswering = { answering = null },
            )
        }
    }

    acting?.let { line ->
        // A post as the venue or a listing has no sender to block; it can still be reported.
        val sender = if (line.isFromListing) null else BlockedUser(line.userId, line.name.orEmpty(), line.avatarUrl)
        ReportOrBlockDialog(
            title = line.name?.takeIf { it.isNotBlank() }?.let { "Message from $it" } ?: "Message",
            reportLabel = "Report message",
            person = sender,
            onReport = {
                acting = null
                reporting = ReportSubject(ReportTarget.MESSAGE, line.id, sender)
            },
            onBlock = {
                acting = null
                blocking = it
            },
            onDismiss = { acting = null },
        )
    }
    profile?.let { person ->
        val id = person.userId.lowercase()
        val presence = if (id in people.online) Presence.ONLINE else null
        ChatProfileSheet(
            profile = person,
            presence = presence,
            friendState = if (person.friend) FriendState.FRIENDS else friendRequests[id] ?: FriendState.NONE,
            addingFriend = addingFriend == id,
            friendError = friendError,
            muted = id in muted,
            onPing = {
                profile = null
                pinging = true
            },
            onAddFriend = {
                friendError = null
                model.addFriend(person.userId) { friendError = it }
            },
            onToggleMute = { graph.chatMutes.setMuted(room, id, id !in muted) },
            onReport = {
                profile = null
                reporting = ReportSubject(ReportTarget.PERSON, person.userId, BlockedUser(person.userId, person.name, person.avatarUrl))
            },
            onBlock = {
                profile = null
                blocking = BlockedUser(person.userId, person.name, person.avatarUrl)
            },
            onDismiss = { profile = null },
        )
    }
    if (pinging) {
        // A Ping to friends about this venue (its room's), or with no pick in a locale's room.
        PingSheet(
            preselect = if (room.kind == ChatRoom.Kind.VENUE) PingPick.VenuePick(Venue(room.id, venueName)) else null,
            onDismiss = { pinging = false },
            onSent = {
                pinging = false
                graph.pingUpdated(it.id)
            },
        )
    }
    reporting?.let { ReportDialog(it) { reporting = null } }
    blocking?.let { BlockDialog(it) { blocking = null } }
    if (askingTerms) {
        TermsDialog(
            onAgreed = {
                askingTerms = false
                send()
            },
            onDismiss = { askingTerms = false },
        )
    }
}

/** What a room without a name is called until (or unless) it's looked up. */
private fun roomNameFallback(room: ChatRoom, graph: AppGraph): String = when (room.kind) {
    ChatRoom.Kind.VENUE -> "Chat"
    ChatRoom.Kind.LOCALE -> graph.location.locale.value?.takeIf { RecordId.same(it.id, room.id) }?.name ?: "Small talk"
}

/**
 * A room's line as ChatThread draws it. A friend shows by their full name (the room sends everyone
 * "First L."), a post as the venue or as a listing by its own name and photo, a prompt as the audience
 * (its name and mark), an answer with its Prompt label from [lines], and a rich message as its card.
 */
private fun VenueMessage.threadMessage(viewerId: String?, friends: FriendDirectory, lines: List<VenueMessage>, people: RoomPeople) = ChatThreadMessage(
    id = id,
    // A post as a listing or the audience groups apart from the poster's own lines, and takes no presence of theirs.
    senderId = when {
        isPostedAsAudience -> "audience"
        personId != null -> "person:$personId"
        else -> userId.lowercase()
    },
    senderName = if (isFromListing) name else friends.displayName(userId, name),
    avatarUrl = if (isPostedAsAudience) null else avatarUrl,
    text = prompt?.text ?: body,
    sentAt = sentAt,
    own = !isPostedAsAudience && isOutgoing(viewerId),
    role = if (isPostedAsAudience) null else role,
    asPlace = isPostedAsVenue,
    rich = if (kind.isRich && kind != ChatKind.PROMPT) {
        RichContent(kind, title = title, body = caption, photoUrl = photoUrl, event = event, endsAt = endsAt, pinned = pinned)
    } else {
        null
    },
    asAudience = isPostedAsAudience,
    asListing = personId != null,
    friend = !isFromListing && (people.byId[userId.lowercase()]?.friend == true || friends.fullName(userId) != null),
    promptLabel = if (kind == ChatKind.TEXT) ChatPrompts.label(promptId, lines) else null,
    prompt = if (kind == ChatKind.PROMPT) prompt else null,
    promptId = if (kind == ChatKind.TEXT) promptId else null,
    opensProfile = opensProfile(viewerId),
)
