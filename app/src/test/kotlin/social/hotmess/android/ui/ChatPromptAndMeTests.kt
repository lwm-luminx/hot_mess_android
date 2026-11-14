package social.hotmess.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.hotmess.android.ui.screens.MeScreen
import social.hotmess.android.ui.screens.VenueChatScreen
import social.hotmess.core.ChatRoom

/** A room's prompts and trivia, and Me's account settings: blocked people, feedback, legal and about. */
class ChatPromptAndMeTests : ScreenTestCase() {
    private val sam = "2c3d4e5f-6071-4829-8b3c-4d5e6f7a8b9c"

    private fun promptLine(id: String, text: String, kind: String = "CONVERSATION", shownAs: String = "CARD", answer: String? = null, revealed: Boolean = false) =
        """{"id":"m-$id","message":"$text","body":"$text","name":"Hot Mess","userId":"00000000-0000-4000-8000-000000000001",
        "sentAt":"${java.time.Instant.now().minusSeconds(120)}","kind":"PROMPT","postedAsAudience":true,
        "prompt":{"id":"$id","kind":"$kind","text":"$text","answer":${answer?.let { "\"$it\"" } ?: "null"},
        "revealAt":${if (revealed) "\"${java.time.Instant.now().minusSeconds(30)}\"" else "\"${java.time.Instant.now().plusSeconds(3600)}\""},
        "shownAs":"$shownAs","number":2,"total":6,"nextAt":null}}"""

    private fun answerLine(id: String, text: String, promptId: String) =
        """{"id":"$id","message":"$text","body":"$text","name":"Sam B.","userId":"$sam","sentAt":"${java.time.Instant.now().minusSeconds(60)}","promptId":"$promptId"}"""

    private fun joined(vararg lines: String) = """{"__typename":"ChatJoined","outOfRange":false,
        "roster":{"people":[{"userId":"$sam","name":"Sam B."}],"friends":[],"recent":[]},
        "history":[${lines.joinToString(",") { """{"message":$it}""" }}],"pinnedMessage":null}"""

    private fun showRoom() {
        stub.error("VenueLineup", "No lineup in the stub")
        stub.error("Tribes", "No tribes in the stub")
        stub.tolerate("ChatInitials", "RecentChatPush", "Venue")
        show { VenueChatScreen(ChatRoom.venue(Fixtures.VENUE_ID), "Nyne", navigator) }
    }

    // region Prompts

    @Test fun aPromptCardAsksTheRoom() {
        stub.cable.on("venueChat", joined(promptLine("pr1", "What was your first drag show?")))
        showRoom()
        expectText("What was your first drag show?")
        expectText("Answer")
    }

    @Test fun answeringAPromptSendsItsId() {
        stub.cable.on("venueChat", joined(promptLine("pr1", "What was your first drag show?")))
        stub.data("SendMyChatMessage", """{"sendMyChatMessage":{"message":${answerLine("l9", "Rocky Horror!", "pr1")}}}""")
        showRoom()
        tap("Answer")
        type("Message", "Rocky Horror!")
        tap("Send")
        awaitRequest("SendMyChatMessage")
        val input = stub.last("SendMyChatMessage")!!.variables.getJSONObject("input")
        assertEquals("pr1", input.optString("promptId"))
        expectText("Rocky Horror!")
    }

    @Test fun aPinnedPromptLeadsTheRoomWithItsAnswers() {
        stub.cable.on("venueChat", joined(promptLine("pr2", "Best karaoke song?", shownAs = "PINNED"), answerLine("l1", "Total Eclipse", "pr2")))
        showRoom()
        expectText("Best karaoke song?")
        expectText("Tonight's prompt")
        expectText("Total Eclipse")
    }

    @Test fun pinnedTriviaHidesItsAnswerUntilAsked() {
        stub.cable.on("venueChat", joined(promptLine("tq1", "Who sang Vogue?", kind = "TRIVIA", shownAs = "PINNED", answer = "Madonna")))
        showRoom()
        expectText("Who sang Vogue?")
        expectText("Trivia")
        assertTrue(!compose.hasText("Madonna"))
    }

    @Test fun aTriviaCardOffersToAnswerOrNot() {
        stub.cable.on("venueChat", joined(promptLine("tq2", "Who sang Vogue?", kind = "TRIVIA", answer = "Madonna")))
        showRoom()
        expectText("Who sang Vogue?")
        expectText("Trivia")
        tap("Not now")
        compose.waitUntil(10_000) { !compose.hasText("Not now") }
    }

    // endregion

    // region Me

    private fun showMe(blocked: String = "[]") {
        stub.data("Safety", """{"me":{"termsAcceptedAt":"2026-01-01T00:00:00Z","blockedUsers":$blocked,"canPretendLocation":false,"self":{"hasFacebook":true}}}""")
        stub.tolerate("Tribes", "CalendarFeed", "Admissions", "ChatInitials", "RecentChatPush", "PairUpPush", "AgeCheck", "ManagedListings")
        show { MeScreen(navigator) }
    }

    @Test fun meListsBlockedPeopleAndUnblocks() {
        stub.data("UnblockUser", """{"unblockUser":{"blockedUsers":[]}}""")
        showMe("""[{"id":"$sam","name":"Sam B.","avatarUrl":null}]""")
        expectText("Blocked")
        expectText("Sam B.")
        tap("Unblock")
        awaitRequest("UnblockUser")
        assertEquals(sam, stub.last("UnblockUser")?.variables?.optString("userId"))
    }

    @Test fun meHasFeedbackLegalAndAbout() {
        showMe()
        expectText("Feedback")
        expectText("Check the app")
        expectText("Report a problem")
        scrollTo("Terms of use")
        expectText("Terms of use")
        expectText("Privacy policy")
        scrollTo("Version")
        expectText("Version")
        scrollTo("Check the app")
        tap("Check the app")
        assertEquals(listOf("check"), navigator.opened)
    }

    @Test fun mePassesOpenTheList() {
        showMe()
        tap("Your passes")
        assertTrue(navigator.opened.toString(), navigator.opened.single() == "passes" || navigator.opened.single().startsWith("pass"))
    }

    // endregion
}
