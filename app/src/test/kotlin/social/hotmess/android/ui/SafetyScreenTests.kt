package social.hotmess.android.ui

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.hotmess.android.ui.screens.BlockDialog
import social.hotmess.android.ui.screens.GetHelpScreen
import social.hotmess.android.ui.screens.GettingHelpScreen
import social.hotmess.android.ui.screens.ReportDialog
import social.hotmess.android.ui.screens.SafetyAlertsScreen
import social.hotmess.android.ui.screens.SupportScreen
import social.hotmess.android.ui.screens.TermsDialog
import social.hotmess.core.BlockedUser
import social.hotmess.core.HelpVenue
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget

/** Get help, the support lines, the user's safety alerts, and reporting and blocking. */
class SafetyScreenTests : ScreenTestCase() {
    private val nyne = HelpVenue(Fixtures.VENUE_ID, "Nyne", "232 W Sprague")

    private fun alert(state: String = "OPEN", acknowledgedBy: String? = null) = """{"id":"${SafetyFixtures.ALERT_ID}",
        "state":"$state","place":"BAR","concerns":["BOTHERING"],"description":"Red jacket","summary":"At the bar",
        "seen":${acknowledgedBy != null},"acknowledgedByName":${acknowledgedBy?.let { "\"$it\"" } ?: "null"},
        "acknowledgedAt":${if (acknowledgedBy != null) "\"${Fixtures.iso(0)}\"" else "null"},"escalatedAt":null,
        "closedAt":null,"supportSharedAt":null,"supportNote":null,"createdAt":"${Fixtures.iso(0)}",
        "venue":{"id":"${Fixtures.VENUE_ID}","name":"Nyne"},"messages":[]}"""

    private fun stubHelp(staffOnShift: Boolean = true, alerts: String = "[]") {
        stub.data("VenueSafety", """{"venues":{"venue":{"id":"${Fixtures.VENUE_ID}","canSendSafetyAlert":true,"safetyStaffOnShift":$staffOnShift}}}""")
        stub.data("MySafetyAlerts", """{"me":{"safetyAlerts":$alerts}}""")
        stub.data("SupportRides", """{"supportResources":{"rides":[]}}""")
        stub.tolerate("SafetyAlert")
    }

    // region Get help

    @Test fun getHelpOffersStaffAFriendARideAndSupport() {
        stubHelp()
        show { GetHelpScreen(nyne, null, navigator) }
        expectText("Get help at Nyne")
        expectText("Tell staff I feel unsafe")
        expectText("Message a friend")
        expectText("Need support?")
        expectText("Call 911")
        awaitRequest("VenueSafety")
        assertEquals(Fixtures.VENUE_ID, stub.last("VenueSafety")?.variables?.optString("id"))
    }

    @Test fun withNobodyOnShiftThereIsNoTellStaffRow() {
        stubHelp(staffOnShift = false)
        show { GetHelpScreen(nyne, null, navigator) }
        awaitRequest("VenueSafety")
        expectText("Message a friend")
        compose.waitUntil(10_000) { !compose.hasText("Tell staff I feel unsafe") }
    }

    @Test fun tellingStaffSendsWhereAndWhat() {
        stubHelp()
        stub.data("SendSafetyAlert", """{"sendSafetyAlert":{"safetyAlert":${alert()}}}""")
        show { GetHelpScreen(nyne, null, navigator) }
        tap("Tell staff I feel unsafe")
        expectText("Tell staff quietly")
        tap("Dance floor")
        tap("Someone's following me")
        type("What they look like", "Green hat")
        tap("Send to staff")
        awaitRequest("SendSafetyAlert")
        val sent = stub.last("SendSafetyAlert")!!.variables
        assertEquals(Fixtures.VENUE_ID, sent.optString("venueId"))
        assertEquals("DANCE_FLOOR", sent.optString("place"))
        assertEquals(JSONArray(listOf("FOLLOWING")).toString(), sent.optJSONArray("concerns")?.toString())
        assertEquals("Green hat", sent.optString("description"))
        expectText("Staff have your alert")
    }

    @Test fun anAlertSomeonePickedUpSaysWhoIsComing() {
        stubHelp(alerts = "[${alert(state = "ACKNOWLEDGED", acknowledgedBy = "Jo")}]")
        stub.data("SafetyAlert", """{"me":{"safetyAlert":${alert(state = "ACKNOWLEDGED", acknowledgedBy = "Jo")}}}""")
        show { GetHelpScreen(nyne, SafetyFixtures.ALERT_ID, navigator) }
        expectText("Someone's on the way")
        expectText("Jo is on the way")
        expectText("What you sent")
    }

    @Test fun aRefusedAlertSaysStaffCouldntBeReached() {
        stubHelp()
        stub.error("SendSafetyAlert", "Safety alerts are off at this venue")
        show { GetHelpScreen(nyne, null, navigator) }
        tap("Tell staff I feel unsafe")
        tap("Send to staff")
        awaitRequest("SendSafetyAlert")
        expectText("Couldn't reach staff")
    }

    @Test fun needSupportOpensTheSupportLines() {
        stubHelp()
        show { GetHelpScreen(nyne, null, navigator) }
        tap("Need support?")
        assertEquals(listOf("support"), navigator.opened)
    }

    // endregion

    // region Support, alerts and the explainer

