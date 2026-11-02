package social.hotmess.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.SectionTitle
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.HotMessApi
import social.hotmess.core.LineupEntry
import social.hotmess.core.LineupState
import social.hotmess.core.Lineups
import social.hotmess.core.SongRequest
import social.hotmess.core.SongRequestDraft
import social.hotmess.core.SongRequestKind
import social.hotmess.core.SongRequestState
import social.hotmess.core.SongRequestWindow
import social.hotmess.core.VenueKaraoke
import social.hotmess.core.VenueLineup
import social.hotmess.core.billing

/**
 * A venue's lineup tonight, its karaoke signups and its song requests (`tonightLineup`), refreshed every minute while
 * the page is open and after anything the viewer does, as iOS's VenueLineupModel. Keeps the last lineup when a fetch fails.
 */
class TonightLineupModel(private val api: HotMessApi, val venueId: String, private val scope: CoroutineScope) {
    private val _lineup = MutableStateFlow<VenueLineup?>(null)
    val lineup: StateFlow<VenueLineup?> = _lineup.asStateFlow()

    suspend fun refresh() {
        try {
            api.tonightLineup(venueId)?.let { _lineup.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // An API without the lineup, or a dropped connection: the page keeps what it had (or its On now).
        }
    }

    /** Signs up to sing or asks for a song; the error worded for the room, or null once it went. */
    suspend fun request(kind: SongRequestKind, draft: SongRequestDraft): String? = attempt { api.requestSong(venueId, kind, draft) }

    suspend fun withdraw(request: SongRequest): String? = attempt { api.withdrawSongRequest(request.id) }

    private suspend fun attempt(block: suspend () -> Unit): String? {
        val error = try {
            block()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiError.of(e).message ?: "That didn't go through. Try again in a moment."
        }
        scope.launch { refresh() }
        return error
    }
}

/** The venue's [TonightLineupModel], loaded now and every minute while shown. */
@Composable
fun rememberTonightLineup(venueId: String): TonightLineupModel {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val model = remember(venueId) { TonightLineupModel(graph.api, venueId, scope) }
    LaunchedEffect(model) {
        while (true) {
            model.refresh()
            delay(60_000L)
        }
    }
    return model
}

/**
 * Tonight at a venue, as iOS's VenueLineupSection: the host on now, the lineup in running order ("Hosted by A and B"
 * when it's only hosts) ending in a "+" that signs up to sing or requests a song (or says they're closed), karaoke
 * with the viewer's signup and the next few singers, a partner's karaoke session to join, and song requests.
 */
@Composable
fun TonightLineupSection(
    model: TonightLineupModel,
    lineup: VenueLineup,
    viewerName: String?,
    onOpenPerson: (String) -> Unit,
) {
    var asking by remember { mutableStateOf<SongRequestKind?>(null) }
    // Signed out, the lineup shows without its "+": signing up and requesting are the viewer's.
    val ask = if (viewerName == null) null else { kind: SongRequestKind -> asking = kind }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s5)) {
        lineup.hostOnNow?.let { host -> HostRow(host) { host.personId?.let(onOpenPerson) } }
        LineupStrip(lineup, onOpenPerson, onAdd = ask)
        val session = lineup.karaokeSession
        if (session != null && session.isPartnerSessionTonight) {
            KaraokeJoinSection(session)
        } else if (lineup.karaoke?.permitted == true || lineup.singers.isNotEmpty()) {
            KaraokeSection(lineup, viewerName, model)
        }
        if (lineup.takesSongRequests) SongRequestsSection(lineup, model)
    }
    asking?.let { kind ->
        val window = if (kind == SongRequestKind.KARAOKE) lineup.karaoke else lineup.songRequests
        SongRequestSheet(
            kind = kind,
            venueName = lineup.venueName,
            hostName = window?.host?.billing,
            viewerName = viewerName,
            submit = { draft -> model.request(kind, draft) },
            onDismiss = { asking = null },
        )
    }
}

private val LineupEntry.personId: String? get() = people.firstOrNull()?.id

