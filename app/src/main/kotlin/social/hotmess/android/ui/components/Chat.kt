package social.hotmess.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.hotmess.android.ui.theme.ContentMaxWidth
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ChatClearMark
import social.hotmess.core.ChatKind
import social.hotmess.core.ChatPrompt
import social.hotmess.core.ChatPromptShownAs
import social.hotmess.core.ChatRole
import social.hotmess.core.Formatting
import social.hotmess.core.PromptJump
import social.hotmess.core.RecordId
import social.hotmess.core.RoomPerson
import social.hotmess.core.SharedEvent
import java.time.Duration
import java.time.Instant

/*
 * The shared chat kit (design system Chat.md): ChatThread of ChatBubbles, RoomBanner, ChatComposer,
 * ChatPeek for a read-only glimpse of a room, ChatLineRow for flat lines, and PresenceDot. None of
 * it knows about venues or sockets; callers pass a room name and map their own message model to [ChatThreadMessage].
 */

/** Whether someone can be reached right now. Offline draws nothing, except where a room lists someone who just left. */
enum class Presence { ONLINE, PUSH, OFFLINE }

/** The API's presence (GraphQL's `presence`) as the dot draws it; offline and unknown draw nothing. */
fun presenceOf(value: com.audiencekit.Presence?): Presence? = when (value) {
    com.audiencekit.Presence.ONLINE -> Presence.ONLINE
    com.audiencekit.Presence.PUSH -> Presence.PUSH
    com.audiencekit.Presence.OFFLINE, null -> null
}

/**
 * The presence dot: a solid `presence-online` disc, or a `presence-push` ring with a hollow centre,
 * each on a 2dp [ring] (`surface-raised` when unspecified; the surface it sits on). [size] is the
 * dot without its ring: 9 on chat's tiny (24) avatars, 10 on 28, 12 on 40 and up. Null draws nothing, and so does offline
 * unless [showOffline] (a room's people who just left), which is a muted `border-strong` disc.
 */
@Composable
fun PresenceDot(
    state: Presence?,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    ring: Color = Color.Unspecified,
    showOffline: Boolean = false,
) {
    if (state == null || (state == Presence.OFFLINE && !showOffline)) return
    val ringColor = ring.takeOrElse { tokens.surfaceRaised }
    val online = tokens.presenceOnline
    val push = tokens.presencePush
    val edge = tokens.presencePushEdge
    val offline = tokens.borderStrong
    val label = when (state) {
        Presence.ONLINE -> "In chat now"
        Presence.PUSH -> "Gets notifications"
        Presence.OFFLINE -> "Offline"
    }
    Canvas(modifier.size(size + 4.dp).semantics { contentDescription = label }) {
        val outer = this.size.minDimension / 2f
        val inner = outer - 2.dp.toPx()
        drawCircle(ringColor, radius = outer)
        if (state == Presence.ONLINE) {
            drawCircle(online, radius = inner)
        } else if (state == Presence.OFFLINE) {
            drawCircle(offline, radius = inner)
        } else {
            drawCircle(push, radius = inner)
            // The hollow centre, about half the dot (the web's radial gradient stops at 32–36%).
            drawCircle(ringColor, radius = inner * 0.48f)
            val stroke = 1.dp.toPx()
            drawCircle(edge, radius = inner - stroke / 2f, style = Stroke(width = stroke))
        }
    }
}

/** One message as ChatThread draws it. Map from whatever the room's own model is. */
data class ChatThreadMessage(
    val id: String,
    /** Groups consecutive messages; usually the sender's user id. */
    val senderId: String,
    /** Shown above the first bubble of an incoming group, when known. */
    val senderName: String?,
    val avatarUrl: String?,
    /** The message in words; for a rich message its summary, which shows when [rich] can't be drawn. */
    val text: String,
    /** When it was sent; without it there's no time divider before it and no 5-minute grouping cut. */
    val sentAt: Instant?,
    val own: Boolean,
    /** Who the sender is in the room: a RoleTag after their name, and an `accent-soft` bubble for venue and host. */
    val role: ChatRole? = null,
    /** Posted as the venue: its name and photo in a rounded square, with no presence. */
    val asPlace: Boolean = false,
    /** A rich message's card, drawn in place of the bubble; null for plain text. */
    val rich: RichContent? = null,
    /** Posted as the room's audience (a prompt): its mark beside it, no presence and no RoleTag. */
    val asAudience: Boolean = false,
    /** Verified talent posting as their listing: their listing photo stays at [ListingAvatarSize]. */
    val asListing: Boolean = false,
    /** The sender is the viewer's friend: a thin `accent` ring around their tiny avatar. */
    val friend: Boolean = false,
    /** An answer to a prompt: "Prompt" or "Trivia" on its bubble's first line. */
    val promptLabel: String? = null,
    /**
     * A PROMPT message's prompt. A CARD one draws as a PromptCard in the thread; a PINNED one is the
     * PromptBar's, so the thread leaves it out.
     */
    val prompt: ChatPrompt? = null,
    /** The prompt a text line answers (its [ChatPrompt.id]); its Prompt label goes to it. */
    val promptId: String? = null,
    /** Tapping the sender's avatar or name opens their profile sheet: a person posting as themselves, not the viewer. */
    val opensProfile: Boolean = false,
) {
    /** Whether the room still shows it: a special disappears at its end time. */
    fun isShowing(at: Instant): Boolean {
        val content = rich ?: return true
        val ends = content.endsAt ?: return true
        return content.kind != ChatKind.SPECIAL || ends.isAfter(at)
    }
}

/**
 * What a rich message (RichMessage) shows besides its summary: an announcement's [title], [body] and
 * optional photo, a shared [event], a photo with its caption ([body]), or a special's [title] and [body]
 * until [endsAt]. [pinned] marks the announcement pinned under the room's title.
 */
