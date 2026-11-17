package social.hotmess.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant

/**
 * A Ping's or Quickie pairing's chat (the API's `PrivateChat`): a short-lived chat for a ping's circle, or a Quickie
 * pair once both said yes. What's said is kept only until it closes, then deleted on the server, and the app keeps none
 * of it either: lines live in memory while the chat is on screen.
 */
@Serializable
data class PrivateChat(
    /** `ping-<ping id>` or `quickie-<match id>`. */
    val id: String,
    /** When it closes at the latest; ending the ping or the pairing closes it sooner. */
    @Serializable(with = InstantSerializer::class) val closesAt: Instant? = null,
    /** The newest lines, oldest first. */
    val messages: List<PrivateChatMessage> = emptyList(),
) {
    val isPing: Boolean get() = id.startsWith("ping-")
}

/** A line in a [PrivateChat]. Its id also names its chat, so it's reported on its own ([ReportTarget.PRIVATE_MESSAGE]). */
@Serializable
data class PrivateChatMessage(
    val id: String,
    val chatId: String,
    val body: String,
    val isMine: Boolean = false,
    /** Null when they've deleted their account. */
    val sender: Sender? = null,
    @Serializable(with = InstantSerializer::class) val sentAt: Instant? = null,
) {
    /** In full for friends, first name and last initial for anyone else. */
    @Serializable
    data class Sender(val id: String, val name: String = "")
}

/** The words around Ping and Quickie chats, the same as iOS's and the SDK's. */
object PrivateChatCopy {
    const val PLACEHOLDER = "Say something"
    const val PING_TITLE = "Chat with your circle"
    const val QUICKIE_TITLE = "Chat to find each other"
    const val EPHEMERAL = "Messages disappear when the chat ends."
    const val CLOSED = "This chat has ended."
    const val PING_WAITING = "The chat opens once someone's in."
    const val BODY_LIMIT = 1_000
}

/** A chat's lines as the screen shows them: in order, each once, whichever way it arrived (history, send, cable). */
object PrivateChatLines {
    fun merge(current: List<PrivateChatMessage>, incoming: List<PrivateChatMessage>): List<PrivateChatMessage> {
        if (incoming.isEmpty()) return current
        val byId = LinkedHashMap<String, PrivateChatMessage>()
        (current + incoming).forEach { byId[it.id] = it }
        return byId.values.sortedWith(compareBy(nullsLast()) { it.sentAt })
    }
}

/**
 * `privateChat` on the [CableConnection]: every Ping and Quickie chat the signed-in person is in, one operation for all
 * of them. Nothing arrives on subscribing; each result after is a line, or a chat closing.
 */
object PrivateChatProtocol {
    const val DOCUMENT: String = Documents.PRIVATE_CHAT_SUBSCRIPTION

    sealed interface Frame {
        data object Subscribed : Frame
        data class Message(val message: PrivateChatMessage) : Frame
        data class Closed(val chatId: String) : Frame
        data object Rejected : Frame
        data object Disconnected : Frame
        data object Ended : Frame
    }

    fun frames(envelope: GraphQLCable.Envelope?): List<Frame> = when (envelope) {
        null -> emptyList()
        GraphQLCable.Envelope.Subscribed -> listOf(Frame.Subscribed)
        GraphQLCable.Envelope.Rejected -> listOf(Frame.Rejected)
        GraphQLCable.Envelope.Disconnected -> listOf(Frame.Disconnected)
        is GraphQLCable.Envelope.Result -> {
            val frame = envelope.event?.let(::event)
            when {
                frame != null -> listOf(frame)
                envelope.data == null && envelope.errors.isNotEmpty() -> return listOf(Frame.Rejected)
                else -> emptyList()
            } + if (envelope.more) emptyList() else listOf(Frame.Ended)
        }
    }

    /** One `PrivateChatEvent`: its line, or that it closed; null when it's neither. */
    fun event(event: JsonObject): Frame? {
        val chatId = (event["chatId"] as? JsonPrimitive)?.content ?: return null
        val closed = (event["closed"] as? JsonPrimitive)?.booleanOrNull == true
        val message = (event["message"] as? JsonObject)?.let {
            runCatching { GraphQLCable.json.decodeFromJsonElement<PrivateChatMessage>(it) }.getOrNull()
        }
        return when {
            closed -> Frame.Closed(chatId)
            message != null -> Frame.Message(message)
            else -> null
        }
    }
}

/** A live `privateChat` subscription on the app's [CableConnection], while collected. */
class PrivateChatConnection(
    private val audienceId: String,
    private val cable: CableConnection,
) {
    sealed interface Event {
        /** Running; sent again after every reconnect, so refetch the open chat then (lines may have been missed). */
        data object Connected : Event
        data class Received(val message: PrivateChatMessage) : Event
        data class Closed(val chatId: String) : Event
        data object Rejected : Event
        data object Ended : Event
        data class Disconnected(val reason: String?, val reconnects: Boolean) : Event
    }

    fun events(): Flow<Event> = cable.subscribe(audienceId, PrivateChatProtocol.DOCUMENT, operationName = "PrivateChat")
        .transform { event ->
            when (event) {
                CableEvent.Subscribed -> emit(Event.Connected)
                CableEvent.Rejected -> emit(Event.Rejected)
                is CableEvent.Disconnected -> emit(Event.Disconnected(event.reason, event.reconnects))
                is CableEvent.Result -> for (frame in PrivateChatProtocol.frames(event.result)) {
                    when (frame) {
                        PrivateChatProtocol.Frame.Subscribed -> emit(Event.Connected)
                        is PrivateChatProtocol.Frame.Message -> emit(Event.Received(frame.message))
                        is PrivateChatProtocol.Frame.Closed -> emit(Event.Closed(frame.chatId))
                        PrivateChatProtocol.Frame.Rejected -> emit(Event.Rejected)
                        PrivateChatProtocol.Frame.Disconnected -> emit(Event.Disconnected(null, reconnects = true))
                        PrivateChatProtocol.Frame.Ended -> emit(Event.Ended)
                    }
                }
            }
        }
}
