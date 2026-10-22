package social.hotmess.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * One line in a venue's or locale's chat room, as its GraphQL `ChatMessage` describes it. Only [body]
 * and [userId] are needed; everything else is optional and defaults to a plain text line.
 *
 * [body] is the line in words, which for a rich message is its summary ("Shared an event: Sunset
 * Social"); [caption] is what the sender wrote: an announcement's or special's body, or an event's or
 * photo's caption.
 */
data class VenueMessage(
    val id: String = UUID.randomUUID().toString(),
    val body: String,
    val userId: String,
    /** The sender's photo, or the venue's for a post as the venue. */
    val avatarUrl: String? = null,
    /** The name the room shows: "First L." for anyone but the viewer's friends, or the venue's name. */
    val name: String? = null,
    val sentAt: Instant? = null,
    /** Who the sender is in the room, worked out by the server; null for everyone else. */
    val role: ChatRole? = null,
    val kind: ChatKind = ChatKind.TEXT,
    /** An announcement's or special's headline. */
    val title: String? = null,
    val caption: String? = null,
    /** A photo message's photo, or an announcement's. */
    val photoUrl: String? = null,
    /** The event an event message shares. */
    val event: SharedEvent? = null,
    /** When a special stops showing. */
    val endsAt: Instant? = null,
    /** The announcement pinned under the room's title. */
    val pinned: Boolean = false,
    /**
     * The sender isn't in the room but a notification reaches them (a yellow dot). The server only
     * says so about the viewer's friends, and to admins.
     */
    val reachable: Boolean = false,
    /** Posted by talent as their listing (role HOST): [name] and [avatarUrl] are this person's. */
    val personId: String? = null,
    /** A [ChatKind.PROMPT] message's prompt: what it asks, and its place in the night's prompts. */
    val prompt: ChatPrompt? = null,
    /** The prompt a text message answers (a PROMPT message's [ChatPrompt.id]); its bubble gets a Prompt label. */
    val promptId: String? = null,
    /**
     * Posted as the room's audience, like a prompt: [name] is the audience's ("Hot Mess"), with its mark,
     * no avatar, no presence and no role.
     */
    val postedAsAudience: Boolean = false,
) {
    fun isOutgoing(currentUserId: String?): Boolean = currentUserId != null && userId.equals(currentUserId, ignoreCase = true)

    /** Posted as the venue: [name] and [avatarUrl] are the venue's, never the sender's. */
    val isPostedAsVenue: Boolean get() = role == ChatRole.VENUE

    /** Posted as the audience (a prompt): an older server that doesn't say so still sends prompts as PROMPT. */
    val isPostedAsAudience: Boolean get() = postedAsAudience || kind == ChatKind.PROMPT

    /**
     * A listing, not the human who typed it: the venue, talent posting as themselves from Backstage, or the
     * audience's prompts. It has no presence, never takes a friend's name, and groups apart from the sender's
     * own lines.
     */
    val isFromListing: Boolean get() = isPostedAsVenue || personId != null || isPostedAsAudience

    /** Whether the room still shows it: a special stops at its end time. */
    fun isShowing(at: Instant = Instant.now()): Boolean = !(kind == ChatKind.SPECIAL && endsAt != null && !endsAt.isAfter(at))
}

/** Who someone is in a chat room, set by the server. Shown as a tag after their name. */
enum class ChatRole(val label: String) {
    /** Posting as the venue, with its name and photo. */
    VENUE("Venue"),

    /** A host or performer on tonight's event at the venue. */
    HOST("Host"),

    /** One of the audience's admins. */
    STAFF("Staff"),
    ;

    companion object {
        /** Reads the socket's `venue` and GraphQL's `VENUE` alike; anything else is no role. */
        fun parse(raw: String?): ChatRole? = raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
    }
}

/** What a chat message is. Only people with a role send anything but [TEXT]. */
enum class ChatKind {
    TEXT,
    ANNOUNCEMENT,
    EVENT,
    PHOTO,
    SPECIAL,

    /** One of the event's chat prompts, posted as the audience: the PromptBar (PINNED) or a PromptCard (CARD). */
    PROMPT,
    ;

    val isRich: Boolean get() = this != TEXT

    companion object {
        /** Any case; an unknown kind reads as [TEXT], so it shows as its summary. */
        fun parse(raw: String?): ChatKind = raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } } ?: TEXT
    }
}

