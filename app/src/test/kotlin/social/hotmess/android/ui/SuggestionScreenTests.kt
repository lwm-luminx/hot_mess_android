package social.hotmess.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Test
import social.hotmess.android.ui.screens.FixDetailScreen
import social.hotmess.android.ui.screens.FixTarget
import social.hotmess.android.ui.screens.SuggestFixScreen
import social.hotmess.android.ui.screens.SuggestListingScreen
import social.hotmess.android.ui.screens.YourSuggestionsScreen

/** Suggesting a missing venue, a fix to one, and the member's own suggestions. */
class SuggestionScreenTests : ScreenTestCase() {
    private val nyne = FixTarget(isVenue = true, id = Fixtures.VENUE_ID, name = "Nyne")

    @Test fun aVenueSuggestionNeedsANameAndWhereItIs() {
        show { SuggestListingScreen(event = false, venueId = null, venueName = null, navigator = navigator) }
        expectText("Suggest a venue")
        compose.onNodeWithTag("suggest.send").assertIsNotEnabled()
        type("Name of the bar or club", "The Eagle")
        compose.onNodeWithTag("suggest.send").assertIsNotEnabled()
        type("Street address", "514 W Sprague")
        compose.onNodeWithTag("suggest.send").assertIsEnabled()
    }

    // An event at a venue whose page it came from: no address to geocode, so the request goes straight out.
    @Test fun suggestingAnEventAtAVenueSendsIt() {
        stub.data("SubmitContribution", """{"submitContribution":{"merged":false,"submission":{"id":"s1"},"existingVenue":null,"existingEvent":null}}""")
        show { SuggestListingScreen(event = true, venueId = Fixtures.VENUE_ID, venueName = "Nyne", navigator = navigator) }
        expectText("Suggest an event")
        type("Name of the night", "Drag Bingo")
        compose.onNodeWithTag("suggest.send").performClick()
        awaitRequest("SubmitContribution")
        val input = stub.last("SubmitContribution")!!.variables.getJSONObject("input").toString()
        assertTrue(input, input.contains("Drag Bingo"))
        assertTrue(input, input.contains(Fixtures.VENUE_ID))
        assertTrue(input, input.contains("MISSING_EVENT"))
    }

    @Test fun aRefusedSuggestionSaysWhy() {
        stub.error("SubmitContribution", "New accounts can send 3 suggestions a day")
        show { SuggestListingScreen(event = true, venueId = Fixtures.VENUE_ID, venueName = "Nyne", navigator = navigator) }
        type("Name of the night", "Drag Bingo")
        compose.onNodeWithTag("suggest.send").performClick()
        awaitRequest("SubmitContribution")
        expectText("Try again in a moment.")
        compose.onNodeWithTag("suggest.send").assertIsEnabled()
    }

    @Test fun suggestingAFixOffersEachKind() {
        show { SuggestFixScreen(nyne, navigator) }
        expectText("Suggest a fix")
        expectText("It's closed for good")
        expectText("Add a missing event")
        tap("Fix a detail")
        assertEquals(listOf("fixDetail"), navigator.opened)
    }

    @Test fun closedForGoodAsksFirstThenSends() {
        stub.data("SubmitContribution", """{"submitContribution":{"merged":true,"submission":{"id":"s2"},"existingVenue":null,"existingEvent":null}}""")
        show { SuggestFixScreen(nyne, navigator) }
        tap("It's closed for good")
        expectText("Not just tonight?")
        compose.onAllNodes(androidx.compose.ui.test.hasText("It's closed for good") and androidx.compose.ui.test.hasClickAction())
            .let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        awaitRequest("SubmitContribution")
        assertTrue(stub.last("SubmitContribution")!!.variables.toString().contains(Fixtures.VENUE_ID))
    }

    @Test fun aFixStartsFromWhatsListed() {
        stub.data(
            "VenueSuggestionState",
            """{"venues":{"venue":{"id":"${Fixtures.VENUE_ID}","name":"Nyne","description":"Dance bar downtown",
            "reportedClosed":false,"profile":{"website":"https://nyne.bar","phone":"+15095550100"}}}}""",
        )
        show { FixDetailScreen(nyne, navigator) }
        expectText("Fix a detail")
        expectText("Dance bar downtown")
        expectText("https://nyne.bar")
        expectText("Only what you change is sent.")
    }

    @Test fun yourSuggestionsShowsEachWithItsState() {
        stub.data(
            "MySuggestions",
            """{"me":{"submissions":[
            {"id":"s1","kind":"MISSING_VENUE","status":"APPROVED","reporterCount":3,"proposed":{"name":"The Eagle"},
             "createdAt":"${Fixtures.iso(-48)}","venue":{"id":"v9","name":"The Eagle"},"event":null,"decisions":[]},
            {"id":"s2","kind":"CORRECTION","status":"REJECTED","reporterCount":1,"proposed":{},
             "createdAt":"${Fixtures.iso(-24)}","venue":{"id":"${Fixtures.VENUE_ID}","name":"Nyne"},"event":null,
             "decisions":[{"action":"REJECT","reason":"The phone number is right."}]},
            {"id":"s3","kind":"MISSING_EVENT","status":"SUBMITTED","reporterCount":1,"proposed":{"name":"Drag Bingo"},
             "createdAt":"${Fixtures.iso(-1)}","venue":null,"event":null,"decisions":[]}]}}""",
        )
        show { YourSuggestionsScreen(navigator) }
        expectText("The Eagle")
        expectText("Added")
        expectText("You and 2 others suggested it")
        expectText("Not changed")
        expectText("The phone number is right.")
        expectText("Drag Bingo")
        expectText("Being checked")
        tap("Open")
        assertEquals(1, navigator.opened.size)
        assertTrue(navigator.opened.single(), navigator.opened.single().contains("v9"))
    }

    @Test fun withoutSuggestionsItSaysHowToMakeOne() {
        stub.data("MySuggestions", """{"me":{"submissions":[]}}""")
        show { YourSuggestionsScreen(navigator) }
        expectText("No suggestions yet")
    }
}
