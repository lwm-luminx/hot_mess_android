package social.hotmess.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import org.junit.Test
import social.hotmess.android.ui.screens.VenueChatScreen
import social.hotmess.core.ChatRoom

/** A venue's chat room, with the realtime socket on [StubCable] and sending through [StubApi]. */
class ChatScreenTests : ScreenTestCase() {
    private val me = "11111111-2222-4333-8444-555555555555"
    private val sam = "2c3d4e5f-6071-4829-8b3c-4d5e6f7a8b9c"

    private fun line(id: String, text: String, userId: String = sam, name: String = "Sam B.", minutesAgo: Long = 5) =
        """{"id":"$id","message":"$text","body":"$text","name":"$name","avatarUrl":null,
        "sentAt":"${java.time.Instant.now().minusSeconds(minutesAgo * 60)}","userId":"$userId","role":null,"kind":null}"""

    private fun joined(vararg history: String) = """{"__typename":"ChatJoined","outOfRange":false,
        "roster":{"people":[{"userId":"$sam","name":"Sam B.","avatarUrl":null,"friend":false},
        {"userId":"$me","name":"Test P.","avatarUrl":null,"friend":false}],"friends":[],"recent":[]},
        "history":[${history.joinToString(",") { """{"message":$it}""" }}],"pinnedMessage":null}"""

    init {
        stub.error("VenueLineup", "No lineup in the stub")
        stub.error("Tribes", "No tribes in the stub")
        stub.tolerate("ChatInitials", "RecentChatPush", "Venue")
    }

    private fun showRoom() = show { VenueChatScreen(ChatRoom.venue(Fixtures.VENUE_ID), "Nyne", navigator) }

    @Test fun theRoomShowsItsHistory() {
        stub.cable.on("venueChat", joined(line("l1", "who's here?"), line("l2", "on the patio!", minutesAgo = 2)))
        showRoom()
        expectText("Nyne")
        expectText("who's here?")
        expectText("on the patio!")
        assertEquals(listOf("venueChat"), stub.cable.operations.distinct())
    }

    @Test fun anEmptyRoomInvitesTheFirstLine() {
        stub.cable.on("venueChat", joined())
        showRoom()
        expectText("Say hello.")
        expectText("Everyone at Nyne can see what you write here.")
    }

    @Test fun untilTheRoomAnswersItSaysConnecting() {
        showRoom()
        expectText("Connecting…")
        compose.waitUntil(10_000) { stub.cable.operations.isNotEmpty() }
    }

    @Test fun aNewLineArrivesLive() {
        stub.cable.on("venueChat", joined(line("l1", "who's here?")))
        showRoom()
        expectText("who's here?")
        stub.cable.push("venueChat", """{"__typename":"ChatMessagePosted","message":${line("l3", "just walked in", minutesAgo = 0)}}""")
        expectText("just walked in")
    }

    @Test fun sendingALinePostsItAndShowsIt() {
        stub.cable.on("venueChat", joined())
        stub.data("SendMyChatMessage", """{"sendMyChatMessage":{"message":${line("l9", "hi all", userId = me, name = "Test P.", minutesAgo = 0)}}}""")
        showRoom()
        expectText("Say hello.")
        type("Message", "hi all")
        tap("Send")
        awaitRequest("SendMyChatMessage")
        val input = stub.last("SendMyChatMessage")!!.variables.getJSONObject("input")
        assertEquals(Fixtures.VENUE_ID, input.optString("venueId"))
        assertTrue(input.toString(), input.toString().contains("hi all"))
        expectText("hi all")
    }

    // A line that didn't go stays in the composer to send again.
    @Test fun aLineTheApiRefusesStaysInTheComposer() {
        stub.cable.on("venueChat", joined())
        stub.error("SendMyChatMessage", "You're sending messages too quickly")
        showRoom()
        expectText("Say hello.")
        type("Message", "hi all")
        tap("Send")
        awaitRequest("SendMyChatMessage")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasText("hi all")).assertExists()
    }

    @Test fun aRemovedLineGoes() {
        stub.cable.on("venueChat", joined(line("l1", "who's here?"), line("l2", "rude line")))
        showRoom()
        expectText("rude line")
        stub.cable.push("venueChat", """{"__typename":"ChatMessageRemoved","messageId":"l2"}""")
        compose.waitUntil(10_000) { !compose.hasText("rude line") }
        expectText("who's here?")
    }

    @Test fun backLeavesTheRoom() {
        stub.cable.on("venueChat", joined())
        showRoom()
        tap("Back")
        assertEquals(listOf("back"), navigator.opened)
    }
}