/**
 * An event's karaoke on the night, as iOS's event page shows it: the venue's karaoke (or a partner's session to join)
 * with its own "Sign up to sing", and song requests with "Request a song", since an event has no lineup strip.
 */
@Composable
fun TonightKaraoke(model: TonightLineupModel, lineup: VenueLineup, viewerName: String?) {
    var asking by remember { mutableStateOf<SongRequestKind?>(null) }
    val ask = if (viewerName == null) null else { kind: SongRequestKind -> asking = kind }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s5)) {
        val session = lineup.karaokeSession
        if (session != null && session.isPartnerSessionTonight) {
            KaraokeJoinSection(session)
        } else if (lineup.karaoke?.permitted == true || lineup.singers.isNotEmpty()) {
            KaraokeSection(lineup, viewerName, model, onSignUp = ask?.let { add -> { add(SongRequestKind.KARAOKE) } })
        }
        if (lineup.songRequests?.let { it.permitted || it.mine.any { request -> request.state.isOpen } } == true) {
            SongRequestsSection(lineup, model, onRequest = ask?.let { add -> { add(SongRequestKind.SONG) } })
        }
    }
    asking?.let { kind ->
        val window = if (kind == SongRequestKind.KARAOKE) lineup.karaoke else lineup.songRequests
        SongRequestSheet(
            kind = kind,
            venueName = lineup.venueName,
            hostName = window?.host?.billing,
            viewerName = viewerName,
            submit = { draft -> model.request(kind, draft) },
            onDismiss = { asking = null },
        )
    }
}

/** Whether [TonightKaraoke] has anything to show. */
val VenueLineup.showsKaraoke: Boolean
    get() = karaokeSession?.isPartnerSessionTonight == true || karaoke?.permitted == true || singers.isNotEmpty() ||
        songRequests?.permitted == true

/** Sign up to sing (or Request a song): the primary button while open; greyed with the venue's reason while not. */
@Composable
private fun RequestButton(kind: SongRequestKind, window: SongRequestWindow, label: String, onClick: () -> Unit) {
    if (!window.permitted) return
    val karaoke = kind == SongRequestKind.KARAOKE
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        PrimaryButton(
            label,
            onClick = onClick,
            enabled = window.open && !window.closed,
            icon = if (karaoke) Icons.Rounded.Mic else Icons.Rounded.MusicNote,
            modifier = Modifier.fillMaxWidth().testTag(if (karaoke) "karaoke.signUp" else "songs.request"),
        )
        if (!window.open || window.closed) {
            val reason = when {
                window.closed -> (if (karaoke) "Signups closed" else "Requests closed") +
                    if (karaoke) " · The singers already on the list still sing." else ""
                else -> window.closedReason?.takeIf { it.isNotBlank() } ?: if (karaoke) "Signups open when a host is on." else "Requests open when a DJ is on."
            }
            Text(reason, style = HotMessType.caption, color = tokens.inkMuted)
        }
    }
}