/** The event an event message shares, as much as its frame carries. */
data class SharedEvent(val id: String, val name: String? = null, val startAt: Instant? = null)

/** What a chat prompt asks: something to talk about, or a trivia question with a hidden [ChatPrompt.answer]. */
enum class ChatPromptKind {
    CONVERSATION,
    TRIVIA,
    ;

    companion object {
        /** Any case; an unknown kind reads as [CONVERSATION], which shows its text and no answer. */
        fun parse(raw: String?): ChatPromptKind = raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } } ?: CONVERSATION
    }
}

/** How a prompt went up: [PINNED] under the room's title (the rotation), or as a [CARD] in a quiet room. */
enum class ChatPromptShownAs {
    PINNED,
    CARD,
    ;

    companion object {
        /** Any case; an unknown one reads as [CARD], which shows in the thread where it went up. */
        fun parse(raw: String?): ChatPromptShownAs = raw?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } } ?: CARD
    }
}

/**
 * The prompt a PROMPT message puts to the room (GraphQL's `ChatPromptFrame`): [number] of the night's
 * [total], what it asks, and for trivia the [answer], hidden until the reader taps or [revealAt].
 */
data class ChatPrompt(
    val id: String,
    val text: String,
    val kind: ChatPromptKind = ChatPromptKind.CONVERSATION,
    val answer: String? = null,
    val revealAt: Instant? = null,
    val shownAs: ChatPromptShownAs = ChatPromptShownAs.CARD,
    val number: Int? = null,
    val total: Int? = null,
    /** When the next one rotates in, if one will. */
    val nextAt: Instant? = null,
) {
    val isTrivia: Boolean get() = kind == ChatPromptKind.TRIVIA

    /** Whether the answer shows for everyone: a trivia answer at [revealAt]. */
    fun isRevealed(at: Instant = Instant.now()): Boolean = answer != null && revealAt != null && !revealAt.isAfter(at)

    /** "2 of 6", or null when the frame doesn't say. */
    val position: String?
        get() {
            val number = number?.takeIf { it > 0 } ?: return null
            val total = total?.takeIf { it >= number } ?: return null
            return "$number of $total"
        }

    /** The PromptBar's (or TriviaCard's) overline: "Tonight's prompt · 2 of 6", "Trivia · 3 of 6". */
    val overline: String
        get() {
            val label = if (isTrivia) "Trivia" else "Tonight's prompt"
            return position?.let { "$label · $it" } ?: label
        }

    /** The label on an answer's bubble: "Prompt", or "Trivia". */
    val answerLabel: String get() = if (isTrivia) "Trivia" else "Prompt"
}

/** The room's prompts, worked out from its lines. */
object ChatPrompts {
    /** The newest PINNED prompt in [messages] (oldest first): what the PromptBar shows, or null. */
    fun pinned(messages: List<VenueMessage>): VenueMessage? =
        messages.lastOrNull { it.kind == ChatKind.PROMPT && it.prompt?.shownAs == ChatPromptShownAs.PINNED }

    /** How many lines in [messages] answer the prompt [promptId]. */
    fun answerCount(messages: List<VenueMessage>, promptId: String): Int = messages.count { it.promptId == promptId }

    /** "14 answers", "1 answer", or null for none. */
    fun answers(count: Int): String? = when {
        count <= 0 -> null
        count == 1 -> "1 answer"
        else -> "$count answers"
    }

    /** The PromptBar's footer: "14 answers · Next one at 9:15pm", either half, or null for neither. */
    fun footer(prompt: ChatPrompt, answerCount: Int, at: Instant = Instant.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
        val next = prompt.nextAt?.takeIf { it.isAfter(at) }?.let { "Next one at ${Formatting.clock(it, zone)}" }
        return listOfNotNull(answers(answerCount), next).joinToString(" · ").ifEmpty { null }
    }

