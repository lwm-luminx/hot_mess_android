package social.hotmess.core

import java.time.Instant

/**
 * What the ongoing notifications say while something is open tonight: the Android counterpart of the iOS Live
 * Activities (hot_mess_ios `LiveActivities.swift`, #234 and #279). One for a Ping (the user's own, else one they're in,
 * else the newest one a friend sent them, to join from the notification shade) and one for their Quickie at a venue.
 * The app posts them whenever it loads that state and after each button; they never name a Quickie partner, since the
 * Lock Screen is for anyone to see.
 */
data class OngoingCard(
    val kind: Kind,
    /** The Ping's id, or the Quickie's venue id: one card per key. */
    val key: String,
    val title: String,
    val status: String,
    /** When a Ping ends (5am), for the countdown; null for a Quickie. */
    val endsAt: Instant?,
    /** The Quickie's topic, once there's one to say yes to. */
    val topic: String? = null,
    /** The pairing the buttons act on. */
    val matchId: String? = null,
    val actions: List<OngoingAction>,
    /** Where tapping the card goes (`https://hotmess.social/…`, as a push's `url`). */
    val url: String,
    /** Where Chat goes. */
    val chatUrl: String,
) {
    enum class Kind { PING, QUICKIE }
}

/** The buttons a card can carry. */
enum class OngoingAction(val label: String) {
    /** "I'm in" on a friend's Ping (`joinPing`). */
    JOIN_PING("I'm in"),

    /** "I'm in" on the Quickie's topic (`acceptPairUp`). */
    ACCEPT_QUICKIE("I'm in"),

    /** Another topic (`skipPairUp`, the topic and not the person). */
    NEW_TOPIC("New topic"),

    /** Opens the chat. */
    CHAT("Chat"),
}

object OngoingCards {
    private const val SITE = "https://hotmess.social"

    /**
     * Which Ping the card is about: the user's own, else one they're in, else the newest one a friend sent them
     * directly (not one they only see through someone in its circle). Null when none is open at [now].
     */
    fun shownPing(myPing: Ping?, friendPings: List<Ping>, now: Instant = Instant.now()): Ping? {
        fun open(ping: Ping) = ping.expiresAt?.isAfter(now) ?: true
        val friends = friendPings.filter(::open)
        return myPing?.takeIf(::open)
            ?: friends.firstOrNull { it.joined }
            ?: friends.filter { !it.isMine && it.via == null }.maxByOrNull { it.createdAt ?: Instant.EPOCH }
    }

    /** The card for [ping]: "Sam's Ping", "2 people in · Purr", with I'm in until the user is in, then Chat. */
    fun ping(ping: Ping): OngoingCard {
        val count = ping.joins.size
        val people = if (count == 1) "1 person in" else "$count people in"
        val place = ping.targets.firstOrNull()?.name.orEmpty()
        val canJoin = !ping.isMine && !ping.joined
        return OngoingCard(
            kind = OngoingCard.Kind.PING,
            key = ping.id,
            title = if (ping.isMine) "Your Ping" else "${ping.user.firstName}'s Ping",
            status = if (place.isEmpty()) people else "$people · $place",
            endsAt = ping.expiresAt,
            actions = if (canJoin) listOf(OngoingAction.JOIN_PING) else listOf(OngoingAction.CHAT),
            url = "$SITE/pings/${ping.id}",
            chatUrl = "$SITE/pings/${ping.id}/chat",
        )
    }

    /** [ping] for the shown Ping, or null when there's none. */
    fun ping(myPing: Ping?, friendPings: List<Ping>, now: Instant = Instant.now()): OngoingCard? =
        shownPing(myPing, friendPings, now)?.let(::ping)

    /**
     * The card for the user's Quickie at [venueId] while it's open (searching through revealed), or null once it's
     * over (met, left) or there's none.
     */
    fun quickie(state: PairUpState?, venueId: String, venueName: String?): OngoingCard? {
        state ?: return null
        val status = quickieStatus(state.status) ?: return null
        val match = state.match?.takeIf { state.isPaired }
        return OngoingCard(
            kind = OngoingCard.Kind.QUICKIE,
            key = venueId,
            title = venueName?.takeIf { it.isNotBlank() }?.let { "Quickie at $it" } ?: "Quickie",
            status = status,
            endsAt = null,
            topic = match?.topic?.text,
            matchId = match?.id,
            actions = quickieActions(state),
            url = "$SITE/venues/$venueId",
            chatUrl = "$SITE/venues/$venueId/quickie",
        )
    }

    /** What the Quickie card says at each step; null once it's over. */
    fun quickieStatus(status: String): String? = when (status) {
        PairUpStatus.SEARCHING -> "Looking for someone to pair you with"
        PairUpStatus.PROPOSED -> "We found someone. Up for this topic?"
        PairUpStatus.WAITING -> "Waiting for them to say yes"
        PairUpStatus.REVEALED -> "You both said yes. Open to see who to look for"
        else -> null
    }

    /**
     * The Quickie's buttons: say yes to the topic or ask for another while it's proposed, another while waiting on
     * them, and Chat once both said yes.
     */
    fun quickieActions(state: PairUpState): List<OngoingAction> = when (state.status) {
        PairUpStatus.PROPOSED ->
            if (state.match?.accepted == true) listOf(OngoingAction.NEW_TOPIC)
            else listOf(OngoingAction.ACCEPT_QUICKIE, OngoingAction.NEW_TOPIC)
        PairUpStatus.WAITING -> listOf(OngoingAction.NEW_TOPIC)
        PairUpStatus.REVEALED -> listOf(OngoingAction.CHAT)
        else -> emptyList()
    }
}