/** The host on now, a step larger than the lineup, opening their page. */
@Composable
private fun HostRow(host: LineupEntry, onOpen: () -> Unit) {
    val person = host.people.firstOrNull()
    DetailSection(null) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (person != null) Modifier.clickable(onClickLabel = "See ${host.billing}'s page", onClick = onOpen) else Modifier)
                .padding(Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(person?.avatarUrl, host.billing, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text("Hosting now", style = HotMessType.caption, color = tokens.inkMuted)
                Text(host.billing, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Everyone who isn't a karaoke singer in running order, then the "+" for signups and song requests. */
@Composable
private fun LineupStrip(lineup: VenueLineup, onOpenPerson: (String) -> Unit, onAdd: ((SongRequestKind) -> Unit)?) {
    val acts = lineup.acts
    val additions = if (onAdd == null) emptyList() else buildList {
        if (lineup.takesKaraokeSignups) add(SongRequestKind.KARAOKE to lineup.karaoke!!)
        if (lineup.takesSongRequests) add(SongRequestKind.SONG to lineup.songRequests!!)
    }
    if (acts.isEmpty() && additions.isEmpty()) return
    // A lineup of only hosts isn't a running order: they're all on together.
    val title = if (lineup.onlyHosts) "Hosted by ${Lineups.andList(acts.map { it.billing })}" else "Tonight"
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        SectionTitle(title, Modifier.semantics { heading() })
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            items(acts, key = { it.id }) { entry ->
                ActCard(entry, isUpNext = entry.id == lineup.upNextId, showState = !lineup.onlyHosts) { entry.personId?.let(onOpenPerson) }
            }
            items(additions, key = { it.first.name }) { (kind, window) -> AddCard(kind, window) { onAdd?.invoke(kind) } }
        }
    }
}

@Composable
private fun ActCard(entry: LineupEntry, isUpNext: Boolean, showState: Boolean, onOpen: () -> Unit) {
    val on = entry.state == LineupState.ON
    val label = when {
        !showState -> entry.role?.label
        on -> "On now"
        isUpNext -> "Up next"
        entry.state == LineupState.DONE -> "Earlier"
        else -> entry.hours() ?: entry.role?.label
    }
    Surface(
        shape = Radius.photo,
        color = if (on) tokens.accentSoft else tokens.surfaceRaised,
        border = if (on) BorderStroke(1.dp, tokens.accent) else null,
        modifier = Modifier
            .width(112.dp)
            .then(if (entry.personId != null) Modifier.clickable(onClickLabel = "See ${entry.billing}'s page", onClick = onOpen) else Modifier),
    ) {
        Column(Modifier.padding(Space.s3), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            if (entry.isIntermission) {
                Box(Modifier.size(44.dp).background(tokens.controlFill, CircleShape))
            } else {
                Avatar(entry.people.firstOrNull()?.avatarUrl, entry.billing, size = 44.dp)
            }
            Text(entry.billing, style = HotMessType.label, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            label?.let { Text(it, style = HotMessType.caption, color = if (on) tokens.accentInk else tokens.inkMuted, maxLines = 1) }
        }
    }
}

/** "Sign up to sing" or "Request a song"; "Signups closed" or "Requests closed" for the night, greyed while waiting on a host. */
@Composable
private fun AddCard(kind: SongRequestKind, window: SongRequestWindow, onClick: () -> Unit) {
    val karaoke = kind == SongRequestKind.KARAOKE
    val label = when {
        window.closed -> if (karaoke) "Signups closed" else "Requests closed"
        else -> if (karaoke) "Sign up to sing" else "Request a song"
    }
    val enabled = window.open && !window.closed
    Surface(
        shape = Radius.photo,
        color = tokens.surfaceRaised,
        border = BorderStroke(1.dp, if (enabled) tokens.accent else tokens.border),
        modifier = Modifier
            .width(112.dp)
            .testTag(if (karaoke) "lineup.signUp" else "lineup.request")
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(Space.s3), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Box(Modifier.size(44.dp).background(if (enabled) tokens.accent else tokens.controlFill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (window.closed) Icons.Rounded.Lock else Icons.Rounded.Add,
                    contentDescription = null,
                    tint = if (enabled) tokens.onAccent else tokens.inkMuted,
                )
            }
            Text(label, style = HotMessType.label, color = if (enabled) tokens.accentInk else tokens.inkMuted, maxLines = 2)
            if (!enabled && !window.closed) {
                window.closedReason?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.caption, color = tokens.inkMuted, maxLines = 3) }
            }
        }
    }
}

