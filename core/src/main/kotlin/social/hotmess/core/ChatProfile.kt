package social.hotmess.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether tapping this line's sender opens their profile sheet: only a person posting as themselves, never
 * the viewer's own lines, the venue posting as itself, talent posting as their listing, or the audience's prompts.
 */
fun VenueMessage.opensProfile(viewerId: String?): Boolean = !isFromListing && !isOutgoing(viewerId) && userId.isNotBlank()

/** Where the viewer stands with someone in a room's profile sheet, for its "Add friend" button. */
enum class FriendState {
    /** Not friends and not asked: "Add friend". */
    NONE,

    /** Asked from this device, no answer yet: "Requested", disabled. */
    REQUESTED,

    /** Friends: "Friends", disabled. */
    FRIENDS,
    ;

    companion object {
        /** A request's status from `sendFriendRequest`: ACCEPTED is friends, anything else waits on them. */
        fun afterRequest(status: String): FriendState = if (status.equals("ACCEPTED", ignoreCase = true)) FRIENDS else REQUESTED
    }
}

/** Where an answer's Prompt label takes the reader. */
sealed interface PromptJump {
    /** The prompt is the room's current pinned one: expand the PromptBar (or TriviaCard) and highlight it. */
    data object Bar : PromptJump

    /** The prompt's line in the transcript (a quiet room's card, or an older pinned prompt): scroll to it if it's drawn. */
    data class Line(val messageId: String) : PromptJump

    companion object {
        /** Where the label of a line answering [promptId] goes, or null when its prompt isn't in [messages] (oldest first). */
        fun to(promptId: String?, messages: List<VenueMessage>): PromptJump? {
            promptId ?: return null
            val line = messages.lastOrNull { it.kind == ChatKind.PROMPT && it.prompt?.id == promptId } ?: return null
            if (ChatPrompts.pinned(messages)?.prompt?.id == promptId) return Bar
            return Line(line.id)
        }
    }
}

/** Sets of strings kept on the device by key; SharedPreferences on Android. */
interface StringSetStorage {
    fun get(key: String): Set<String>
    fun put(key: String, values: Set<String>)
}

/**
 * Who the viewer muted in each chat room, on this device only: their lines are hidden in that room and no
 * other. Nothing is sent to the API. Rooms are read from [storage] the first time they're asked about.
 */
class ChatMutes(private val storage: StringSetStorage) {
    private val _muted = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    /** Muted user ids (lowercase) by [key]; a room shows here once it's been read with [muted]. */
    val state: StateFlow<Map<String, Set<String>>> = _muted.asStateFlow()

    /** Who is muted in [room], by lowercase user id. */
    fun muted(room: ChatRoom): Set<String> {
        val key = key(room)
        _muted.value[key]?.let { return it }
        val stored = storage.get(key).map { it.lowercase() }.toSet()
        _muted.value = _muted.value + (key to stored)
        return stored
    }

    fun isMuted(room: ChatRoom, userId: String): Boolean = userId.lowercase() in muted(room)

    /** Mutes or unmutes [userId] in [room], and keeps it. */
    fun setMuted(room: ChatRoom, userId: String, muted: Boolean) {
        val id = userId.lowercase()
        val current = muted(room)
        val next = if (muted) current + id else current - id
        if (next == current) return
        val key = key(room)
        storage.put(key, next)
        _muted.value = _muted.value + (key to next)
    }

    companion object {
        /** The storage key for [room]'s mutes: its kind and lowercase id. */
        fun key(room: ChatRoom): String = "chat.muted.${room.kind.name.lowercase()}.${room.id.lowercase()}"

        /** [messages] without the people [muted] by lowercase user id; posts as a listing or the audience always stay. */
        fun visible(messages: List<VenueMessage>, muted: Set<String>): List<VenueMessage> =
            if (muted.isEmpty()) messages else messages.filterNot { !it.isFromListing && it.userId.lowercase() in muted }
    }
}