    /** What a line answering [promptId] is labelled: "Prompt" or "Trivia" ("Prompt" when the prompt isn't in [messages]); null for none. */
    fun label(promptId: String?, messages: List<VenueMessage>): String? {
        promptId ?: return null
        val prompt = messages.firstOrNull { it.prompt?.id == promptId }?.prompt
        return prompt?.answerLabel ?: "Prompt"
    }
}

/**
 * A chat room: a venue's, for people at the venue, or a locale's, for people out in the locale who
 * aren't at a venue. Both deliver the same events, so one connection and one screen serve either.
 *
 * [field] is the room's GraphQL subscription field and [argument] the id it takes, which is also how
 * `sendMyChatMessage` names the room.
 */
data class ChatRoom(val kind: Kind, val id: String) {
    enum class Kind(val field: String, val argument: String) {
        VENUE("venueChat", "venueId"),
        LOCALE("localeChat", "localeId"),
    }

    companion object {
        fun venue(id: String) = ChatRoom(Kind.VENUE, id)
        fun locale(id: String) = ChatRoom(Kind.LOCALE, id)
    }
}

/**
 * A chat room (see [ChatRoom]) over GraphQL subscriptions on Action Cable: the `GraphQLChannel`
 * subscription, the `venueChat`/`localeChat` operation run on it, and the `ChatRoomEvent`s it delivers.
 * Kept apart from the socket so it can be tested.
 *
 * Each operation is its own channel subscription, named by the audience and an id of the client's
 * choosing. Once Action Cable confirms it, the client performs `execute` with the GraphQL document;
 * every result then arrives as `{"result":{"data":…,"errors":…},"more":<bool>}`, the first being
 * `ChatJoined` (who is here, the recent lines, the pinned announcement and whether an admin is out of
 * range). `more: false` ends the operation. An operation the server refuses (not at the venue, banned)
 * comes back with errors and no data.
 *
 * Someone whose last connection leaves stays listed, offline, for a while: their ChatPresenceChanged
 * carries `recentUntil`, and ChatJoined's roster adds `recent`, who left lately (the latest first), people
 * who aren't in `people`. `lastSeenAt` can be null (a host who posted without joining), and `recentUntil`
 * hours away (until their event ends); without `recentUntil`, `lastSeenAt` plus [RECENT] stands in.
 *
 * Saying something is the `sendMyChatMessage` mutation (see [SEND_MESSAGE]); the room delivers the line
 * to everyone following it, the sender included.
 */
object ChatProtocol {
    /** How long a room lists someone after they leave, when the event doesn't say (the server's ChatVisit::RECENT). */
    val RECENT: java.time.Duration = java.time.Duration.ofMinutes(30)

    // Lenient, so an id sent as a number still reads as a string rather than dropping the line.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** A chat line's fields, as [message] reads them. */
    const val MESSAGE_FIELDS: String =
        "id message body name avatarUrl sentAt userId role kind title photoUrl event { id name startAt } endsAt pinned " +
            "postedAsVenue postedAsPerson person { id } postedAsAudience promptId " +
            "prompt { id kind text answer revealAt shownAs number total nextAt }"

    /** The ChatRoomEvent selection every room's subscription reads, by `__typename`. */
    const val ROOM_EVENT_FIELDS: String = """
        __typename
        ... on ChatJoined {
          outOfRange
          roster {
            people { userId name avatarUrl friend }
            friends { userId name }
            recent { userId name avatarUrl friend presence lastSeenAt recentUntil }
          }
          history { senderPresence message { $MESSAGE_FIELDS } }
          pinnedMessage { senderPresence message { $MESSAGE_FIELDS } }
          expiresBefore
        }
        ... on ChatMessagePosted { senderPresence message { $MESSAGE_FIELDS } }
        ... on ChatMessageUpdated { message { $MESSAGE_FIELDS } }
        ... on ChatMessageRemoved { messageId }
        ... on ChatPinChanged { messageId pinned announcement { $MESSAGE_FIELDS } }
        ... on ChatRoomCleared { clearedAt expired }
        ... on ChatPresenceChanged { userId presence name avatarUrl recentUntil }
        ... on ChatRangeChanged { outOfRange }
        ... on ChatLeft { reason }
    """