data class RichContent(
    val kind: ChatKind,
    val title: String? = null,
    val body: String? = null,
    val photoUrl: String? = null,
    val event: SharedEvent? = null,
    val endsAt: Instant? = null,
    val pinned: Boolean = false,
) {
    /** Whether it has what its card needs; otherwise the summary shows as a bubble. */
    val drawable: Boolean
        get() = when (kind) {
            ChatKind.TEXT -> false
            ChatKind.ANNOUNCEMENT, ChatKind.SPECIAL -> !title.isNullOrBlank()
            ChatKind.EVENT -> event != null
            ChatKind.PHOTO -> photoUrl != null
            // A prompt draws from its ChatThreadMessage.prompt, as a PromptCard or the PromptBar.
            ChatKind.PROMPT -> false
        }
}

/**
 * What ChatThread tells its PromptBar: [collapsed] to one line (scrolled up, or under a pinned announcement)
 * unless the reader opened it; [highlighted] briefly after an answer's Prompt label led here; [onExpand] opens it.
 */
data class PromptBarState(val collapsed: Boolean, val highlighted: Boolean, val onExpand: () -> Unit)

/** The highlight's key for the PromptBar, which isn't a line in the transcript. */
private const val BarHighlight = "prompt-bar"

private sealed interface ThreadEntry {
    val key: String

    data class Divider(override val key: String, val label: String) : ThreadEntry

    /** Where what has cleared ends and what is current begins, as a labelled rule. */
    data class Cleared(val mark: ChatClearMark) : ThreadEntry {
        override val key: String get() = "cleared-${mark.at}"
    }

    data class BubbleEntry(val message: ChatThreadMessage, val first: Boolean, val last: Boolean) : ThreadEntry {
        override val key: String get() = message.id
    }

    /** A quiet room's prompt, as a PromptCard. */
    data class PromptEntry(val message: ChatThreadMessage, val prompt: ChatPrompt) : ThreadEntry {
        override val key: String get() = message.id
    }
}

private val GroupGap: Duration = Duration.ofMinutes(5)
private val DividerGap: Duration = Duration.ofMinutes(15)

private fun gap(a: ChatThreadMessage, b: ChatThreadMessage): Duration? {
    val from = a.sentAt ?: return null
    val to = b.sentAt ?: return null
    return Duration.between(from, to)
}

private fun dividerBefore(messages: List<ChatThreadMessage>, index: Int): Boolean {
    val message = messages[index]
    if (message.sentAt == null) return false
    if (index == 0) return true
    val between = gap(messages[index - 1], message) ?: return false
    return between >= DividerGap
}

/**
 * Same sender, side and role, under 5 minutes apart (or untimed), and no divider between them. Rich
 * messages stand alone, each with its sender's name and avatar.
 */
private fun sameGroup(messages: List<ChatThreadMessage>, index: Int): Boolean {
    if (index == 0) return false
    val previous = messages[index - 1]
    val message = messages[index]
    if (previous.senderId != message.senderId || previous.own != message.own) return false
    if (previous.role != message.role || previous.asPlace != message.asPlace) return false
    if (previous.rich != null || message.rich != null) return false
    if (previous.prompt != null || message.prompt != null) return false
    if (dividerBefore(messages, index)) return false
    val between = gap(previous, message) ?: return true
    return between < GroupGap
}

/**
 * The transcript's rows. The [clearMark] goes above the first line sent since it, with older lines above; with none
 * since, after them all.
 */
private fun threadEntries(messages: List<ChatThreadMessage>, clearMark: ChatClearMark? = null): List<ThreadEntry> {
    val entries = ArrayList<ThreadEntry>(messages.size + 5)
    val firstAfterClear = clearMark?.let { mark -> messages.indexOfFirst { it.sentAt?.isBefore(mark.at) == false } }
    messages.forEachIndexed { index, message ->
        if (clearMark != null && index == firstAfterClear && index > 0) entries += ThreadEntry.Cleared(clearMark)
        if (dividerBefore(messages, index)) {
            entries += ThreadEntry.Divider("divider-${message.id}", Formatting.shortTime(message.sentAt!!))
        }
        val prompt = message.prompt
        if (prompt != null && prompt.shownAs == ChatPromptShownAs.CARD) {
            // Why it appeared: the minutes nobody said anything before it.
            val quiet = if (index > 0) gap(messages[index - 1], message)?.toMinutes() else null
            if (quiet != null && quiet >= 1) {
                entries += ThreadEntry.Divider("quiet-${message.id}", if (quiet == 1L) "Quiet for 1 minute" else "Quiet for $quiet minutes")
            }
            entries += ThreadEntry.PromptEntry(message, prompt)
            return@forEachIndexed
        }
        val first = !sameGroup(messages, index)
        val last = index == messages.lastIndex || !sameGroup(messages, index + 1)
        entries += ThreadEntry.BubbleEntry(message, first, last)
    }
    if (clearMark != null && messages.isNotEmpty() && firstAfterClear == -1) entries += ThreadEntry.Cleared(clearMark)
    return entries
}

/**
 * A chat room's transcript, oldest first and kept scrolled to the newest message: bubbles grouped
 * by sender (under 5 minutes apart), the sender's name (and RoleTag) above an incoming group and their
 * avatar beside its last bubble, and a time divider at the top and after any 15-minute gap. Rich
 * messages stand alone as RichMessage cards; a special disappears at its end time; the pinned
 * announcement also shows as a PinnedBar on top, which scrolls to it. [onOpenEvent] opens a shared
 * event by id. Empty, it says hello to everyone in [roomName], or out in it when [isLocale] (a city's room).
 *
 * [promptBar] draws the night's pinned prompt under any PinnedBar, told to collapse to one line once the
 * reader scrolls up or when an announcement is pinned too, so the header never holds more than two strips.
 * Pinned prompts never show in the thread; a quiet room's prompt card does, with [onAnswerPrompt] and
 * [onDismissPrompt] ("Not now") for its buttons. [clearMark] draws a rule where the room was cleared, or where
 * what admins alone still see ends.
 */
