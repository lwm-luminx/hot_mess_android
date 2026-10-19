package social.hotmess.core

import kotlinx.serialization.Serializable

/** Someone the user blocked: chat rooms leave them out, and the API stops sending their messages, presence and Pings. */
@Serializable
data class BlockedUser(
    val id: String,
    /** The short form rooms show strangers ("Aurora B."). */
    val name: String = "",
    val avatarUrl: String? = null,
)

/** What the API keeps about the user's own safety settings. */
data class SafetyState(
    /** Whether they agreed to the terms of use; chat and Pings ask first. */
    val termsAccepted: Boolean = false,
    val blockedUsers: List<BlockedUser> = emptyList(),
) {
    fun isBlocked(userId: String?): Boolean = userId != null && blockedUsers.any { RecordId.same(it.id, userId) }
}

/** What a report is about: the API's `ReportTarget`, sent by name. */
enum class ReportTarget { MESSAGE, PERSON, VENUE, PING, PHOTO }

/** Why someone reports something. [label] is what the audience's admins read. */
enum class ReportReason(val label: String) {
    SPAM("Spam"),
    HARASSMENT("Harassment or hate"),
    SEXUAL("Sexual content"),
    DANGER("Threats or danger"),
    OTHER("Something else"),
}

/** Something being reported, and the person behind it when blocking them makes sense (a message's sender, a Ping's). */
data class ReportSubject(
    val target: ReportTarget,
    val targetId: String,
    /** Who to offer to block as well; null for a venue, a listing, or a post as the venue. */
    val sender: BlockedUser? = null,
) {
    /** The reason picker's question. */
    val question: String
        get() = when (target) {
            ReportTarget.MESSAGE -> "Why are you reporting this message?"
            ReportTarget.PERSON -> "Why are you reporting this person?"
            ReportTarget.VENUE -> "Why are you reporting this venue?"
            ReportTarget.PING -> "Why are you reporting this ping?"
            ReportTarget.PHOTO -> "Why are you reporting this photo?"
        }
}

@Serializable
data class ReportResult(val reported: Boolean = false, val blocked: Boolean = false)

/** The audience's legal pages, linked from Me, sign-in and the terms prompt. */
object LegalLinks {
    const val TERMS = "https://audiencekit.com/terms/"
    const val PRIVACY = "https://audiencekit.com/privacy/"
    const val DATA_DELETION = "https://audiencekit.com/data-deletion/"
}