    /** A venue's room, `venueChat(venueId: $id)`. One constant per room kind, so the schema check can read each. */
    const val VENUE_ROOM_DOCUMENT: String =
        "subscription ChatRoom(\$id: ID!) { venueChat(venueId: \$id) { $ROOM_EVENT_FIELDS } }"

    /** A locale's room, `localeChat(localeId: $id)`. */
    const val LOCALE_ROOM_DOCUMENT: String =
        "subscription ChatRoom(\$id: ID!) { localeChat(localeId: \$id) { $ROOM_EVENT_FIELDS } }"

    /** The room's subscription document, for `$id`. */
    fun document(room: ChatRoom): String = when (room.kind) {
        ChatRoom.Kind.VENUE -> VENUE_ROOM_DOCUMENT
        ChatRoom.Kind.LOCALE -> LOCALE_ROOM_DOCUMENT
    }

    /**
     * Says something in a room as yourself; `$input` takes `venueId` or `localeId`, `message`, and `promptId`
     * for an answer to a prompt.
     */
    const val SEND_MESSAGE: String =
        "mutation SendMyChatMessage(\$input: ChatMessageSendOwnInput!) { sendMyChatMessage(input: \$input) { message { $MESSAGE_FIELDS } } }"

    /**
     * The mutation's input for saying [body] in [room], answering the prompt [promptId] when given. Only a
     * venue's room has prompts, so a locale's never sends one.
     */
    fun sendInput(room: ChatRoom, body: String, promptId: String? = null): JsonObject = JsonObject(
        buildMap {
            put(room.kind.argument, JsonPrimitive(room.id.lowercase()))
            put("message", JsonPrimitive(body))
            promptId?.takeIf { it.isNotBlank() && room.kind == ChatRoom.Kind.VENUE }?.let { put("promptId", JsonPrimitive(it)) }
        },
    )

    sealed interface Frame {
        /** Action Cable confirmed the channel subscription: time to run the operation. */
        data object Subscribed : Frame

        /** In the room: the first event (ChatJoined) arrived. */
        data object Connected : Frame
        data class Message(val message: VenueMessage) : Frame
        data object Disconnected : Frame

        /**
         * The server won't let this person in: the channel was rejected (another audience, banned) or the
         * operation came back with errors and no data (not at the room's place lately).
         */
        data object Rejected : Frame

        /** They were in the room, but they've left its place, or were banned or removed. */
        data object Left : Frame

        /** The server ended the operation (`more: false`); reconnecting starts it over. */
        data object Ended : Frame

        /** Whether they're outside the room's place. Only admins get in from outside. */
        data class Range(val outOfRange: Boolean) : Frame

        /**
         * Everyone in the room now, by lowercase user id, with who they are, and all the joiner's friends;
         * and [recent], who left lately, each with their [RoomPerson.recentUntil].
         */
        data class Roster(
            val online: Set<String>,
            val people: List<RoomPerson> = emptyList(),
            val friends: List<Friend> = emptyList(),
            val recent: List<RoomPerson> = emptyList(),
        ) : Frame

        /**
         * Someone (by lowercase user id) came into the room or left it; a newcomer with their [name] and
         * [avatarUrl]. Someone who left but whom a notification reaches is [reachable], and the room lists
         * someone who left until [recentUntil] when sent.
         */
        data class PresenceChanged(
            val userId: String,
            val online: Boolean,
            val name: String? = null,
            val avatarUrl: String? = null,
            val reachable: Boolean = false,
            val recentUntil: Instant? = null,
        ) : Frame

        /**
         * The room's recent lines, oldest first, and its pinned announcement, sent on joining, with when the room
         * stops showing lines to everyone but its admins ([expiresBefore]; null from an API that doesn't say).
         */
        data class History(val messages: List<VenueMessage>, val pinned: VenueMessage? = null, val expiresBefore: Instant? = null) : Frame

