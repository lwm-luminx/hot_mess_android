package social.hotmess.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Suggestions: members telling us about a missing venue or event, a detail that's wrong, or a venue that has closed
 * (`submitContribution`, the API's doc/CONTRIBUTIONS.md). Nothing changes a listing until a person approves it, and
 * the venue never learns who sent it. As on iOS (HotMessAPI+Suggestions.swift).
 */
@Serializable
enum class SuggestionKind(val title: String) {
    MISSING_VENUE("New venue"),
    MISSING_EVENT("New event"),
    CORRECTION("Fix"),
    CLOSED("Closed for good"),
    TAG_SUGGESTION("Tag"),
    LOCAL_RULES_CORRECTION("Local rules"),
    TALENT_LINK("Performer"),
}

/** Where a suggestion is (`SubmissionStatus`), as the member sees it: never whether the venue was asked. */
@Serializable
enum class SuggestionStatus { SUBMITTED, RESEARCHING, IN_REVIEW, NEEDS_VENUE, APPROVED, REJECTED, MERGED }

/** One of the member's own suggestions, for "Your suggestions". */
@Serializable
data class SuggestionSummary(
    val id: String,
    /** A kind a newer API adds reads as a fix. */
    val kind: SuggestionKind = SuggestionKind.CORRECTION,
    val status: SuggestionStatus = SuggestionStatus.SUBMITTED,
    val reporterCount: Int = 1,
    val proposed: JsonObject = JsonObject(emptyMap()),
    val createdAt: String,
    val venue: Listing? = null,
    val event: Listing? = null,
    val decisions: List<Decision> = emptyList(),
) {
    @Serializable
    data class Listing(val id: String, val name: String? = null)

    @Serializable
    data class Decision(val action: String, val reason: String? = null)

    val created: Instant? get() = runCatching { Instant.parse(createdAt) }.getOrNull()

    /** What it's about: the listing, or the name it proposed. */
    val title: String
        get() {
            val proposedName = (proposed["name"] as? JsonPrimitive)?.contentOrNull
            val name = when (kind) {
                SuggestionKind.MISSING_VENUE -> venue?.name ?: proposedName
                SuggestionKind.MISSING_EVENT -> event?.name ?: proposedName
                else -> event?.name ?: venue?.name
            }
            return name ?: kind.title
        }

    /** The badge: "Being checked", "Added", "Updated", "Closed" or "Not changed". */
    val badge: String
        get() = when (status) {
            SuggestionStatus.APPROVED -> when (kind) {
                SuggestionKind.MISSING_VENUE, SuggestionKind.MISSING_EVENT -> "Added"
                SuggestionKind.CLOSED -> "Closed"
                else -> "Updated"
            }
            SuggestionStatus.REJECTED -> "Not changed"
            else -> "Being checked"
        }

    /** The reviewer's reason, on a rejected suggestion. */
    val rejectionReason: String?
        get() {
            if (status != SuggestionStatus.REJECTED) return null
            return (decisions.lastOrNull { it.action == "REJECT" } ?: decisions.lastOrNull())?.reason
        }

    /** An added venue or event to open. */
    val addedRoute: AppRoute?
        get() {
            if (status != SuggestionStatus.APPROVED) return null
            return when (kind) {
                SuggestionKind.MISSING_VENUE -> venue?.let { AppRoute.VenueDetail(it.id) }
                SuggestionKind.MISSING_EVENT -> event?.let { AppRoute.EventDetail(it.id) }
                else -> null
            }
        }
}

/** A suggestion to send with `submitContribution`. */
data class SuggestionRequest(
    val kind: SuggestionKind,
    /** The venue, event or recurring night it's about; none for a missing one. */
    val targetId: String? = null,
    val localeId: String? = null,
    /** Only the fields it proposes, by their API names (`phone`, `start_at`). */
    val proposed: Map<String, Any?> = emptyMap(),
    val body: String? = null,
    /** Links that show it's right (`SubmissionEvidenceInput`); photos aren't offered yet. */
    val evidence: List<String> = emptyList(),
    /** For a missing venue or event the member says isn't the listing it matched. */
    val notListed: Boolean = false,
) {
    /** `submitContribution`'s input. `notListed` is only sent when it's set, so an API without it still works. */
    val input: Map<String, Any?>
        get() = buildMap {
            put("kind", kind.name)
            put("proposed", proposed)
            put("evidence", evidence.map { mapOf("kind" to "link", "url" to it) })
            targetId?.let { put("targetId", it) }
            localeId?.let { put("localeId", it) }
            body?.trim()?.takeIf { it.isNotEmpty() }?.let { put("body", it) }
            if (notListed) put("notListed", true)
        }
}

/** What sending a suggestion did. */
sealed interface SuggestionOutcome {
    /** Filed. [merged] when someone else had suggested the same thing. */
    data class Sent(val id: String, val merged: Boolean) : SuggestionOutcome

    /** Nothing was filed: it's already listed as this venue or event. */
    data class AlreadyListed(val isVenue: Boolean, val id: String, val name: String) : SuggestionOutcome {
        val route: AppRoute get() = if (isVenue) AppRoute.VenueDetail(id) else AppRoute.EventDetail(id)
    }

    companion object {
        /** The confirmation's words after sending. */
        fun sentMessage(merged: Boolean): String =
            if (merged) "Someone else suggested this too. We'll check them together, and let you know in Your suggestions."
            else "We'll let you know in Your suggestions when it's checked."
    }
}

/**
 * What the venue page needs that isn't in its main query (so the page still loads from an API without
 * contributions): whether it's reported closed, and its details as a fix starts from.
 */
@Serializable
data class VenueSuggestionState(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val reportedClosed: Boolean = false,
    val profile: Profile? = null,
) {
    @Serializable
    data class Profile(val website: String? = null, val phone: String? = null)

    val website: String? get() = profile?.website
    val phone: String? get() = profile?.phone
}

/** Times as the API casts them: `2026-10-24T21:00:00Z`, to the minute. */
object SuggestionFormat {
    fun timestamp(instant: Instant): String = instant.truncatedTo(ChronoUnit.MINUTES).toString()
}

/** Fields a fix may change and whether it changed them, from the API's `Contributions::Fields`. */
object FixDetail {
    /**
     * The proposed changes: each text field whose trimmed value is set and differs from what it is now, plus the
     * event's times when they moved by a minute or more, and `is_canceled` when ticked.
     */
    fun proposed(
        fields: Map<String, Pair<String?, String>>,
        start: Pair<Instant, Instant>? = null,
        end: Pair<Instant, Instant>? = null,
        canceled: Boolean = false,
    ): Map<String, Any?> = buildMap {
        fields.forEach { (key, value) ->
            val (current, typed) = value
            val trimmed = typed.trim()
            if (trimmed.isNotEmpty() && trimmed != current.orEmpty().trim()) put(key, trimmed)
        }
        start?.let { (was, now) -> if (moved(was, now)) put("start_at", SuggestionFormat.timestamp(now)) }
        end?.let { (was, now) -> if (moved(was, now)) put("end_at", SuggestionFormat.timestamp(now)) }
        if (canceled) put("is_canceled", true)
    }

    /** A new website or ticket link needs a link that shows it. */
    fun needsLink(proposed: Map<String, Any?>): Boolean = "website" in proposed || "ticket_url" in proposed

    private fun moved(was: Instant, now: Instant): Boolean = kotlin.math.abs(java.time.Duration.between(was, now).seconds) >= 60
}

@Serializable
internal data class SubmitContributionResponse(val submitContribution: Payload) {
    @Serializable
    data class Payload(
        val merged: Boolean = false,
        val submission: Submission? = null,
        val existingVenue: SuggestionSummary.Listing? = null,
        val existingEvent: SuggestionSummary.Listing? = null,
    ) {
        val outcome: SuggestionOutcome?
            get() = existingVenue?.let { SuggestionOutcome.AlreadyListed(true, it.id, it.name.orEmpty()) }
                ?: existingEvent?.let { SuggestionOutcome.AlreadyListed(false, it.id, it.name.orEmpty()) }
                ?: submission?.let { SuggestionOutcome.Sent(it.id, merged) }
    }

    @Serializable
    data class Submission(val id: String)
}

@Serializable
internal data class MySuggestionsResponse(val me: Me? = null) {
    @Serializable
    data class Me(val submissions: List<SuggestionSummary> = emptyList())
}

@Serializable
internal data class VenueSuggestionStateResponse(val venues: Venues? = null) {
    @Serializable
    data class Venues(val venue: VenueSuggestionState? = null)
}

@Serializable
internal data class ReportVenueOpenResponse(val reportVenueOpen: Payload) {
    @Serializable
    data class Payload(val venue: Venue)

    @Serializable
    data class Venue(val reportedClosed: Boolean = false)
}

internal object SuggestionDocuments {
    val SUBMIT: String = """
        mutation SubmitContribution(${'$'}input: ContributionSubmitInput!) {
          submitContribution(input: ${'$'}input) {
            merged submission { id } existingVenue { id name } existingEvent { id name }
          }
        }
    """.trimIndent()

    val MINE: String = """
        query MySuggestions {
          me { submissions(limit: 50) {
            id kind status reporterCount proposed createdAt
            venue { id name } event { id name } decisions { action reason }
          } }
        }
    """.trimIndent()

    val VENUE_STATE: String = """
        query VenueSuggestionState(${'$'}id: ID!) {
          venues { venue(id: ${'$'}id) { id name description reportedClosed profile { website phone } } }
        }
    """.trimIndent()

    val REPORT_VENUE_OPEN: String = """
        mutation ReportVenueOpen(${'$'}input: VenueOpenReportInput!) {
          reportVenueOpen(input: ${'$'}input) { venue { id reportedClosed } }
        }
    """.trimIndent()
}