    @Test fun supportListsTheRegionsLines() {
        stub.data(
            "SupportResources",
            """{"supportResources":{"region":"US","name":"United States","emergency":"911","checkedAt":"2026-10-01",
            "lines":[{"id":"988","name":"988 Suicide & Crisis Lifeline","description":"Call or text, any time.","phone":"988",
            "textNumber":"988","textBody":null,"url":"https://988lifeline.org"},
            {"id":"trevor","name":"The Trevor Project","description":"For LGBTQ+ young people.","phone":"18664887386",
            "textNumber":null,"textBody":null,"url":null}]}}""",
        )
        stub.data("SupportRides", """{"supportResources":{"rides":[]}}""")
        show { SupportScreen(null, navigator) }
        expectText("Need support?")
        expectText("988 Suicide & Crisis Lifeline")
        expectText("The Trevor Project")
        expectText("Call")
    }

    @Test fun supportThatCantLoadStillGivesTheCrisisLine() {
        stub.on("SupportResources", StubApi.Reply.Raw(500, "{}"))
        stub.tolerate("SupportRides")
        show(dark = true) { SupportScreen("Staff are on their way.", navigator) }
        expectText("call or text 988")
        expectText("A note from the venue")
        expectText("Staff are on their way.")
    }

    @Test fun safetyAlertsListsTheUsersAlerts() {
        stub.data("MySafetyAlerts", """{"me":{"safetyAlerts":[${alert(state = "ACKNOWLEDGED", acknowledgedBy = "Jo")}]}}""")
        show { SafetyAlertsScreen(navigator) }
        expectText("Your safety alerts")
        expectText("Nyne")
        expectText("Jo is on it")
    }

    @Test fun gettingHelpExplainsTheButton() {
        show { GettingHelpScreen(navigator) }
        expectText("Getting help")
        expectText("quiet Get help button")
        expectText("Only staff see who sent an alert")
        tap("Back")
        assertEquals(listOf("back"), navigator.opened)
    }

    // endregion

    // region Report, block and the terms

    @Test fun reportingAPersonSendsTheReasonAndTheBlock() {
        stub.data("ReportContent", """{"reportContent":{"reported":true,"blocked":true}}""")
        var dismissed = false
        show { ReportDialog(ReportSubject(ReportTarget.PERSON, SafetyFixtures.PERSON_ID, BlockedUser(SafetyFixtures.PERSON_ID, "Sam B.")), onDismiss = { dismissed = true }) }
        expectText("Why are you reporting this person?")
        tap("Harassment or hate")
        tap("Also block Sam B.")
        tap("Report")
        awaitRequest("ReportContent")
        val sent = stub.last("ReportContent")!!.variables
        assertEquals("PERSON", sent.optString("targetType"))
        assertEquals(SafetyFixtures.PERSON_ID, sent.optString("targetId"))
        assertEquals("Harassment or hate", sent.optString("reason"))
        assertTrue(sent.optBoolean("block"))
        compose.waitUntil(10_000) { dismissed || compose.hasText("Thanks") }
    }

    @Test fun cancellingAReportSendsNothing() {
        var dismissed = false
        show { ReportDialog(ReportSubject(ReportTarget.MESSAGE, "m1", BlockedUser(SafetyFixtures.PERSON_ID, "Sam B.")), onDismiss = { dismissed = true }) }
        expectText("Why are you reporting this message?")
        tap("Cancel")
        assertTrue(dismissed)
        assertEquals(0, stub.count("ReportContent"))
    }

    @Test fun blockingSendsTheBlock() {
        stub.data("BlockUser", """{"blockUser":{"blockedUsers":[{"id":"${SafetyFixtures.PERSON_ID}","name":"Sam B.","avatarUrl":null}]}}""")
        var dismissed = false
        show { BlockDialog(BlockedUser(SafetyFixtures.PERSON_ID, "Sam B."), onDismiss = { dismissed = true }) }
        expectText("Block Sam B.?")
        expectText("They aren't told")
        tap("Block")
        awaitRequest("BlockUser")
        assertEquals(SafetyFixtures.PERSON_ID, stub.last("BlockUser")?.variables?.optString("userId"))
        compose.waitUntil(10_000) { dismissed }
    }

    @Test fun theTermsAskBeforePosting() {
        var agreed = false
        var dismissed = false
        stub.tolerate("AcceptTerms", "AcceptTermsAsAdult")
        show { TermsDialog(onAgreed = { agreed = true }, onDismiss = { dismissed = true }) }
        expectText("Before you post")
        expectText("Read the terms of use")
        tap("Not now")
        assertTrue(dismissed)
        assertTrue(!agreed)
    }

    @Test fun agreeingToTheTermsRecordsIt() {
        var agreed = false
        stub.data("AcceptTerms", """{"acceptTerms":{"termsAcceptedAt":"${Fixtures.iso(0)}"}}""")
        stub.data("AcceptTermsAsAdult", """{"acceptTermsAsAdult":{"termsAcceptedAt":"${Fixtures.iso(0)}"}}""")
        show { TermsDialog(onAgreed = { agreed = true }, onDismiss = {}) }
        tap("I agree")
        compose.waitUntil(10_000) { agreed }
        assertTrue(stub.count("AcceptTerms") + stub.count("AcceptTermsAsAdult") >= 1)
    }

    // endregion
}

object SafetyFixtures {
    const val ALERT_ID = "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d"
    const val PERSON_ID = "2c3d4e5f-6071-4829-8b3c-4d5e6f7a8b9c"
}
