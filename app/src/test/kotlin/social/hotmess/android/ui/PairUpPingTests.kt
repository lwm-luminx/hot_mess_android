package social.hotmess.android.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import social.hotmess.android.ui.screens.JoinButton
import social.hotmess.android.ui.screens.PairUpController
import social.hotmess.android.ui.screens.PairUpRow
import social.hotmess.android.ui.screens.PairUpSheet
import social.hotmess.android.ui.screens.PlanChangePrompt
import social.hotmess.core.PairUpMatch
import social.hotmess.core.PairUpPartner
import social.hotmess.core.PairUpState
import social.hotmess.core.PairUpStatus
import social.hotmess.core.PairUpTopic
import social.hotmess.core.VenuePairUp

/** Quickie's sheet through a pairing, and the Ping pieces Now and a venue show, against [StubApi]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class PairUpPingTests {
    @get:Rule val compose = createComposeRule()

    private val stub = StubApi().apply { stubBasics() }

    @After fun everythingStubbed() {
        assertTrue("Unstubbed requests: ${stub.unstubbed}", stub.unstubbed.isEmpty())
    }

    private val match = """{"id":"m1","accepted":true,"partnerAccepted":true,"shared":false,"partnerShared":false,"bonus":false,
        "metAt":null,"endReason":null,"topic":{"key":"karaoke","text":"Worst song you'd still sing"},
        "partner":{"id":"p1","name":"Jamie","avatarUrl":null,"lookFor":"Green hat"}}"""

    private fun pairUp(here: Boolean, mine: String = "null") = """{"venues":{"venue":{"pairUp":{"here":$here,"mine":$mine}}}}"""

    private fun state(status: String, match: String = "null", metCount: Int = 0) =
        """{"status":"$status","lookFor":"Red jacket","metCount":$metCount,"match":$match,"bonus":null}"""

    private fun showSheet(): PairUpController {
        val graph = stub.graph()
        val controller = PairUpController(graph.api, Fixtures.VENUE_ID, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        runBlocking { controller.load() }
        compose.show(graph) { PairUpSheet(controller, venueName = "Nyne", onDismiss = {}) }
        return controller
    }

    @Test fun awayFromTheVenueQuickieWaits() {
        stub.data("VenuePairUp", pairUp(here = false))
        showSheet()
        compose.waitForText("Quickie opens once you're at Nyne.")
        compose.waitForText("Meet someone at Nyne")
    }

    @Test fun atTheVenueStartingJoins() {
        stub.data("VenuePairUp", pairUp(here = true))
        stub.data("JoinPairUp", """{"joinPairUp":{"pairUp":${state(PairUpStatus.SEARCHING)}}}""")
        val controller = showSheet()
        compose.waitForText("Meet someone at Nyne")
        compose.waitForText("How can they spot you? (optional)")
        assertTrue(controller.here)
    }

    @Test fun searchingSaysSo() {
        stub.data("VenuePairUp", pairUp(here = true, mine = state(PairUpStatus.SEARCHING)))
        showSheet()
        compose.waitForText("Finding someone to chat with")
        compose.waitForText("Leave Quickie")
    }

    @Test fun revealedShowsWhoToLookFor() {
        stub.data("VenuePairUp", pairUp(here = true, mine = state(PairUpStatus.REVEALED, match)))
        stub.data("ShowPairUpCode", """{"showPairUpCode":{"code":"HM-1234"}}""")
        showSheet()
        compose.waitForText("Look for Jamie")
        compose.waitForText("Green hat")
        compose.waitForText("Worst song you'd still sing")
    }

    @Test fun afterMeetingItOffersToShare() {
        val met = match.replace("\"metAt\":null", "\"metAt\":\"2026-11-14T22:00:00Z\"")
        stub.data("VenuePairUp", pairUp(here = true, mine = state(PairUpStatus.MET, met, metCount = 2)))
        showSheet()
        compose.waitForText("Hope you and Jamie had a good chat.")
    }

    @Test fun theVenueRowInvitesToQuickie() {
        var opened = false
        val pairUp = VenuePairUp(here = true, mine = null)
        compose.show(stub.graph()) { PairUpRow(pairUp, onOpen = { opened = true }) }
        compose.waitUntil(5_000) { compose.hasText("Quickie") }
        // The row's own button opens the sheet.
        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        assertTrue(opened)
    }

    @Test fun aMatchedRowOffersToGoAgain() {
        val partner = PairUpPartner(id = "p1", name = "Jamie")
        val met = PairUpState(status = PairUpStatus.MET, metCount = 1,
            match = PairUpMatch(id = "m1", accepted = true, partnerAccepted = true, metAt = "2026-11-14T22:00:00Z",
                topic = PairUpTopic("k", "Topic"), partner = partner))
        compose.show(stub.graph(), darkTheme = true) { PairUpRow(VenuePairUp(here = true, mine = met), onOpen = {}, onGoAgain = {}) }
        compose.waitUntil(5_000) { compose.hasText("Quickie") }
    }

    @Test fun planChangeAsksAndAnswers() {
        val answers = mutableListOf<String>()
        compose.show(stub.graph()) {
            PlanChangePrompt(venueName = "Nyne", pingPlaces = "The Globe", busy = false,
                onMove = { answers += "move" }, onKeep = { answers += "keep" })
        }
        compose.waitForText("Nyne")
        compose.waitForText("The Globe")
        assertEquals(emptyList<String>(), answers)
    }

    @Test fun joinButtonSaysWhetherYoureIn() {
        var joins = 0
        compose.show(stub.graph()) { JoinButton(joined = false, enabled = true, onJoin = { joins++ }) }
        compose.waitUntil(5_000) { compose.hasText("in") }
        compose.onNodeWithText("in", substring = true).performClick()
        assertEquals(1, joins)
    }
}