        /** An announcement (by [id]) was pinned, with the [announcement], or unpinned (null). */
        data class Pin(val id: String, val announcement: VenueMessage?) : Frame

        /** An admin removed a line (by [id]); nobody sees it again. */
        data class Removed(val id: String) : Frame

        /**
         * An admin cleared the room, or ([expired]) its oldest lines expired: every line sent up to [at] is gone (all
         * of them when [at] is unreadable).
         */
        data class Cleared(val at: Instant? = null, val expired: Boolean = false) : Frame
    }

    /** The channel's identifier (see [GraphQLCable.identifier]). */
    fun identifier(audienceId: String, channelId: String): String = GraphQLCable.identifier(audienceId, channelId)

    /** A fresh id for [identifier]. */
    fun newChannelId(): String = GraphQLCable.newChannelId()

    fun subscribe(identifier: String): String = GraphQLCable.subscribe(identifier)

    /** Runs the room's subscription on the channel [identifier] names. */
    fun execute(identifier: String, room: ChatRoom): String =
        GraphQLCable.execute(identifier, document(room), JsonObject(mapOf("id" to JsonPrimitive(room.id.lowercase()))))

    /**
     * Reads one frame from the server: what it says, in order, or nothing for pings and anything
     * unrecognized. ChatJoined is several things at once, and a result with `more: false` also ends.
     */
    fun parse(text: String): List<Frame> = frames(GraphQLCable.parse(text))

    /** What one [GraphQLCable.Envelope] says about the room. */
    fun frames(envelope: GraphQLCable.Envelope?): List<Frame> = when (envelope) {
        null -> emptyList()
        GraphQLCable.Envelope.Subscribed -> listOf(Frame.Subscribed)
        GraphQLCable.Envelope.Disconnected -> listOf(Frame.Disconnected)
        GraphQLCable.Envelope.Rejected -> listOf(Frame.Rejected)
        is GraphQLCable.Envelope.Result -> result(envelope)
    }

    /** `{"result":{"data":…,"errors":…},"more":…}`. */
    private fun result(result: GraphQLCable.Envelope.Result): List<Frame> {
        val event = result.event
        val frames = when {
            event != null -> event(event)
            // Refused: not in the room, banned, or the room isn't there.
            result.errors.isNotEmpty() -> return listOf(Frame.Rejected)
            else -> emptyList()
        }
        return if (result.more) frames else frames + Frame.Ended
    }

    /** One ChatRoomEvent, by its `__typename`. An unknown event is skipped. */
    private fun event(event: JsonObject): List<Frame> = when (event.string("__typename")) {
        "ChatJoined" -> joined(event)
        "ChatMessagePosted" -> listOfNotNull(posted(event)?.let(Frame::Message))
        "ChatMessageUpdated" -> listOfNotNull(message(event["message"])?.let(Frame::Message))
        "ChatMessageRemoved" -> listOfNotNull(event.string("messageId")?.let(Frame::Removed))
        "ChatPinChanged" -> listOfNotNull(pin(event))
        "ChatRoomCleared" -> listOf(Frame.Cleared(event.instant("clearedAt"), event.boolean("expired")))
        "ChatPresenceChanged" -> listOfNotNull(presence(event))
        "ChatRangeChanged" -> listOf(Frame.Range(event.boolean("outOfRange")))
        "ChatLeft" -> listOf(Frame.Left)
        else -> emptyList()
    }

