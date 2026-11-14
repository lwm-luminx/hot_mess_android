package social.hotmess.core

import com.audiencekit.graphQLVariables
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionsTest {
    private fun summary(json: String) = HotMessApi.json.decodeFromString(SuggestionSummary.serializer(), json)

    @Test
    fun aRejectedSuggestionShowsTheReviewersReason() {
        val suggestion = summary(
            """{"id":"s1","kind":"CORRECTION","status":"REJECTED","reporterCount":2,"proposed":{"phone":"555"},
               "createdAt":"2026-11-01T20:00:00Z","venue":{"id":"v1","name":"Purr"},
               "decisions":[{"action":"COMMENT","reason":"Asked"},{"action":"REJECT","reason":"That's the old number"}]}""",
        )
        assertEquals("Purr", suggestion.title)
        assertEquals("Not changed", suggestion.badge)
        assertEquals("That's the old number", suggestion.rejectionReason)
        assertNull(suggestion.addedRoute)
    }

    @Test
    fun anAddedVenueOpensAndUnknownKindsReadAsAFix() {
        val added = summary(
            """{"id":"s2","kind":"MISSING_VENUE","status":"APPROVED","proposed":{"name":"The Sun"},
               "createdAt":"2026-11-01T20:00:00Z","venue":{"id":"v2"}}""",
        )
        assertEquals("The Sun", added.title)
        assertEquals("Added", added.badge)
        assertEquals(AppRoute.VenueDetail("v2"), added.addedRoute)

        val newer = summary("""{"id":"s3","kind":"SOMETHING_NEW","status":"WHO_KNOWS","createdAt":"2026-11-01T20:00:00Z"}""")
        assertEquals(SuggestionKind.CORRECTION, newer.kind)
        assertEquals("Being checked", newer.badge)
    }

    @Test
    fun theInputLeavesOutWhatWasntGiven() {
        val input = SuggestionRequest(SuggestionKind.MISSING_VENUE, proposed = mapOf("name" to "The Sun"), body = "  ").input
        assertEquals("MISSING_VENUE", input["kind"])
        assertFalse("body" in input)
        assertFalse("notListed" in input)
        assertFalse("targetId" in input)

        val variables = graphQLVariables(
            "input" to SuggestionRequest(SuggestionKind.CLOSED, targetId = "v1", evidence = listOf("https://x"), notListed = true).input,
        )
        val sent = variables.getValue("input") as JsonObject
        assertEquals(JsonPrimitive(true), sent["notListed"])
        assertEquals(JsonPrimitive("v1"), sent["targetId"])
        assertEquals(JsonPrimitive("link"), ((sent["evidence"] as kotlinx.serialization.json.JsonArray)[0] as JsonObject)["kind"])
    }

    @Test
    fun aFixSendsOnlyWhatChanged() {
        val start = Instant.parse("2026-11-07T04:00:00Z")
        val proposed = FixDetail.proposed(
            fields = mapOf("name" to ("Purr" to " Purr "), "phone" to (null to ""), "website" to ("https://a" to "https://b")),
            start = start to start.plusSeconds(30),
            end = start to start.plusSeconds(3600),
            canceled = true,
        )
        assertEquals(setOf("website", "end_at", "is_canceled"), proposed.keys)
        assertEquals("2026-11-07T05:00:00Z", proposed["end_at"])
        assertTrue(FixDetail.needsLink(proposed))
    }

    @Test
    fun theOutcomePrefersAnExistingListing() {
        val listed = HotMessApi.json.decodeFromString(
            SubmitContributionResponse.serializer(),
            """{"submitContribution":{"merged":false,"submission":null,"existingVenue":{"id":"v1","name":"Purr"}}}""",
        ).submitContribution.outcome
        assertEquals(SuggestionOutcome.AlreadyListed(true, "v1", "Purr"), listed)

        val sent = HotMessApi.json.decodeFromString(
            SubmitContributionResponse.serializer(),
            """{"submitContribution":{"merged":true,"submission":{"id":"s9"}}}""",
        ).submitContribution.outcome
        assertEquals(SuggestionOutcome.Sent("s9", merged = true), sent)
    }
}
