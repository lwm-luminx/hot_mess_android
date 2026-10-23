package social.hotmess.core

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * "I feel unsafe": an alert the user sent to the staff on shift at the venue they're at (`sendSafetyAlert`,
 * api doc/SAFETY_ALERTS.md). The user sees where it stands and its private thread; how staff closed it never
 * reaches the app.
 */
@Serializable
data class SafetyAlert(
    val id: String,
    val state: SafetyAlertState = SafetyAlertState.UNKNOWN,
    val place: SafetyPlace? = null,
    val concerns: List<SafetyConcern> = emptyList(),
    val description: String? = null,
    val summary: String = "",
    val seen: Boolean = false,
    val acknowledgedByName: String? = null,
    val acknowledgedAt: String? = null,
    val escalatedAt: String? = null,
    val closedAt: String? = null,
    val supportSharedAt: String? = null,
    val supportNote: String? = null,
    val createdAt: String,
    val venue: VenueRef,
    val messages: List<SafetyAlertMessage> = emptyList(),
) {
    @Serializable
    data class VenueRef(val id: String, val name: String? = null)

    /** Still waiting for staff, or picked up: what the waiting screen shows. */
    val isActive: Boolean get() = state == SafetyAlertState.OPEN || state == SafetyAlertState.ACKNOWLEDGED

    val sentAt: Instant? get() = runCatching { Instant.parse(createdAt) }.getOrNull()

    /** Nobody has picked it up and the escalation window has passed: the "Nobody's picked it up yet" screen. */
    fun isUnanswered(now: Instant): Boolean {
        if (state != SafetyAlertState.OPEN) return false
        if (escalatedAt != null) return true
        val sent = sentAt ?: return false
        return Duration.between(sent, now) >= ESCALATION_WINDOW
    }

    companion object {
        /** How long nobody may pick it up before the managers hear it too (`SafetyAlert::ESCALATE_AFTER`). */
        val ESCALATION_WINDOW: Duration = Duration.ofMinutes(2)
    }
}

@Serializable
data class SafetyAlertMessage(
    val id: String,
    val body: String,
    val fromStaff: Boolean = false,
    val senderName: String = "",
    val createdAt: String? = null,
)

/** `SafetyAlertState`; anything newer reads as [UNKNOWN] thanks to `coerceInputValues`. */
@Serializable
enum class SafetyAlertState { OPEN, ACKNOWLEDGED, CLOSED, CANCELLED, UNKNOWN }

/** Where in the venue the user is (`SafetyAlertPlace`). */
@Serializable
enum class SafetyPlace(val label: String) {
    BAR("Bar"),
    DANCE_FLOOR("Dance floor"),
    RESTROOMS("Restrooms"),
    PATIO("Patio"),
    STAGE("Near the stage"),
    ENTRANCE("Entrance"),
    UNSURE("Not sure"),
}

/** What's going on (`SafetyAlertConcern`). Any of them. */
@Serializable
enum class SafetyConcern(val label: String) {
    BOTHERING("Someone's bothering me"),
    FOLLOWING("Someone's following me"),
    INTOXICATED("Someone's had too much"),
    UNWELL("I don't feel well"),
    OTHER("Something else"),
}

/** What the user filled in on "Tell staff quietly". Everything is optional. */
data class SafetyAlertDraft(
    val place: SafetyPlace? = null,
    val concerns: Set<SafetyConcern> = emptySet(),
    val description: String = "",
) {
    val trimmedDescription: String? get() = description.trim().takeIf { it.isNotEmpty() }

    fun toggle(concern: SafetyConcern): SafetyAlertDraft =
        copy(concerns = if (concern in concerns) concerns - concern else concerns + concern)

    fun choose(place: SafetyPlace): SafetyAlertDraft = copy(place = if (this.place == place) null else place)
}

/** Whether "Get help" shows on a venue (the app places the user there) and whether anyone on shift hears alerts. */
@Serializable
data class VenueSafety(val canSendSafetyAlert: Boolean = false, val safetyStaffOnShift: Boolean = false)

