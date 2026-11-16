package social.hotmess.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.BlockedUser
import social.hotmess.core.CableConnection
import social.hotmess.core.Formatting
import social.hotmess.core.HotMessApi
import social.hotmess.core.PrivateChat
import social.hotmess.core.PrivateChatConnection
import social.hotmess.core.PrivateChatCopy
import social.hotmess.core.PrivateChatLines
import social.hotmess.core.PrivateChatMessage
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import java.time.Duration
import java.time.Instant

/**
 * One Ping or Quickie chat while it's on screen: its lines (in memory only, never saved), new ones over the cable, and
 * sending. [fetch] reads the chat from the ping or pairing; it's null until there's a chat (nobody's in yet, or the two
 * haven't both said yes) and after it closes.
 */
@Stable
class PrivateChatController(
    private val api: HotMessApi,
    private val cable: CableConnection,
    private val scope: CoroutineScope,
    private val fetch: suspend () -> PrivateChat?,
) {
    var chat by mutableStateOf<PrivateChat?>(null)
        private set
    var messages by mutableStateOf<List<PrivateChatMessage>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var closed by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var draft by mutableStateOf("")
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        try {
            val fetched = fetch()
            when {
                fetched != null -> {
                    chat = fetched
                    messages = PrivateChatLines.merge(messages, fetched.messages)
                }
                // It was open and now isn't: it ended, and what was said is gone.
                chat != null -> close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!loaded) error = ApiError.of(e).message
        }
        loaded = true
    }

    /** Loads, then follows the cable while collected, reloading after every (re)connect and at the closing time. */
    suspend fun run() {
        load()
        scope.launch { watchClosing() }
        val audienceId = audienceId() ?: return
        var backoff = 1_000L
        while (!closed) {
            var connected = false
            PrivateChatConnection(audienceId, cable).events().takeWhile { event ->
                when (event) {
                    PrivateChatConnection.Event.Connected -> {
                        connected = true
                        backoff = 1_000L
                        load()
                    }
                    is PrivateChatConnection.Event.Received -> receive(event.message)
                    is PrivateChatConnection.Event.Closed -> if (event.chatId == chat?.id) close()
                    is PrivateChatConnection.Event.Disconnected -> Unit
                    PrivateChatConnection.Event.Rejected, PrivateChatConnection.Event.Ended -> Unit
                }
                !closed && event !is PrivateChatConnection.Event.Rejected && event !is PrivateChatConnection.Event.Ended
            }.collect {}
            if (closed) return
            // Ended or refused (an API without chats refuses): try again later, slower each time.
            delay(if (connected) 1_000L else backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private suspend fun audienceId(): String? =
        try {
            api.client.audienceId()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun watchClosing() {
        while (!closed) {
            val closesAt = chat?.closesAt
            val wait = closesAt?.let { Duration.between(Instant.now(), it).toMillis() } ?: 60_000L
            delay(wait.coerceIn(1_000L, 60_000L))
            if (closesAt != null && !Instant.now().isBefore(closesAt)) load()
        }
    }

    private fun receive(message: PrivateChatMessage) {
        val current = chat
        if (current == null) {
            // The first line opens the chat: read it whole.
            scope.launch { load() }
        } else if (message.chatId == current.id) {
            messages = PrivateChatLines.merge(messages, listOf(message))
        }
    }

    private fun close() {
        closed = true
        messages = emptyList()
    }

    fun send() {
        val body = draft.trim()
        val id = chat?.id ?: return
        if (body.isEmpty() || sending || closed) return
        sending = true
        scope.launch {
            try {
                val sent = api.sendPrivateChatMessage(id, body.take(PrivateChatCopy.BODY_LIMIT))
                messages = PrivateChatLines.merge(messages, listOf(sent))
                if (draft.trim() == body) draft = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ApiError.of(e).message ?: "Couldn't send that. Try again in a moment."
            } finally {
                sending = false
            }
        }
    }
}

@Composable
fun rememberPrivateChatController(key: Any, fetch: suspend () -> PrivateChat?): PrivateChatController {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    return remember(key) { PrivateChatController(graph.api, graph.cable, scope, fetch) }
}

/** A Ping or Quickie chat in a sheet of its own: [title] is PrivateChatCopy's for the kind, [waiting] shown until it opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatSheet(controller: PrivateChatController, title: String, waiting: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(controller) { controller.run() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.s4)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text(title, style = HotMessType.title)
            Text(PrivateChatCopy.EPHEMERAL, style = HotMessType.bodySmall, color = tokens.inkMuted)
            PrivateChatBody(controller, waiting)
        }
    }
}

/** The lines and the composer, or why there's nothing to say yet. */
@Composable
fun PrivateChatBody(controller: PrivateChatController, waiting: String) {
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    when {
        !controller.loaded -> Box(Modifier.fillMaxWidth().padding(Space.s8), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        controller.closed -> Text(PrivateChatCopy.CLOSED, style = HotMessType.body, color = tokens.inkMuted)
        controller.chat == null -> Text(controller.error ?: waiting, style = HotMessType.body, color = tokens.inkMuted)
        else -> {
            Lines(controller.messages) { line ->
                line.sender?.let { reporting = ReportSubject(ReportTarget.PRIVATE_MESSAGE, line.id, BlockedUser(it.id, it.name)) }
            }
            controller.error?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
            Composer(controller)
        }
    }
    reporting?.let { ReportDialog(it) { reporting = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Lines(messages: List<PrivateChatMessage>, onReport: (PrivateChatMessage) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    if (messages.isEmpty()) {
        Text("No messages yet. Say hi.", style = HotMessType.bodySmall, color = tokens.inkMuted)
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).testTag("privateChat.lines"),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        items(messages, key = { it.id }) { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.isMine) Arrangement.End else Arrangement.Start) {
                Column(
                    Modifier
                        .widthIn(max = 280.dp)
                        .background(if (line.isMine) tokens.accent else tokens.surfaceRaised, Radius.bubble)
                        // Long-press someone else's line to report it (and block them).
                        .combinedClickable(onClick = {}, onLongClick = { if (!line.isMine) onReport(line) })
                        .padding(horizontal = Space.s3, vertical = Space.s2),
                ) {
                    if (!line.isMine) {
                        Text(line.sender?.name ?: "Someone", style = HotMessType.caption, color = tokens.accentInk)
                    }
                    Text(line.body, style = HotMessType.body, color = if (line.isMine) tokens.onAccent else tokens.ink)
                    line.sentAt?.let {
                        Text(
                            Formatting.clock(it),
                            style = HotMessType.caption,
                            color = if (line.isMine) tokens.onAccent.copy(alpha = 0.7f) else tokens.inkMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(controller: PrivateChatController) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
        OutlinedTextField(
            value = controller.draft,
            onValueChange = {
                controller.draft = it.take(PrivateChatCopy.BODY_LIMIT)
                controller.error = null
            },
            placeholder = { Text(PrivateChatCopy.PLACEHOLDER) },
            modifier = Modifier.weight(1f).testTag("privateChat.field"),
            maxLines = 4,
        )
        IconButton(
            onClick = { controller.send() },
            enabled = controller.draft.isNotBlank() && !controller.sending,
            modifier = Modifier.testTag("privateChat.send"),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = tokens.accent)
        }
    }
}