@Composable
fun ChatThread(
    messages: List<ChatThreadMessage>,
    roomName: String,
    modifier: Modifier = Modifier,
    presence: Map<String, Presence> = emptyMap(),
    state: LazyListState = rememberLazyListState(),
    isLocale: Boolean = false,
    onOpenEvent: (String) -> Unit = {},
    onMessageActions: ((ChatThreadMessage) -> Unit)? = null,
    promptBar: (@Composable (PromptBarState) -> Unit)? = null,
    onAnswerPrompt: (ChatThreadMessage) -> Unit = {},
    onDismissPrompt: (ChatThreadMessage) -> Unit = {},
    clearMark: ChatClearMark? = null,
    onOpenProfile: ((ChatThreadMessage) -> Unit)? = null,
    onOpenPrompt: ((promptId: String) -> PromptJump?)? = null,
) {
    val clock by rememberMinuteClock()
    val visible = remember(messages, clock) {
        messages.filter { it.isShowing(clock) && it.prompt?.shownAs != ChatPromptShownAs.PINNED }
    }
    val entries = remember(visible, clearMark) { threadEntries(visible, clearMark) }
    val pinned = remember(visible) { visible.lastOrNull { it.rich?.kind == ChatKind.ANNOUNCEMENT && it.rich?.pinned == true } }
    val scope = rememberCoroutineScope()
    // The reader opened the collapsed PromptBar (tapping it, or a Prompt label); dragging the transcript lets it collapse again.
    var barOpen by remember { mutableStateOf(false) }
    // What a Prompt label just led to (a line's id, or the bar), highlighted for a moment.
    var highlight by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(highlight) {
        if (highlight != null) {
            delay(1_600L)
            highlight = null
        }
    }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) barOpen = false }
    }
    val jumpToPrompt: ((String) -> Unit)? = onOpenPrompt?.let { resolve ->
        fun(promptId: String) {
            when (val jump = resolve(promptId)) {
                PromptJump.Bar -> {
                    barOpen = true
                    highlight = BarHighlight
                }
                is PromptJump.Line -> {
                    // Only what the transcript draws: a card a reader said "Not now" to, or an older pinned prompt, isn't there.
                    val index = entries.indexOfFirst { it.key == jump.messageId }
                    if (index >= 0) {
                        scope.launch { state.animateScrollToItem(index) }
                        highlight = jump.messageId
                    }
                }
                null -> Unit
            }
        }
    }
    Column(modifier.background(tokens.surface)) {
        if (pinned != null) {
            PinnedBar(pinned.senderName, pinned.rich?.title ?: pinned.text) {
                val index = entries.indexOfFirst { it.key == pinned.id }
                if (index >= 0) scope.launch { state.animateScrollToItem(index) }
            }
        }
        if (promptBar != null) {
            // Scrolled up from the newest line, the bar steps back to one line.
            val scrolledUp by remember(state) { derivedStateOf { state.canScrollForward } }
            promptBar(PromptBarState((pinned != null || scrolledUp) && !barOpen, highlight == BarHighlight) { barOpen = true })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) {
                Message(
                    Icons.AutoMirrored.Rounded.Chat,
                    "Say hello.",
                    if (isLocale) "Everyone out in $roomName can see what you write here." else "Everyone at $roomName can see what you write here.",
                )
                if (clearMark != null) {
                    ClearDivider(clearMark, Modifier.align(Alignment.TopCenter).padding(horizontal = Space.s4, vertical = Space.s3))
                }
            } else {
                Transcript(
                    entries, presence, state, onOpenEvent, onMessageActions, onAnswerPrompt, onDismissPrompt,
                    highlight = highlight, onOpenProfile = onOpenProfile, onOpenPrompt = jumpToPrompt,
                )
            }
        }
    }
}

/** Now, updated every minute, so specials leave the room when they end. */
@Composable
private fun rememberMinuteClock(): State<Instant> = produceState(Instant.now()) {
    while (true) {
        delay(60_000L)
        value = Instant.now()
    }
}

@Composable
private fun BoxScope.Transcript(
    entries: List<ThreadEntry>,
    presence: Map<String, Presence>,
    state: LazyListState,
    onOpenEvent: (String) -> Unit,
    onMessageActions: ((ChatThreadMessage) -> Unit)?,
    onAnswerPrompt: (ChatThreadMessage) -> Unit,
    onDismissPrompt: (ChatThreadMessage) -> Unit,
    highlight: String? = null,
    onOpenProfile: ((ChatThreadMessage) -> Unit)? = null,
    onOpenPrompt: ((String) -> Unit)? = null,
) {
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) state.animateScrollToItem(entries.lastIndex) }
    BoxWithConstraints(Modifier.fillMaxSize().widthIn(max = ContentMaxWidth).align(Alignment.TopCenter)) {
        val bubbleMax = min(280.dp, maxWidth * 0.75f)
        val richMax = min(320.dp, maxWidth * 0.85f)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s3),
        ) {
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is ThreadEntry.Divider -> TimeDivider(entry.label)
                    is ThreadEntry.Cleared -> ClearDivider(entry.mark, Modifier.padding(top = Space.s3, bottom = Space.s1))
                    is ThreadEntry.BubbleEntry -> BubbleRow(
                        entry,
                        bubbleMax,
                        presence[entry.message.senderId],
                        richMax = richMax,
                        onOpenEvent = onOpenEvent,
                        onActions = onMessageActions,
                        onOpenProfile = onOpenProfile,
                        onOpenPrompt = onOpenPrompt,
                    )
                    is ThreadEntry.PromptEntry -> PromptCard(
                        entry.prompt,
                        audienceName = entry.message.senderName?.takeIf { it.isNotBlank() } ?: "Hot Mess",
                        sentAt = entry.message.sentAt,
                        onAnswer = { onAnswerPrompt(entry.message) },
                        onDismiss = { onDismissPrompt(entry.message) },
                        modifier = Modifier.padding(vertical = Space.s2),
                        highlighted = highlight == entry.message.id,
                    )
                }
            }
        }
    }
}

/** A rule across the transcript with what cleared in the middle. */
@Composable
private fun ClearDivider(mark: ChatClearMark, modifier: Modifier = Modifier) {
    val label = mark.label()
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(tokens.inkMuted.copy(alpha = 0.4f)))
        Text(label, style = HotMessType.caption, color = tokens.inkMuted, textAlign = TextAlign.Center)
        Box(Modifier.weight(1f).height(1.dp).background(tokens.inkMuted.copy(alpha = 0.4f)))
    }
}