    private fun joined(event: JsonObject): List<Frame> {
        val roster = event["roster"] as? JsonObject
        // Anything malformed in `people` or `friends` is skipped, never the whole roster.
        val people = (roster?.get("people") as? JsonArray).orEmpty().mapNotNull { entry ->
            val person = entry as? JsonObject ?: return@mapNotNull null
            val id = person.string("userId") ?: return@mapNotNull null
            RoomPerson(
                userId = id.lowercase(),
                name = person.string("name"),
                avatarUrl = person.string("avatarUrl"),
                friend = person.boolean("friend"),
            )
        }
        val friends = (roster?.get("friends") as? JsonArray).orEmpty().mapNotNull { entry ->
            val friend = entry as? JsonObject ?: return@mapNotNull null
            val id = friend.string("userId") ?: return@mapNotNull null
            Friend(id.lowercase(), friend.string("name").orEmpty())
        }
        // Who left lately; someone with no time to be listed until is skipped.
        val recent = (roster?.get("recent") as? JsonArray).orEmpty().mapNotNull { entry ->
            val person = entry as? JsonObject ?: return@mapNotNull null
            val id = person.string("userId") ?: return@mapNotNull null
            val until = person.instant("recentUntil") ?: person.instant("lastSeenAt")?.plus(RECENT) ?: return@mapNotNull null
            RoomPerson(
                userId = id.lowercase(),
                name = person.string("name"),
                avatarUrl = person.string("avatarUrl"),
                friend = person.boolean("friend"),
                recentUntil = until,
                reachable = person.string("presence").equals("PUSH", ignoreCase = true),
            )
        }
        // A malformed line drops only itself.
        val history = (event["history"] as? JsonArray).orEmpty().mapNotNull { posted(it as? JsonObject) }
        return listOf(
            Frame.Connected,
            Frame.Range(event.boolean("outOfRange")),
            Frame.Roster(people.map { it.userId }.toSet(), people, friends, recent),
            Frame.History(history, posted((event["pinnedMessage"] ?: event["pinned"]) as? JsonObject), event.instant("expiresBefore")),
        )
    }

    /** A ChatMessagePosted: its line, reachable when the sender isn't here but a notification reaches them. */
    private fun posted(event: JsonObject?): VenueMessage? {
        val line = message(event?.get("message")) ?: return null
        return line.copy(reachable = event?.string("senderPresence").equals("PUSH", ignoreCase = true))
    }

    private fun pin(event: JsonObject): Frame? {
        val id = event.string("messageId") ?: return null
        return Frame.Pin(id, if (event.boolean("pinned")) message(event["announcement"]) else null)
    }

    private fun presence(event: JsonObject): Frame? {
        val userId = event.string("userId") ?: return null
        val presence = event.string("presence") ?: return null
        val online = presence.equals("ONLINE", ignoreCase = true)
        return Frame.PresenceChanged(
            userId.lowercase(),
            online,
            name = if (online) event.string("name") else null,
            avatarUrl = if (online) event.string("avatarUrl") else null,
            reachable = presence.equals("PUSH", ignoreCase = true),
            recentUntil = if (online) null else event.instant("recentUntil"),
        )
    }

    /** A ChatMessage, as [MESSAGE_FIELDS] asks for it, or null when it's malformed. */
    fun message(element: JsonElement?): VenueMessage? {
        val message = element as? JsonObject ?: return null
        return runCatching { json.decodeFromJsonElement(IncomingMessage.serializer(), message) }.getOrNull()?.toMessage()
    }

    /** A string field, or null when it's missing, not a string-like value, or blank. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull.blankToNull()

    /** A timestamp field, or null when it's missing or unreadable. */
    private fun JsonObject.instant(key: String): Instant? = string(key)?.let(InstantSerializer::parse)

    private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

    /** The websocket endpoint, derived from the HTTP base URL: `wss://<host>/connection`. */
    fun realtimeUrl(baseUrl: String): String? = GraphQLCable.realtimeUrl(baseUrl)

