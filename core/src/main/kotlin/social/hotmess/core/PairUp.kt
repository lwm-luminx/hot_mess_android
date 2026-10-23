package social.hotmess.core

import kotlinx.serialization.Serializable

/** Pair me up to chat at a venue (`venue.pairUp`): whether the user can opt in now, and their pairing tonight. */
@Serializable
data class VenuePairUp(
    /** The app places them at the venue, so they can opt in. */
    val here: Boolean = false,
    /** Their Pair me up here tonight; null until they opt in. */
    val mine: PairUpState? = null,
)

/** Where the user is in Pair me up at a venue tonight. */
@Serializable
data class PairUpState(
    /** SEARCHING, PROPOSED, WAITING, REVEALED, MET or LEFT; see [PairUpStatus]. */
    val status: String = PairUpStatus.SEARCHING,
    /** The hint their partner sees once both accept. */
    val lookFor: String? = null,
    /** How many conversations they had tonight (the same person twice counts twice): the night's badge. */
    val metCount: Int = 0,
    /** Their pairing. While searching, the last one when it ended. */
    val match: PairUpMatch? = null,
    /** A shared topic from a mistaken scan, while it lasts. */
    val bonus: PairUpMatch? = null,
) {
    val isActive: Boolean get() = status != PairUpStatus.LEFT
    val isPaired: Boolean get() = status in setOf(PairUpStatus.PROPOSED, PairUpStatus.WAITING, PairUpStatus.REVEALED)
}

object PairUpStatus {
    const val SEARCHING = "SEARCHING"
    const val PROPOSED = "PROPOSED"
    const val WAITING = "WAITING"
    const val REVEALED = "REVEALED"
    const val MET = "MET"
    const val LEFT = "LEFT"
}

/** A pairing as the user sees it. */
@Serializable
data class PairUpMatch(
    val id: String,
    val accepted: Boolean = false,
    val partnerAccepted: Boolean = false,
    /** Whether they agreed to share the meeting in the venue's chat. */
    val shared: Boolean = false,
    val partnerShared: Boolean = false,
    /** A fresh topic from scanning the wrong code: accepted, no code needed. */
    val bonus: Boolean = false,
    val metAt: String? = null,
    /** SKIPPED, LEFT or BLOCKED once it ended; null while it lasts. */
    val endReason: String? = null,
    val topic: PairUpTopic,
    /** Who to look for, once both accepted; null before and once either cancels. */
    val partner: PairUpPartner? = null,
) {
    /** Both agreed, so the venue's chat says who met and what they talked about. */
    val isShared: Boolean get() = shared && partnerShared
}

@Serializable
data class PairUpTopic(val key: String, val text: String)

/** Just enough to find someone in the room: never where they are. */
@Serializable
data class PairUpPartner(
    val id: String,
    val name: String,
    /** Tiny, served by the API only while the pairing lasts. */
    val avatarUrl: String? = null,
    val lookFor: String? = null,
)

/** What scanning a Pair me up code did: MET, DIFFERENT, USED, ENDED, OWN or UNREADABLE. */
@Serializable
data class PairUpScan(
    val outcome: String = "UNREADABLE",
    val match: PairUpMatch? = null,
    val pairUp: PairUpState? = null,
) {
    /** Never the scanner's fault: what to do next, or null for a meeting. */
    val notice: String?
        get() = when (outcome) {
            "MET" -> null
            "DIFFERENT" -> "That's someone else's code, but here's a topic for the two of you."
            "USED" -> "That code was refreshed. Ask them to show it again."
            "ENDED" -> "That pairing has ended."
            "OWN" -> "That's your own code. Scan theirs instead."
            else -> "That isn't a Quickie code. Try scanning again."
        }
}