@Composable
private fun TimeDivider(label: String) {
    Text(
        label,
        style = HotMessType.caption,
        color = tokens.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = Space.s3, bottom = Space.s1),
    )
}

@Composable
private fun BubbleRow(
    entry: ThreadEntry.BubbleEntry,
    bubbleMax: Dp,
    presence: Presence?,
    groupGap: Dp = Space.s2,
    maxLines: Int = Int.MAX_VALUE,
    ring: Color = Color.Unspecified,
    richMax: Dp = bubbleMax,
    onOpenEvent: (String) -> Unit = {},
    onActions: ((ChatThreadMessage) -> Unit)? = null,
    avatarSize: Dp = TinyAvatarSize,
    onOpenProfile: ((ChatThreadMessage) -> Unit)? = null,
    onOpenPrompt: ((String) -> Unit)? = null,
) {
    val message = entry.message
    // A person's avatar and name open their profile sheet. Compose stretches the 24dp avatar's touch target to 48dp.
    val openProfile = onOpenProfile?.takeIf { message.opensProfile && !message.own }?.let { open -> { open(message) } }
    val profileLabel = message.senderName?.takeIf { it.isNotBlank() }?.let { "See $it's profile" } ?: "See their profile"
    // People are tiny; the venue and verified talent posting as their listing keep their 28.
    val avatar = if (message.asPlace || message.asListing) ListingAvatarSize else avatarSize
    val rich = message.rich?.takeIf { it.drawable }
    // Someone else's line: a long press reports it or blocks its sender, also offered to screen readers.
    val actions = onActions?.takeIf { !message.own }
    val actionsModifier = if (actions == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(message.id) { detectTapGestures(onLongPress = { actions(message) }) }
            .semantics { customActions = listOf(CustomAccessibilityAction("Report or block") { actions(message); true }) }
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = if (entry.last) groupGap else 2.dp),
        horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!message.own) {
            if (entry.last && message.asAudience) {
                AudienceMark(message.senderName?.takeIf { it.isNotBlank() } ?: "Hot Mess", size = avatar)
            } else if (entry.last) {
                val friendRing = tokens.accent
                Avatar(
                    message.avatarUrl,
                    message.senderName.orEmpty(),
                    size = avatar,
                    // A friend's tiny avatar: a 1.5dp `accent` ring outside a 2dp gap, drawn outside its bounds.
                    modifier = Modifier
                        .then(
                            if (message.friend && !message.asPlace && !message.asListing) {
                                Modifier.drawBehind {
                                    val stroke = 1.5.dp.toPx()
                                    drawCircle(friendRing, radius = size.minDimension / 2f + 2.dp.toPx() + stroke / 2f, style = Stroke(width = stroke))
                                }
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (openProfile != null) {
                                Modifier.clickable(
                                    interactionSource = null,
                                    indication = ripple(bounded = false, radius = 24.dp),
                                    role = Role.Button,
                                    onClickLabel = profileLabel,
                                    onClick = openProfile,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    // A place shows no presence, in a rounded square so it never looks like a person.
                    presence = if (message.asPlace) null else presence,
                    presenceRing = ring,
                    shape = if (message.asPlace) Radius.md else CircleShape,
                )
            } else {
                Spacer(Modifier.width(avatar))
            }
            Spacer(Modifier.width(Space.s2))
        }
        Column(
            modifier = (if (rich != null) Modifier.widthIn(max = richMax).fillMaxWidth() else Modifier).then(actionsModifier),
            horizontalAlignment = if (message.own) Alignment.End else Alignment.Start,
        ) {
            val name = message.senderName?.takeIf { it.isNotBlank() }
            if (!message.own && entry.first && (name != null || message.role != null)) {
                Row(
                    Modifier.widthIn(max = if (rich != null) richMax else bubbleMax).padding(start = Space.s3, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (name != null) {
                        Text(
                            name,
                            style = HotMessType.caption,
                            color = tokens.inkMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .then(
                                    if (openProfile != null) {
                                        Modifier.clickable(role = Role.Button, onClickLabel = profileLabel, onClick = openProfile)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                    RoleTag(message.role)
                }
            }
            if (rich != null) {
                RichMessage(rich, message.senderName, onOpenEvent = onOpenEvent)
            } else {
                ChatBubble(
                    message.text,
                    own = message.own,
                    last = entry.last,
                    maxWidth = bubbleMax,
                    maxLines = maxLines,
                    tinted = !message.own && (message.role == ChatRole.VENUE || message.role == ChatRole.HOST),
                    label = message.promptLabel,
                    onLabelClick = message.promptId?.let { id -> onOpenPrompt?.let { open -> { open(id) } } },
                )
            }
        }
    }
}

/**
 * One chat bubble: `accent` / `on-accent` for your own, `surface-raised` / `ink` with a small
 * shadow for everyone else's, or `accent-soft` / `ink` with no shadow when [tinted] (the venue's and
 * hosts'). The last bubble of a group drops its corner nearest the sender to `radius-sm`. [maxLines]
 * clamps the text with an ellipsis (ChatPeek shows two). An answer to a prompt passes its [label]
 * ("Prompt", "Trivia"), drawn as the bubble's first line.
 */
@Composable
fun ChatBubble(
    text: String,
    own: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 280.dp,
    maxLines: Int = Int.MAX_VALUE,
    tinted: Boolean = false,
    label: String? = null,
    onLabelClick: (() -> Unit)? = null,
) {
    val tail = CornerSize(4.dp)
    val shape = when {
        !last -> Radius.bubble
        own -> Radius.bubble.copy(bottomEnd = tail)
        else -> Radius.bubble.copy(bottomStart = tail)
    }
    val fill = when {
        own -> tokens.accent
        tinted -> tokens.accentSoft
        else -> tokens.surfaceRaised
    }
    val bubble = modifier
        .widthIn(max = maxWidth)
        .then(if (own || tinted) Modifier else Modifier.shadow(1.dp, shape))
        .clip(shape)
        .background(fill)
        .padding(horizontal = Space.s3, vertical = Space.s2)
    val ink = if (own) tokens.onAccent else tokens.ink
    if (label == null) {
        Text(text, style = HotMessType.body, color = ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = bubble)
    } else {
        Column(bubble) {
            PromptLabel(label, if (own) tokens.onAccent else tokens.inkMuted, onOpen = onLabelClick)
            Text(text, style = HotMessType.body, color = ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Who a sender is in the room, as a word after their name: "Venue" in solid `accent`, "Host" in
 * `accent-soft` with `accent-ink`, "Staff" in `control-fill`. Nothing for everyone else.
 */
@Composable
fun RoleTag(role: ChatRole?, modifier: Modifier = Modifier) {
    role ?: return
    val (fill, ink) = when (role) {
        ChatRole.VENUE -> tokens.accent to tokens.onAccent
        ChatRole.HOST -> tokens.accentSoft to tokens.accentInk
        ChatRole.STAFF -> tokens.controlFill to tokens.ink
    }
    Text(
        role.label,
        style = RoleTagText,
        color = ink,
        maxLines = 1,
        modifier = modifier.clip(Radius.pill).background(fill).padding(horizontal = 6.dp),
    )
}

private val RoleTagText = HotMessType.caption.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.02.em)
private val RichOverline = HotMessType.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.06.em)

/**
 * What hosts, the venue and staff can post besides text (design system RichMessage), each standing
 * alone in the room:
 * - announcement: an `accent-soft` card with an optional 16:9 photo, an "Announcement" overline
 *   ("Pinned announcement" when pinned), the title and the body;
 * - event: the shared event as a photo card that opens it, with the caption under it;
 * - photo: a 4:3 photo up to 240 wide with its caption;
 * - special: an `accent-soft` card with a dashed `accent-ink` edge, a "Special · until 11pm"
 *   overline, the title and the body.
 * [author] names the sender for the photo's description.
 */
@Composable
fun RichMessage(content: RichContent, author: String?, modifier: Modifier = Modifier, onOpenEvent: (String) -> Unit = {}) {
    val body = content.body?.takeIf { it.isNotBlank() }
    when (content.kind) {
        ChatKind.PHOTO -> Column(
            modifier
                .widthIn(max = 240.dp)
                .shadow(1.dp, Radius.bubble)
                .clip(Radius.bubble)
                .background(tokens.surfaceRaised),
        ) {
            val description = body ?: author?.let { "Photo from $it" } ?: "Photo"
            RemoteImage(
                content.photoUrl,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .semantics {
                        contentDescription = description
                        role = Role.Image
                    },
            )
            if (body != null) {
                Text(
                    body,
                    style = HotMessType.body,
                    color = tokens.ink,
                    modifier = Modifier.padding(start = Space.s3, end = Space.s3, top = Space.s2, bottom = 10.dp),
                )
            }
        }
        ChatKind.EVENT -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val event = content.event ?: return@Column
            val start = event.startAt
            PhotoCard(
                url = null,
                onClick = { onOpenEvent(event.id) },
                topClearance = if (start != null) 68.dp else 52.dp,
                leading = {
                    if (start != null) {
                        GlassPill(
                            shape = Radius.md,
                            padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = Formatting.dateTime(start) },
                        ) {
                            Column(Modifier.widthIn(min = 30.dp).clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Formatting.monthAbbreviation(start), style = HotMessType.caption)
                                Text(Formatting.dayOfMonth(start), style = HotMessType.heading)
                            }
                        }
                    }
                },
            ) {
                Text(event.name ?: "An event", style = HotMessType.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (start != null) Text(Formatting.shortTime(start), style = HotMessType.subheading, maxLines = 1)
            }
            if (body != null) {
                Text(body, style = HotMessType.body, color = tokens.ink, modifier = Modifier.padding(horizontal = Space.s1))
            }
        }
        ChatKind.ANNOUNCEMENT -> Column(modifier.fillMaxWidth().clip(Radius.bubble).background(tokens.accentSoft)) {
            if (content.photoUrl != null) {
                RemoteImage(content.photoUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            RichText(if (content.pinned) "Pinned announcement" else "Announcement", content.title, body)
        }
        ChatKind.SPECIAL -> {
            val edge = tokens.accentInk
            val overline = content.endsAt?.let { "Special · until ${Formatting.clock(it)}" } ?: "Special"
            Column(
                modifier
                    .fillMaxWidth()
                    .clip(Radius.bubble)
                    .background(tokens.accentSoft)
                    .drawBehind {
                        val stroke = 1.5.dp.toPx()
                        val radius = 16.dp.toPx() - stroke / 2f
                        drawRoundRect(
                            edge,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(radius, radius),
                            style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        )
                    },
            ) {
                RichText(overline, content.title, body)
            }
        }
        ChatKind.TEXT, ChatKind.PROMPT -> Unit
    }
}

/** A rich card's words: the overline in `accent-ink`, then the title and body in `ink`. */
@Composable
private fun RichText(overline: String, title: String?, body: String?) {
    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = Space.s3)) {
        Text(overline.uppercase(), style = RichOverline, color = tokens.accentInk)
        if (!title.isNullOrBlank()) {
            Text(title, style = HotMessType.heading, color = tokens.ink, modifier = Modifier.padding(top = 2.dp))
        }
        if (body != null) {
            Text(body, style = HotMessType.body, color = tokens.ink, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The pinned announcement under a room's title: a pin, the sender in bold and the announcement's
 * title on one line, on `accent-soft`. Tapping it runs [onOpen], which scrolls to the announcement.
 */
@Composable
fun PinnedBar(author: String?, title: String, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(tokens.accentSoft)
            .clickable(role = Role.Button, onClickLabel = "Show the announcement", onClick = onOpen)
            .padding(horizontal = Space.s4, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = tokens.accentInk, modifier = Modifier.size(16.dp))
        Text(
            buildAnnotatedString {
                if (!author.isNullOrBlank()) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(author) }
                    append(" ")
                }
                append(title)
            },
            style = HotMessType.bodySmall,
            color = tokens.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Why the reader is (or isn't fully) in a room. Only one shows; range wins. */
enum class RoomBannerKind { RANGE, CONNECTING, OFFLINE }

/** The strip under a chat room's title. [isLocale] rooms are a city's, which you're in, not at. */
@Composable
fun RoomBanner(kind: RoomBannerKind, roomName: String, modifier: Modifier = Modifier, isLocale: Boolean = false) {
    when (kind) {
        RoomBannerKind.RANGE -> Row(
            modifier.fillMaxWidth().background(tokens.warningSoft).padding(horizontal = Space.s4, vertical = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = tokens.warning, modifier = Modifier.size(20.dp))
            Text(
                if (isLocale) {
                    "You're not in $roomName. You're in this chat because you're an admin."
                } else {
                    "You're not at $roomName. You're in this chat because you're an admin."
                },
                style = HotMessType.bodySmall,
                color = tokens.ink,
            )
        }
        RoomBannerKind.CONNECTING, RoomBannerKind.OFFLINE -> Text(
            if (kind == RoomBannerKind.CONNECTING) "Connecting…" else "Offline. Reconnecting…",
            style = HotMessType.bodySmall,
            color = tokens.inkMuted,
            modifier = modifier.fillMaxWidth().background(tokens.controlFill).padding(horizontal = Space.s4, vertical = Space.s1),
        )
    }
}

/**
 * Who else is in the room, under its banner (and above any pinned announcement): "N here now", then a
 * row of tiny (24) faces, in the order given (callers put friends first; see RoomPeople.hereNow). A
 * friend's face has a thin `accent` ring outside a small gap. A face in the room carries the online dot;
 * someone here recently (who left in the last half hour) the offline dot, or the push dot when they're
 * [RoomPerson.reachable] or in [reachable]. The viewer ([viewerId]) is "You", in the room, and counts
 * among "here now". Draws nothing when [people] is empty.
 */
@Composable
fun HereNowStrip(
    people: List<RoomPerson>,
    reachable: Set<String> = emptySet(),
    viewerId: String? = null,
    modifier: Modifier = Modifier,
    onOpenPerson: ((RoomPerson) -> Unit)? = null,
) {
    if (people.isEmpty()) return
    val here = people.count { !it.isRecent }
    val recently = people.size - here
    val hairline = tokens.border
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.surfaceRaised)
            .drawBehind { drawLine(hairline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx()) }
            .padding(vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Text(
            if (recently == 0) "$here here now" else "$here here now · $recently here recently",
            style = HotMessType.caption,
            color = tokens.inkMuted,
            modifier = Modifier.padding(horizontal = Space.s4),
        )
        LazyRow(
            // Room for a friend's ring and the presence dot, which draw outside the faces.
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s1),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            items(people, key = { it.userId }) { person ->
                val viewer = RecordId.same(person.userId, viewerId)
                val presence = when {
                    viewer || !person.isRecent -> Presence.ONLINE
                    person.reachable || person.userId in reachable -> Presence.PUSH
                    else -> Presence.OFFLINE
                }
                HereNowFace(
                    if (viewer) person.copy(name = "You", friend = false, recentUntil = null) else person,
                    presence,
                    onOpen = onOpenPerson?.takeIf { !viewer }?.let { open -> { open(person) } },
                )
            }
        }
    }
}

/**
 * One face in [HereNowStrip], at chat's [TinyAvatarSize]: read out as "Aurora Bell, friend, here now",
 * "You, here now", or "Jo P., here recently". A friend's has a 1.5dp `accent` ring outside a 2dp gap.
 */
@Composable
private fun HereNowFace(person: RoomPerson, presence: Presence, onOpen: (() -> Unit)? = null) {
    val name = person.name ?: "Someone"
    val whenHere = if (person.isRecent) "here recently" else "here now"
    val description = if (person.friend) "$name, friend, $whenHere" else "$name, $whenHere"
    val ringColor = tokens.accent
    Avatar(
        person.avatarUrl,
        name,
        size = TinyAvatarSize,
        modifier = Modifier
            .clearAndSetSemantics {
                contentDescription = description
                if (onOpen != null) {
                    role = Role.Button
                    onClick(label = "See profile") { onOpen(); true }
                }
            }
            .then(
                if (onOpen != null) {
                    Modifier.clickable(interactionSource = null, indication = ripple(bounded = false, radius = 24.dp), onClick = onOpen)
                } else {
                    Modifier
                },
            )
            .then(
                if (person.friend) {
                    Modifier.drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawCircle(ringColor, radius = size.minDimension / 2f + 2.dp.toPx() + stroke / 2f, style = Stroke(width = stroke))
                    }
                } else {
                    Modifier
                },
            ),
        presence = presence,
        presenceRing = tokens.surfaceRaised,
        showOffline = true,
    )
}

/**
 * The message field and send button pinned under a room. Look only: [onSend] runs from the button
 * and the keyboard's send key; [sendEnabled] is the caller's (empty or offline disables it). While
 * [answering] a prompt (its text), an AnswerChip sits above the field, whose close button runs
 * [onStopAnswering], and a hint under it says the answer shows with a Prompt label.
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    modifier: Modifier = Modifier,
    answering: String? = null,
    onStopAnswering: () -> Unit = {},
) {
    val hairline = tokens.border
    Column(
        modifier
            .fillMaxWidth()
            .background(tokens.surfaceRaised)
            .drawBehind { drawLine(hairline, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
            .padding(horizontal = Space.s3, vertical = Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        if (answering != null) AnswerChip(answering, onClose = onStopAnswering)
        ComposerRow(value, onValueChange, onSend, sendEnabled)
        if (answering != null) {
            Text(
                "Your answer shows in the room with a Prompt label.",
                style = HotMessType.caption,
                color = tokens.inkMuted,
                modifier = Modifier.padding(horizontal = Space.s1),
            )
        }
    }
}

@Composable
private fun ComposerRow(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, sendEnabled: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val pill = RoundedCornerShape(20.dp)
        val ink = tokens.ink
        val placeholder = tokens.inkMuted
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            textStyle = HotMessType.body.copy(color = ink),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            maxLines = 4,
            interactionSource = interaction,
            cursorBrush = SolidColor(tokens.accent),
            decorationBox = { field ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(pill)
                        .background(tokens.surfaceSunken)
                        .border(if (focused) 2.dp else 1.dp, if (focused) tokens.focus else tokens.borderStrong, pill)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text("Message", style = HotMessType.body, color = placeholder)
                    field()
                }
            },
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (sendEnabled) tokens.accent else tokens.controlFill)
                .clickable(enabled = sendEnabled, role = Role.Button, onClick = onSend)
                .semantics { contentDescription = "Send" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.ArrowUpward,
                contentDescription = null,
                tint = if (sendEnabled) tokens.onAccent else tokens.inkMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** ChatPeek's bubbles: grouped like the thread, but with no time dividers drawn. */
private fun peekEntries(messages: List<ChatThreadMessage>): List<ThreadEntry.BubbleEntry> =
    messages.mapIndexed { index, message ->
        val first = !sameGroup(messages, index)
        val last = index == messages.lastIndex || !sameGroup(messages, index + 1)
        ThreadEntry.BubbleEntry(message, first, last)
    }

data class ChatPeekParticipant(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val presence: Presence? = null,
    val isPlace: Boolean = false,
)


/**
 * A read-only glimpse of a chat room (design system ChatPeek): a `surface-sunken` panel with
 * `radius-photo` corners holding [title], "N here now" when [online] is known, the last [limit]
 * [messages] (oldest first) as the room's own ChatBubbles clamped to two lines, and a "Join the
 * chat" call to action. The whole panel is one button that runs [onOpen]; screen readers hear
 * "Open the chat at [room], N recent messages" instead of each bubble. It holds no connection;
 * callers refresh [messages] when their screen appears.
 */

@Composable
fun ChatPeek(
    messages: List<ChatThreadMessage>,
    title: String,
    room: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    online: Int? = null,
    presence: Map<String, Presence> = emptyMap(),
    limit: Int = 3,
    participants: List<ChatPeekParticipant> = emptyList(),
) {
    val entries = remember(messages, limit) { peekEntries(messages.takeLast(limit)) }
    val count = entries.size
    val description = when (count) {
        0 -> "Open the chat at $room, no recent messages"
        1 -> "Open the chat at $room, 1 recent message"
        else -> "Open the chat at $room, $count recent messages"
    }
    val panel = tokens.surfaceSunken
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radius.photo)
            .background(panel)
            .clearAndSetSemantics {
                contentDescription = description + participants.joinToString(prefix = if (participants.isEmpty()) "" else "; ", separator = "; ") {
                    it.name + when (it.presence) {
                        Presence.ONLINE -> ", in chat"
                        Presence.PUSH -> ", reachable by notification"
                        else -> ""
                    }
                }
                role = Role.Button
                onClick { onOpen(); true }
            }
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.s4, vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = HotMessType.subheading.copy(fontWeight = FontWeight.Bold),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (online != null && online > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PresenceDot(Presence.ONLINE, ring = panel)
                    Text("$online here now", style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
                }
            }
        }
        if (participants.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                contentPadding = PaddingValues(vertical = Space.s1),
            ) {
                items(participants, key = { it.id }) { participant ->
                    Avatar(
                        // Tiny in a peek; the room's place keeps its 28.
                        participant.avatarUrl, participant.name, size = if (participant.isPlace) ListingAvatarSize else PeekAvatarSize,
                        presence = participant.presence, presenceRing = panel,
                        shape = if (participant.isPlace) RoundedCornerShape(8.dp) else CircleShape,
                    )
                }
            }
        }
        if (entries.isEmpty()) {
            Text("No one's said anything yet. Say hello.", style = HotMessType.body, color = tokens.inkMuted)
        } else {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bubbleMax = min(280.dp, maxWidth * 0.75f)
                Column {
                    entries.forEachIndexed { index, entry ->
                        BubbleRow(
                            entry,
                            bubbleMax,
                            presence[entry.message.senderId],
                            groupGap = if (index == entries.lastIndex) 0.dp else Space.s2,
                            maxLines = 2,
                            ring = panel,
                            avatarSize = PeekAvatarSize,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (entries.isEmpty()) "Start the chat" else "Join the chat",
                style = HotMessType.label,
                color = tokens.accentInk,
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = tokens.accentInk,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A flat, bubble-less chat line (the admin transcript's ChatLine): a tiny (24) avatar, then the name (with
 * its RoleTag) and a pre-formatted time on one caption line, then the text in two lines. Rich messages
 * pass their summary as [text].
 */
@Composable
fun ChatLineRow(
    author: String?,
    avatarUrl: String?,
    text: String,
    time: String?,
    modifier: Modifier = Modifier,
    presence: Presence? = null,
    maxLines: Int = 2,
    role: ChatRole? = null,
) {
    val name = author?.takeIf { it.isNotBlank() } ?: "Someone"
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(avatarUrl, author.orEmpty(), size = TinyAvatarSize, presence = presence)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = HotMessType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = tokens.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                RoleTag(role)
                if (time != null) Text(time, style = HotMessType.caption, color = tokens.inkMuted, maxLines = 1)
            }
            Text(
                text,
                style = HotMessType.body,
                color = tokens.ink,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private val previewStart: Instant = Instant.parse("2026-10-09T21:00:00Z")

private val previewMessages = listOf(
    ChatThreadMessage("1", "sam", "Sam", null, "Anyone else here for the drag show?", previewStart, own = false, friend = true, promptLabel = "Prompt"),
    ChatThreadMessage("2", "sam", "Sam", null, "Front left by the stage", previewStart.plusSeconds(40), own = false),
    ChatThreadMessage("3", "me", "Me", null, "On my way in now", previewStart.plusSeconds(120), own = true, promptLabel = "Prompt"),
    ChatThreadMessage("4", "me", "Me", null, "Save me a spot!", previewStart.plusSeconds(150), own = true),
    ChatThreadMessage("5", "alex", "Alex", null, "Second set is starting", previewStart.plusSeconds(20 * 60), own = false),
)

@Preview(name = "Chat thread", widthDp = 360, heightDp = 520)
@Composable
private fun ChatThreadPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            RoomBanner(RoomBannerKind.RANGE, "The Wildrose")
            ChatThread(
                previewMessages,
                "The Wildrose",
                Modifier.weight(1f).fillMaxWidth(),
                presence = mapOf("sam" to Presence.ONLINE, "alex" to Presence.PUSH),
            )
            var draft by remember { mutableStateOf("") }
            ChatComposer(draft, { draft = it }, onSend = {}, sendEnabled = draft.isNotBlank())
        }
    }
}

private val previewRichMessages = listOf(
    ChatThreadMessage(
        "r1", "venue", "Neighbours", null, "Announcement: Coat check closes at midnight", previewStart, own = false,
        role = ChatRole.VENUE, asPlace = true,
        rich = RichContent(
            ChatKind.ANNOUNCEMENT,
            title = "Coat check closes at midnight",
            body = "Grab your things before the late set. Lost and found is at the front door.",
            pinned = true,
        ),
    ),
    ChatThreadMessage("r2", "aurora", "Aurora B.", null, "Noted, thanks!", previewStart.plusSeconds(60), own = false),
    ChatThreadMessage("r3", "kiko", "DJ Kiko", null, "Next week I'm back with the disco set", previewStart.plusSeconds(180), own = false, role = ChatRole.HOST),
    ChatThreadMessage(
        "r4", "kiko", "DJ Kiko", null, "Shared an event: Sunset Social", previewStart.plusSeconds(200), own = false,
        role = ChatRole.HOST,
        rich = RichContent(ChatKind.EVENT, body = "Come through", event = SharedEvent("e1", "Sunset Social", previewStart.plusSeconds(8 * 86_400L))),
    ),
    ChatThreadMessage(
        "r5", "venue", "Neighbours", null, "Special: Free photo booth prints", previewStart.plusSeconds(300), own = false,
        role = ChatRole.VENUE, asPlace = true,
        rich = RichContent(ChatKind.SPECIAL, title = "Free photo booth prints", body = "By the back stairs", endsAt = Instant.now().plusSeconds(3_600)),
    ),
    ChatThreadMessage("r6", "sam", "Sam O.", null, "Be kind in here, folks.", previewStart.plusSeconds(360), own = false, role = ChatRole.STAFF),
)

@Preview(name = "Roles and rich messages", widthDp = 360, heightDp = 760)
@Composable
private fun ChatThreadRichPreview() {
    HotMessTheme(darkTheme = false) {
        ChatThread(
            previewRichMessages,
            "Neighbours",
            Modifier.fillMaxSize(),
            presence = mapOf("kiko" to Presence.ONLINE, "aurora" to Presence.ONLINE),
        )
    }
}

@Preview(name = "Chat thread, dark", widthDp = 360, heightDp = 520)
@Composable
private fun ChatThreadDarkPreview() {
    HotMessTheme(darkTheme = true) {
        Column(Modifier.fillMaxSize()) {
            RoomBanner(RoomBannerKind.OFFLINE, "The Wildrose")
            ChatThread(previewMessages, "The Wildrose", Modifier.weight(1f).fillMaxWidth())
            ChatComposer("Hi there", {}, onSend = {}, sendEnabled = true)
        }
    }
}

@Preview(name = "Empty room", widthDp = 360, heightDp = 360)
@Composable
private fun ChatThreadEmptyPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            RoomBanner(RoomBannerKind.CONNECTING, "The Wildrose")
            ChatThread(emptyList(), "The Wildrose", Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Preview(name = "Presence and chat lines", widthDp = 360)
@Composable
private fun PresencePreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.background(tokens.surfaceRaised)) {
            Row(Modifier.padding(Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Avatar(null, "Sam Lee", size = 28.dp, presence = Presence.ONLINE)
                Avatar(null, "Alex Kim", size = 28.dp, presence = Presence.PUSH)
                Avatar(null, "Jo Park", size = 44.dp, presence = Presence.ONLINE)
                Avatar(null, "Ri Moe", size = 44.dp, presence = Presence.PUSH)
                Avatar(null, "No One", size = 44.dp, presence = Presence.OFFLINE)
            }
            ChatLineRow("Sam Lee", null, "Anyone else here for the drag show? Front left by the stage, come say hi.", "2m", presence = Presence.ONLINE)
            ChatLineRow(null, null, "Second set is starting", "14m")
        }
    }
}

@Preview(name = "Chat peek", widthDp = 360)
@Composable
private fun ChatPeekPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.background(tokens.surface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
            ChatPeek(
                previewMessages + ChatThreadMessage(
                    "6",
                    "alex",
                    "Alex",
                    null,
                    "Second set is starting and the line for the photo booth is already out the door, so go now if you want a picture",
                    previewStart.plusSeconds(21 * 60),
                    own = false,
                ),
                title = "Chat",
                room = "The Wildrose",
                onOpen = {},
                online = 12,
                presence = mapOf("alex" to Presence.ONLINE),
            )
            ChatPeek(emptyList(), title = "Small talk", room = "The Wildrose", onOpen = {})
        }
    }
}

@Preview(name = "Chat peek, dark", widthDp = 360)
@Composable
private fun ChatPeekDarkPreview() {
    HotMessTheme(darkTheme = true) {
        Column(Modifier.background(tokens.surface).padding(Space.s4)) {
            ChatPeek(previewMessages, title = "Small talk", room = "The Wildrose", onOpen = {})
        }
    }
}

private val previewHereNow = listOf(
    RoomPerson("aurora", "Aurora Bell", friend = true),
    RoomPerson("wren", "Wren Fox", friend = true),
    RoomPerson("amy", "Amy R."),
    RoomPerson("jo", "Jo P."),
    RoomPerson("kiko", "Kiko M."),
    RoomPerson("zed", "Zed Q."),
    // Here recently: one a notification reaches, one offline.
    RoomPerson("sam", "Sam Lee", friend = true, recentUntil = previewStart.plusSeconds(1_800), reachable = true),
    RoomPerson("ri", "Ri M.", recentUntil = previewStart.plusSeconds(1_200)),
)

@Preview(name = "Here now", widthDp = 360, heightDp = 420)
@Composable
private fun HereNowPreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            HereNowStrip(previewHereNow)
            ChatThread(previewRichMessages, "Neighbours", Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Preview(name = "Here now, dark", widthDp = 360)
@Composable
private fun HereNowDarkPreview() {
    HotMessTheme(darkTheme = true) {
        HereNowStrip(previewHereNow.take(3))
    }
}