    /** A ChatMessage. `message` (the line in words) and `userId` are required; the rest default to a plain line. */
    @Serializable
    private data class IncomingMessage(
        val message: String,
        val userId: String,
        val avatarUrl: String? = null,
        val id: String? = null,
        val name: String? = null,
        val sentAt: String? = null,
        val role: String? = null,
        val kind: String? = null,
        val title: String? = null,
        val body: String? = null,
        val photoUrl: String? = null,
        val event: IncomingEvent? = null,
        val endsAt: String? = null,
        val pinned: Boolean? = null,
        val postedAsPerson: Boolean? = null,
        val person: IncomingPerson? = null,
        val postedAsAudience: Boolean? = null,
        val promptId: String? = null,
        // Read on its own, so a malformed prompt drops only the prompt, never the line.
        val prompt: JsonElement? = null,
    ) {
        fun toMessage(): VenueMessage {
            val base = VenueMessage(body = message, userId = userId, avatarUrl = avatarUrl.blankToNull())
            return base.copy(
                id = id.blankToNull() ?: base.id,
                name = name.blankToNull(),
                sentAt = sentAt?.let(InstantSerializer::parse),
                role = ChatRole.parse(role),
                kind = ChatKind.parse(kind),
                title = title.blankToNull(),
                caption = body.blankToNull(),
                photoUrl = photoUrl.blankToNull(),
                event = event?.id.blankToNull()?.let { SharedEvent(it, event?.name.blankToNull(), event?.startAt?.let(InstantSerializer::parse)) },
                endsAt = endsAt?.let(InstantSerializer::parse),
                pinned = pinned ?: false,
                personId = if (postedAsPerson == true) person?.id.blankToNull()?.lowercase() else null,
                prompt = promptFrame(prompt),
                promptId = promptId.blankToNull(),
                postedAsAudience = postedAsAudience ?: false,
            )
        }
    }

    /** A ChatPromptFrame; it needs its `id` and `text`, and anything else unreadable is left out. */
    private fun promptFrame(element: JsonElement?): ChatPrompt? {
        val frame = element as? JsonObject ?: return null
        val id = frame.string("id") ?: return null
        val text = frame.string("text") ?: return null
        return ChatPrompt(
            id = id,
            text = text,
            kind = ChatPromptKind.parse(frame.string("kind")),
            answer = frame.string("answer"),
            revealAt = frame.instant("revealAt"),
            shownAs = ChatPromptShownAs.parse(frame.string("shownAs")),
            number = frame.int("number"),
            total = frame.int("total"),
            nextAt = frame.instant("nextAt"),
        )
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    @Serializable
    private data class IncomingEvent(val id: String? = null, val name: String? = null, val startAt: String? = null)

    @Serializable
    private data class IncomingPerson(val id: String? = null)

    private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }
}

/**
 * A chat room, a venue's or a locale's, in [audienceId], followed on the app's [CableConnection]: one
 * `GraphQLChannel` subscription running the room's subscription. Saying something is
 * [HotMessApi.sendChatMessage].
 */