/** "Need support?": the emergency number and the crisis and support lines for a region (`supportResources`). */
@Serializable
data class SupportResources(
    val region: String = "US",
    val name: String = "",
    val emergency: String = "911",
    /** When someone last checked every line ("2026-10-23"); null until they have, and no date shows. */
    val checkedAt: String? = null,
    val lines: List<SupportLine> = emptyList(),
) {
    val checkedDate: LocalDate? get() = checkedAt?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

@Serializable
data class SupportLine(
    val id: String,
    val name: String,
    val description: String? = null,
    /** Digits only. */
    val phone: String? = null,
    val textNumber: String? = null,
    /** What to text to start, when the line asks for something ("START"). */
    val textBody: String? = null,
    val url: String? = null,
) {
    /**
     * The numbers written out, so they can be read aloud or dialled from another phone:
     * "Call 1-866-488-7386 · Text START to 678678".
     */
    val numbers: String
        get() = listOfNotNull(
            phone?.let { "Call ${formatNumber(it)}" },
            textNumber?.let { number -> textBody?.let { "Text $it to $number" } ?: "Text ${formatNumber(number)}" },
        ).joinToString(" · ")

    companion object {
        /** 18664887386 as 1-866-488-7386, 8775658860 as 877-565-8860; short codes as they are. */
        fun formatNumber(digits: String): String = when {
            digits.length == 10 -> "${digits.substring(0, 3)}-${digits.substring(3, 6)}-${digits.substring(6)}"
            digits.length == 11 && digits.startsWith("1") ->
                "1-${digits.substring(1, 4)}-${digits.substring(4, 7)}-${digits.substring(7)}"
            else -> digits
        }
    }
}

/** The venue "Get help" is about, with what "Message a friend" and "Get a ride" say. */
data class HelpVenue(val id: String, val name: String, val address: String? = null) {
    /** "I'm at The Wildrose, 1001 E Pike St." */
    val whereIAm: String get() = if (address.isNullOrBlank()) "I'm at $name." else "I'm at $name, $address."

    val pickup: String get() = if (address.isNullOrBlank()) name else "$name, $address"
}

internal object SafetyAlertDocuments {
    private const val ALERT = "id state place concerns description summary seen acknowledgedByName acknowledgedAt " +
        "escalatedAt closedAt supportSharedAt supportNote createdAt venue { id name } " +
        "messages { id body fromStaff senderName createdAt }"

    val VENUE_SAFETY: String = """
        query VenueSafety(${'$'}id: ID!) {
          venues { venue(id: ${'$'}id) { id canSendSafetyAlert safetyStaffOnShift } }
        }
    """.trimIndent()

    val SEND: String = """
        mutation SendSafetyAlert(${'$'}venueId: ID!, ${'$'}place: SafetyAlertPlace, ${'$'}concerns: [SafetyAlertConcern!], ${'$'}description: String) {
          sendSafetyAlert(input: { venueId: ${'$'}venueId, place: ${'$'}place, concerns: ${'$'}concerns, description: ${'$'}description }) {
            safetyAlert { $ALERT }
          }
        }
    """.trimIndent()

    val UPDATE: String = """
        mutation UpdateSafetyAlert(${'$'}id: ID!, ${'$'}place: SafetyAlertPlace, ${'$'}concerns: [SafetyAlertConcern!], ${'$'}description: String) {
          updateSafetyAlert(input: { id: ${'$'}id, place: ${'$'}place, concerns: ${'$'}concerns, description: ${'$'}description }) {
            safetyAlert { $ALERT }
          }
        }
    """.trimIndent()

    val REMIND: String = """
        mutation RemindSafetyAlert(${'$'}id: ID!) {
          remindSafetyAlert(input: { id: ${'$'}id }) { safetyAlert { $ALERT } }
        }
    """.trimIndent()

    val CANCEL: String = """
        mutation CancelSafetyAlert(${'$'}id: ID!) {
          cancelSafetyAlert(input: { id: ${'$'}id }) { safetyAlert { $ALERT } }
        }
    """.trimIndent()

    val MESSAGE: String = """
        mutation SendSafetyAlertMessage(${'$'}id: ID!, ${'$'}body: String!) {
          sendSafetyAlertMessage(input: { id: ${'$'}id, body: ${'$'}body }) { safetyAlert { $ALERT } }
        }
    """.trimIndent()

    val ONE: String = """
        query SafetyAlert(${'$'}id: ID!) {
          me { safetyAlert(id: ${'$'}id) { $ALERT } }
        }
    """.trimIndent()

    val MINE: String = """
        query MySafetyAlerts {
          me { safetyAlerts { $ALERT } }
        }
    """.trimIndent()

    val SUPPORT: String = """
        query SupportResources(${'$'}region: String) {
          supportResources(region: ${'$'}region) {
            region name emergency checkedAt
            lines { id name description phone textNumber textBody url }
          }
        }
    """.trimIndent()
}

@Serializable
internal data class VenueSafetyResponse(val venues: Venues? = null) {
    @Serializable
    data class Venues(val venue: VenueSafety? = null)
}

@Serializable
internal data class SafetyAlertPayload(val safetyAlert: SafetyAlert? = null)

@Serializable
internal data class OneSafetyAlertResponse(val me: Me? = null) {
    @Serializable
    data class Me(val safetyAlert: SafetyAlert? = null)
}

@Serializable
internal data class MySafetyAlertsResponse(val me: Me? = null) {
    @Serializable
    data class Me(val safetyAlerts: List<SafetyAlert> = emptyList())
}

@Serializable
internal data class SupportResourcesResponse(val supportResources: SupportResources)