/** Karaoke: how many are singing, the viewer's own signup, and the next few singers (all of them behind See all). */
@Composable
private fun KaraokeSection(lineup: VenueLineup, viewerName: String?, model: TonightLineupModel, onSignUp: (() -> Unit)? = null) {
    val window = lineup.karaoke
    val singers = lineup.singers
    var showingAll by rememberSaveable { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionTitle("Karaoke", Modifier.weight(1f).semantics { heading() })
            if (singers.isNotEmpty()) {
                Text(if (singers.size == 1) "1 singer tonight" else "${singers.size} singers tonight", style = HotMessType.caption, color = tokens.inkMuted)
            }
        }
        lineup.featuredSignup?.takeIf { it.state.isOpen || it.id !in dismissed }?.let { request ->
            MySignupCard(lineup, request, viewerName, model, onDismiss = { dismissed = dismissed + request.id })
        }
        val mine = window?.mine.orEmpty()
        if (window != null && onSignUp != null && mine.none { it.state.isOpen }) {
            RequestButton(SongRequestKind.KARAOKE, window, if (mine.isEmpty()) "Sign up to sing" else "Sing another", onSignUp)
        }
        val coming = lineup.karaokeEntries.filter { it.state != LineupState.DONE }
        val sung = lineup.karaokeEntries.count { it.state == LineupState.DONE && it.isSinger }
        if (coming.isEmpty() && sung == 0) {
            if (window?.open == true) Text("Nobody's on the list yet. Be the first.", style = HotMessType.body, color = tokens.inkMuted)
        } else {
            DetailSection(null) {
                val rows = if (showingAll) lineup.karaokeEntries else coming.take(3)
                rows.forEach { entry -> SingerRow(lineup, entry) }
                val hidden = if (showingAll) 0 else lineup.karaokeEntries.size - rows.size
                if (hidden > 0 || showingAll) {
                    TextButton(onClick = { showingAll = !showingAll }, modifier = Modifier.padding(horizontal = Space.s2)) {
                        Text(if (showingAll) "Show fewer" else "See all ${singers.size} singers")
                    }
                }
            }
        }
    }
}