class VenueChatConnection(
    private val room: ChatRoom,
    private val audienceId: String,
    private val cable: CableConnection,
) {
    sealed interface Event {
        data object Connected : Event
        data class Received(val message: VenueMessage) : Event

        /**
         * The socket dropped. With [reconnects] the cable is reconnecting, and the room is joined again
         * (a [Connected] and fresh roster and history follow) on the same flow; without, the flow ends.
         */
        data class Disconnected(val reason: String?, val reconnects: Boolean) : Event
        data object Rejected : Event
        data object Left : Event

        /**
         * The server ended the room's operation (`more: false`) with no ChatLeft or refusal; the flow ends
         * after it, and subscribing again starts the room over.
         */
        data object Ended : Event
        data class Range(val outOfRange: Boolean) : Event

        /** Everyone in the room now, by lowercase user id, and who they are, the viewer's friends and who left lately. */
        data class Roster(
            val online: Set<String>,
            val people: List<RoomPerson> = emptyList(),
            val friends: List<Friend> = emptyList(),
            val recent: List<RoomPerson> = emptyList(),
        ) : Event

        data class PresenceChanged(
            val userId: String,
            val online: Boolean,
            val name: String? = null,
            val avatarUrl: String? = null,
            val reachable: Boolean = false,
            val recentUntil: Instant? = null,
        ) : Event

        data class History(val messages: List<VenueMessage>, val pinned: VenueMessage?, val expiresBefore: Instant? = null) : Event
        data class Pin(val id: String, val announcement: VenueMessage?) : Event
        data class Removed(val id: String) : Event
        data class Cleared(val at: Instant?, val expired: Boolean) : Event
    }

    /**
     * Follows the room while collected, across reconnects; the flow ends when the room refuses this
     * person, the server ends the operation, or the connection is refused.
     */
    fun events(): Flow<Event> = cable.subscribe(
        audienceId,
        ChatProtocol.document(room),
        JsonObject(mapOf("id" to JsonPrimitive(room.id.lowercase()))),
    ).transform { event ->
        when (event) {
            // ChatJoined, after the operation runs, says the room is joined.
            CableEvent.Subscribed -> Unit
            CableEvent.Rejected -> emit(Event.Rejected)
            is CableEvent.Disconnected -> emit(Event.Disconnected(event.reason, event.reconnects))
            is CableEvent.Result -> for (frame in ChatProtocol.frames(event.result)) {
                when (frame) {
                    ChatProtocol.Frame.Subscribed -> Unit
                    ChatProtocol.Frame.Connected -> emit(Event.Connected)
                    ChatProtocol.Frame.Disconnected -> emit(Event.Disconnected(null, reconnects = true))
                    ChatProtocol.Frame.Rejected -> emit(Event.Rejected)
                    ChatProtocol.Frame.Left -> emit(Event.Left)
                    // The server ended the room's operation; the cable ends the flow.
                    ChatProtocol.Frame.Ended -> emit(Event.Ended)
                    is ChatProtocol.Frame.Message -> emit(Event.Received(frame.message))
                    is ChatProtocol.Frame.Range -> emit(Event.Range(frame.outOfRange))
                    is ChatProtocol.Frame.Roster -> emit(Event.Roster(frame.online, frame.people, frame.friends, frame.recent))
                    is ChatProtocol.Frame.PresenceChanged ->
                        emit(Event.PresenceChanged(frame.userId, frame.online, frame.name, frame.avatarUrl, frame.reachable, frame.recentUntil))
                    is ChatProtocol.Frame.History -> emit(Event.History(frame.messages, frame.pinned, frame.expiresBefore))
                    is ChatProtocol.Frame.Pin -> emit(Event.Pin(frame.id, frame.announcement))
                    is ChatProtocol.Frame.Removed -> emit(Event.Removed(frame.id))
                    is ChatProtocol.Frame.Cleared -> emit(Event.Cleared(frame.at, frame.expired))
                }
            }
        }
    }
}

/**
 * Where a chat transcript divides what has cleared from what is current: an admin clearing the room at [at], or
 * ([expired]) the line before which only the room's admins still see messages.
 */
data class ChatClearMark(val at: Instant, val expired: Boolean) {
    /** What the divider says. */
    fun label(zone: ZoneId = ZoneId.systemDefault()): String =
        if (expired) "Cleared for everyone but admins above" else "Chat cleared at ${Formatting.clock(at, zone)}"

    companion object {
        /** The mark a room's history gets: only admins are sent lines older than [expiresBefore], and they get it. */
        fun forHistory(history: List<VenueMessage>, expiresBefore: Instant?): ChatClearMark? =
            expiresBefore?.takeIf { cutoff -> history.any { line -> line.sentAt?.isBefore(cutoff) == true } }
                ?.let { ChatClearMark(it, expired = true) }

        /** What's left of [lines] once the room clears everything sent up to [at] (everything, without one). */
        fun remaining(lines: List<VenueMessage>, at: Instant?): List<VenueMessage> =
            if (at == null) emptyList() else lines.filterNot { line -> line.sentAt?.isAfter(at) == false }

        /**
         * The mark after the room clears up to [at]: an admin's clearing leaves one where it happened; lines expiring
         * keep [current] while lines it divides remain in [lines].
         */
        fun afterClear(current: ChatClearMark?, lines: List<VenueMessage>, at: Instant?, expired: Boolean, now: Instant = Instant.now()): ChatClearMark? {
            if (!expired) return ChatClearMark(at ?: now, expired = false)
            val mark = current ?: return null
            return mark.takeIf { lines.any { line -> line.sentAt?.isBefore(mark.at) == true } }
        }
    }
}
