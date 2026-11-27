package social.hotmess.android.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import social.hotmess.android.AppGraph
import social.hotmess.android.ui.screens.EventScreen
import social.hotmess.android.ui.screens.EventsScreen
import social.hotmess.android.ui.screens.LoginScreen
import social.hotmess.android.ui.screens.MeScreen
import social.hotmess.android.ui.screens.NowScreen
import social.hotmess.android.ui.screens.VenueScreen
import social.hotmess.android.ui.screens.VenuesScreen

/**
 * The app's screens rendered under Robolectric against [StubApi]: no network, no sign-in, no device. Each test fails
 * if a screen asked for something the test didn't stub.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized screen, so the lazy lists compose what a person would see without scrolling.
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenTests {
    @get:Rule val compose = createComposeRule()

    private val stub = StubApi().apply {
        stubBasics()
        stubVenueSideLoads()
        data("ClosestLocale", """{"closestLocale":{"id":"${Fixtures.LOCALE_ID}","name":"Spokane","label":"spokane"}}""")
        data("LocaleEvents", """{"locale":{"events":{"upcoming":{"nodes":[${Fixtures.event(name = "Drag Brunch", startHours = 3)}]}}}}""")
        error("VenuePhotos", "No photos in the stub")
        error("EventPhotos", "No photos in the stub")
        data("EventSetList", """{"events":{"event":{"id":"${Fixtures.EVENT_ID}","setList":null}}}""")
        error("LocalePosts", "No posts in the stub")
    }

    @After fun everythingStubbed() {
        assertTrue("Unstubbed requests: ${stub.unstubbed}", stub.unstubbed.isEmpty())
    }

    private fun show(atVenue: Boolean = false, dark: Boolean = false, content: @Composable () -> Unit): AppGraph {
        val graph = stub.graph()
        if (atVenue) graph.location.simulate(47.65, -117.41, "Nyne")
        compose.show(graph, darkTheme = dark) { content() }
        return graph
    }

    private fun expectText(text: String) {
        try {
            compose.waitForText(text)
        } catch (error: Throwable) {
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val dump = (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString() }
            throw AssertionError("No \"$text\" on screen:\n$dump", error)
        }
    }

    // region Now

    @Test fun nowWithoutAPositionOffersLocationAndPing() {
        show { NowScreen(RecordingNavigator()) }
        expectText("Turn on location")
        expectText("Ping")
        assertEquals(0, stub.count("ReportLocation"))
        assertTrue(stub.count("MyPing") >= 1)
    }

    @Test fun nowOffersFindingFriendsAndShowsRequests() {
        stub.data("ContactMatching", Fixtures.CONTACT_MATCHING_OFFERED)
        show { NowScreen(RecordingNavigator()) }
        expectText("Find friends from your contacts")
        expectText("Jordan P. wants to be friends")
    }

    @Test fun nowAtAVenueLeadsWithTheVenue() {
        stub.data("ReportLocation", Fixtures.nowAtVenue)
        show(atVenue = true) { NowScreen(RecordingNavigator()) }
        expectText("Pretending to be at Nyne")
        expectText("Stop pretending")
        assertTrue(stub.count("ReportLocation") >= 1)
        assertTrue("asked for tonight's lineup at the venue", stub.count("VenueLineup") >= 1)
    }

    @Test fun nowAtAnEventIsAboutTheEvent() {
        stub.data("ReportLocation", Fixtures.nowAtEvent)
        show(atVenue = true, dark = true) { NowScreen(RecordingNavigator()) }
        expectText("Bingo Night")
    }

    @Test fun nowNearVenuesListsThem() {
        stub.data("ReportLocation", Fixtures.nowNearby)
        show(atVenue = true) { NowScreen(RecordingNavigator()) }
        expectText("The Stud")
    }

    @Test fun nowShowsTheErrorWhenItCantLoad() {
        stub.on("ReportLocation", StubApi.Reply.Raw(500, "{}"))
        show(atVenue = true) { NowScreen(RecordingNavigator()) }
        expectText("Something went wrong")
    }

    // endregion

    // region Venue and event

    @Test fun venueShowsItsAddressAndWhatsComingUp() {
        stub.data("Venue", Fixtures.venueOverview)
        show { VenueScreen(Fixtures.VENUE_ID, RecordingNavigator()) }
        expectText("232 W Sprague")
        expectText("Coming up")
        expectText("Bingo Night")
        assertEquals(Fixtures.VENUE_ID, stub.last("Venue")?.variables?.optString("id"))
    }

    @Test fun aVenueTheApiDoesntHaveIsAnError() {
        stub.data("Venue", """{"venues":{"venue":null}}""")
        show(dark = true) { VenueScreen(Fixtures.VENUE_ID, RecordingNavigator()) }
        expectText("Something went wrong")
    }

    @Test fun eventShowsItsHostAndRsvpChoices() {
        stub.data("Event", Fixtures.eventDetail())
        show { EventScreen(Fixtures.EVENT_ID, RecordingNavigator()) }
        expectText("Bingo Night")
        expectText("Hosted by Miss Coco")
        expectText("Your RSVP")
        expectText("Going")
        expectText("Interested")
        expectText("Not going")
    }

    @Test fun goingSendsTheRsvp() {
        stub.data("Event", Fixtures.eventDetail())
        stub.data("RSVPEvent", """{"rsvpEvent":{"event":{"id":"${Fixtures.EVENT_ID}","rsvp":{"mine":"ATTENDING"}}}}""")
        show { EventScreen(Fixtures.EVENT_ID, RecordingNavigator()) }
        expectText("Going")
        compose.onNode(hasText("Going") and isSelectable()).performScrollTo().performClick()
        compose.waitUntil(10_000) { stub.count("RSVPEvent") >= 1 }
        assertEquals("ATTENDING", stub.last("RSVPEvent")?.variables?.optString("state"))
    }

    @Test fun anRsvpTheApiRefusesSaysSo() {
        stub.data("Event", Fixtures.eventDetail(rsvp = "\"MAYBE\""))
        stub.error("RSVPEvent", "RSVPs are closed", code = "FORBIDDEN")
        show { EventScreen(Fixtures.EVENT_ID, RecordingNavigator()) }
        expectText("Going")
        compose.onNode(hasText("Going") and isSelectable()).performScrollTo().performClick()
        compose.waitUntil(10_000) { stub.count("RSVPEvent") >= 1 }
        compose.waitForIdle()
        expectText("Couldn't save RSVP")
        expectText("RSVPs are closed")
    }

    @Test fun anEventThatFailsToLoadIsAnError() {
        stub.on("Event", StubApi.Reply.Raw(500, "{}"))
        show { EventScreen(Fixtures.EVENT_ID, RecordingNavigator()) }
        expectText("Something went wrong")
    }

    // endregion

    // region Tabs

    @Test fun venuesListsTheLocalesVenues() {
        stub.data("VenuesPage", Fixtures.venuesPage)
        show { VenuesScreen(RecordingNavigator()) }
        expectText("Nyne")
        expectText("The Globe")
    }

    @Test fun venuesShowsTheErrorWhenTheListCantBeHad() {
        stub.on("VenuesPage", StubApi.Reply.Raw(503, "{}"))
        show { VenuesScreen(RecordingNavigator()) }
        expectText("Something went wrong")
    }

    @Test fun eventsWithoutAPositionAsksWhereYouAre() {
        show { EventsScreen(RecordingNavigator()) }
        expectText("Where are you?")
        expectText("Choose a city")
    }

    @Test fun eventsFindsTheLocaleAndItsEvents() {
        stub.data("ReportLocation", Fixtures.nowNearby)
        show(atVenue = true) { EventsScreen(RecordingNavigator()) }
        compose.waitUntil(10_000) { stub.count("LocaleEvents") >= 1 }
        assertEquals(Fixtures.LOCALE_ID, stub.last("LocaleEvents")?.variables?.optString("id"))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("1 event", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun meShowsTheAccountAndPasses() {
        show { MeScreen(RecordingNavigator()) }
        expectText("Signed in")
        expectText("Your passes")
        expectText("Location access")
    }

    @Test fun loginOffersEachMethodAndTheReviewCode() {
        stub.on("GET /v1/token/methods", StubApi.Reply.Raw(200, Fixtures.SIGN_IN_METHODS))
        var signedIn = false
        show { LoginScreen(onSignIn = { signedIn = true }) }
        expectText("Continue with Facebook")
        expectText("Continue with Google")
        expectText("Have an App Review code?")
        expectText("I confirm I am 18 or older")
        assertFalse(signedIn)
    }

    @Test fun loginWithoutMethodsSaysSignInIsUnavailable() {
        stub.on("GET /v1/token/methods", StubApi.Reply.Raw(200, """{"methods":[]}"""))
        show(dark = true) { LoginScreen(onSignIn = {}) }
        expectText("Sign-in isn't available")
    }

    // endregion
}