@Composable
private fun SingerRow(lineup: VenueLineup, entry: LineupEntry) {
    val request = entry.songRequest
    val status = when {
        entry.isIntermission -> if (entry.state == LineupState.ON) "On now" else null
        entry.state == LineupState.ON -> "On stage"
        entry.id == lineup.upNextId -> "Up next"
        entry.state == LineupState.DONE -> if (request?.state == SongRequestState.SKIPPED) "Passed" else "Sang"
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            lineup.singerNumber(entry)?.toString() ?: "",
            style = HotMessType.label,
            color = tokens.inkMuted,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            val name = entry.billing + if (request?.mine == true) " (you)" else ""
            Text(name, style = HotMessType.body, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            request?.songLine?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = HotMessType.caption, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        status?.let {
            Text(it, style = HotMessType.caption, color = if (entry.state == LineupState.ON) tokens.accentInk else tokens.inkMuted)
        }
    }
}

/** The viewer's own signup: where they are, Remove while they're waiting, Dismiss once it's over. */
@Composable
private fun MySignupCard(lineup: VenueLineup, request: SongRequest, viewerName: String?, model: TonightLineupModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val entry = lineup.entryFor(request)
    val onStage = request.state == SongRequestState.UP || entry?.state == LineupState.ON
    val isUpNext = entry != null && entry.id == lineup.upNextId
    val host = lineup.karaoke?.host?.billing ?: "the host"
    val breakOnNow = lineup.breakOnNow
    val headline = when (request.state) {
        SongRequestState.DONE -> "You sang it"
        SongRequestState.SKIPPED -> "The host passed on this one"
        SongRequestState.WITHDRAWN -> "You're off the list"
        else -> when {
            onStage -> "You're on"
            isUpNext -> if (breakOnNow == null) "You're up next" else "You're up after the break"
            else -> "You're on the list"
        }
    }
    val detail = when (request.state) {
        SongRequestState.DONE -> "Thanks for singing."
        SongRequestState.SKIPPED -> "You can sign up again any time."
        SongRequestState.WITHDRAWN -> "Sign up again whenever you like."
        else -> when {
            onStage -> "Go sing it."
            isUpNext -> breakOnNow?.let { "${it.billing} is on. Stay close; $host calls you when it ends." } ?: "Head for the stage when $host calls you"
            else -> {
                val place = entry?.let(lineup::singerNumber)?.let { " — number $it tonight" }.orEmpty()
                when (val count = lineup.singersBefore(request) ?: 0) {
                    0 -> "Nobody before you$place"
                    1 -> "1 singer before you$place"
                    else -> "$count singers before you$place"
                }
            }
        }
    }
    val songLine = request.singer?.takeIf { it.isNotBlank() && !it.equals(viewerName, ignoreCase = true) }?.let { "${request.songLine} · as $it" } ?: request.songLine
    var confirming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Surface(
        shape = Radius.photo,
        color = if (onStage) tokens.successSoft else tokens.surfaceRaised,
        border = BorderStroke(1.dp, if (onStage) tokens.success else if (request.state.isOpen) tokens.accent else tokens.border),
    ) {
        Row(Modifier.fillMaxWidth().padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(40.dp).background(if (onStage) tokens.successSoft else if (request.state.isOpen) tokens.accentSoft else tokens.controlFill, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, tint = if (onStage) tokens.success else if (request.state.isOpen) tokens.accentInk else tokens.inkMuted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(headline, style = HotMessType.subheading, color = tokens.ink)
                Text(songLine, style = HotMessType.body, color = tokens.inkMuted)
                Text(detail, style = HotMessType.caption, color = tokens.inkMuted)
            }
            if (request.state == SongRequestState.QUEUED && !onStage) {
                TextButton(onClick = { confirming = true }, enabled = !removing) { Text("Remove", color = tokens.accentInk) }
            } else if (!request.state.isOpen) {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = tokens.inkMuted) }
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Remove yourself from the list?", style = HotMessType.heading) },
            text = { Text((if (isUpNext) "You're up next. " else "") + "${request.title} stays yours to sign up for again later.", style = HotMessType.body) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    removing = true
                    scope.launch {
                        failure = model.withdraw(request)
                        removing = false
                    }
                }) { Text("Remove", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Stay on") } },
            containerColor = tokens.surfaceRaised,
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Couldn't remove you", style = HotMessType.heading) },
            text = { Text(message, style = HotMessType.body) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
            containerColor = tokens.surfaceRaised,
        )
    }
}

/** A partner's karaoke session tonight (KaraFun and the like): join by link, or with the code. */
@Composable
private fun KaraokeJoinSection(session: VenueKaraoke) {
    val context = LocalContext.current
    var showingCode by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        SectionTitle("Karaoke", Modifier.semantics { heading() })
        DetailSection(null) {
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                val provider = session.providerName?.takeIf { it.isNotBlank() }
                val url = session.joinLink
                val code = session.sessionCode?.takeIf { it.isNotBlank() }
                if (url != null) {
                    PrimaryButton(provider?.let { "Join $it session" } ?: "Join karaoke session", onClick = { context.openUrl(url) }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Mic)
                }
                if (code != null) {
                    if (showingCode || url == null) {
                        Text("Join code", style = HotMessType.caption, color = tokens.inkMuted)
                        Text(code, style = HotMessType.title.copy(fontWeight = FontWeight.Bold), color = tokens.ink, modifier = Modifier.testTag("karaoke.code"))
                    } else {
                        TextButton(onClick = { showingCode = true }) { Text("Show join code") }
                    }
                }
                if (url == null && code == null) {
                    Text("Ask the host how to join the karaoke session.", style = HotMessType.body, color = tokens.inkMuted)
                }
            }
        }
    }
}

/** Song requests: what's playing, and the viewer's own with where they are and Remove while they wait. */
@Composable
private fun SongRequestsSection(lineup: VenueLineup, model: TonightLineupModel, onRequest: (() -> Unit)? = null) {
    val window = lineup.songRequests ?: return
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    val mine = lineup.mySongRequests
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        SectionTitle("Song requests", Modifier.semantics { heading() })
        if (onRequest != null) RequestButton(SongRequestKind.SONG, window, "Request a song", onRequest)
        DetailSection(null) {
            window.playing?.let { playing ->
                RequestRow(Icons.Rounded.MusicNote, "Now playing", playing.songLine, accent = true)
            }
            mine.forEach { request ->
                val status = when (request.state) {
                    SongRequestState.UP -> "Playing now"
                    SongRequestState.DONE -> "Played"
                    SongRequestState.SKIPPED -> "Skipped"
                    SongRequestState.WITHDRAWN -> "Taken back"
                    else -> when (val before = window.requestsBefore(request)) {
                        null, 0 -> "Next up"
                        1 -> "1 song before yours"
                        else -> "$before songs before yours"
                    }
                }
                RequestRow(Icons.Rounded.MusicNote, request.songLine, "Yours · $status") {
                    if (request.state == SongRequestState.QUEUED) {
                        TextButton(onClick = { scope.launch { failure = model.withdraw(request) } }) { Text("Remove", color = tokens.accentInk) }
                    }
                }
            }
            if (window.playing == null && mine.isEmpty()) {
                val text = when {
                    window.closed -> "Requests are closed for tonight."
                    !window.open -> window.closedReason?.takeIf { it.isNotBlank() } ?: "Requests open when a DJ is on."
                    onRequest != null -> "Nothing requested yet."
                    else -> "Ask for a song with the + in the lineup."
                }
                Text(text, style = HotMessType.body, color = tokens.inkMuted, modifier = Modifier.padding(Space.s4))
            }
        }
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Couldn't take it back", style = HotMessType.heading) },
            text = { Text(message, style = HotMessType.body) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
            containerColor = tokens.surfaceRaised,
        )
    }
}

@Composable
private fun RequestRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    accent: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = Space.s4, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (accent) tokens.accentInk else tokens.inkMuted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = HotMessType.body, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = HotMessType.caption, color = tokens.inkMuted, maxLines = 1)
        }
        trailing()
    }
}

/**
 * Sign up to sing, or request a song: the song, who it's by, (for karaoke) who's singing, and a note for the host.
 * [submit] returns the server's refusal, shown in the sheet, or null once it went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongRequestSheet(
    kind: SongRequestKind,
    venueName: String,
    hostName: String?,
    viewerName: String?,
    submit: suspend (SongRequestDraft) -> String?,
    onDismiss: () -> Unit,
) {
    val karaoke = kind == SongRequestKind.KARAOKE
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf("") }
    var artist by rememberSaveable { mutableStateOf("") }
    var singer by rememberSaveable { mutableStateOf(viewerName.orEmpty()) }
    var note by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s4)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(if (karaoke) "Sign up to sing" else "Request a song", style = HotMessType.heading, modifier = Modifier.semantics { heading() })
            val taking = hostName?.let { "Tonight at $venueName. $it is taking ${if (karaoke) "signups" else "requests"}." } ?: "Tonight at $venueName."
            Text(taking, style = HotMessType.body, color = tokens.inkMuted)
            val sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            OutlinedTextField(title, { title = it }, label = { Text("Song") }, placeholder = { Text("Song title") }, singleLine = true,
                keyboardOptions = sentences, modifier = Modifier.fillMaxWidth().testTag("request.title"))
            OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, placeholder = { Text("Who it's by (optional)") }, singleLine = true,
                keyboardOptions = sentences, modifier = Modifier.fillMaxWidth())
            if (karaoke) {
                OutlinedTextField(singer, { singer = it }, label = { Text("Singing as") }, placeholder = { Text("Your name") }, singleLine = true,
                    keyboardOptions = sentences, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("How the host calls you up. Singing with someone? Add them: Ada & Bo") })
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note for the host") }, placeholder = { Text("A dedication, or a heads-up (optional)") },
                minLines = 2, maxLines = 4, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, style = HotMessType.body, color = tokens.danger) }
            PrimaryButton(
                if (karaoke) "Put me on the list" else "Send request",
                enabled = title.isNotBlank() && !sending,
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val draft = SongRequestDraft(title, artist, singer.takeIf { karaoke && it != viewerName }, note)
                        val refused = submit(draft)
                        sending = false
                        if (refused == null) onDismiss() else error = refused
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("request.send"),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Cancel") }
            Spacer(Modifier.size(Space.s2))
        }
    }
}
