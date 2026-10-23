package social.hotmess.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatProfileTest {
    private class MemoryStorage : StringSetStorage {
        val values = mutableMapOf<String, Set<String>>()
        override fun get(key: String): Set<String> = values[key].orEmpty()
        override fun put(key: String, values: Set<String>) {
            this.values[key] = values
        }
    }

    private val room = ChatRoom.venue("V-1")
    private val other = ChatRoom.venue("v-2")

    @Test
    fun onlyPeoplePostingAsThemselvesOpenAProfile() {
        assertTrue(VenueMessage(body = "hi", userId = "u-1").opensProfile("me"))
        assertTrue(VenueMessage(body = "hi", userId = "u-1", role = ChatRole.STAFF).opensProfile("me"))
        assertFalse(VenueMessage(body = "hi", userId = "ME").opensProfile("me"), "your own lines")
        assertFalse(VenueMessage(body = "hi", userId = "u-1", role = ChatRole.VENUE).opensProfile("me"), "the venue as itself")
        assertFalse(VenueMessage(body = "hi", userId = "u-1", role = ChatRole.HOST, personId = "p-1").opensProfile("me"), "talent as their listing")
        assertFalse(VenueMessage(body = "hi", userId = "u-1", postedAsAudience = true).opensProfile("me"), "the audience's prompts")
        assertFalse(VenueMessage(body = "hi", userId = "u-1", kind = ChatKind.PROMPT).opensProfile("me"))
    }

    @Test
    fun friendStateAfterARequest() {
        assertEquals(FriendState.REQUESTED, FriendState.afterRequest("PENDING"))
        assertEquals(FriendState.REQUESTED, FriendState.afterRequest("DISMISSED"))
        assertEquals(FriendState.FRIENDS, FriendState.afterRequest("accepted"))
    }

    @Test
    fun mutesArePerRoomAndKept() {
        val storage = MemoryStorage()
        val mutes = ChatMutes(storage)
        mutes.setMuted(room, "U-9", true)
        assertTrue(mutes.isMuted(room, "u-9"))
        assertFalse(mutes.isMuted(other, "u-9"), "only in that room")
        assertFalse(mutes.isMuted(ChatRoom.locale("v-1"), "u-9"), "a locale's room is another room")
        assertEquals(setOf("u-9"), storage.values[ChatMutes.key(room)])

        // A new store (the app opened again) reads it back.
        val again = ChatMutes(storage)
        assertTrue(again.isMuted(room, "u-9"))
        again.setMuted(room, "u-9", false)
        assertFalse(again.isMuted(room, "u-9"))
        assertEquals(emptySet(), storage.values[ChatMutes.key(room)])
    }

    @Test
    fun mutedPeoplesLinesAreHidden() {
        val lines = listOf(
            VenueMessage(id = "1", body = "hi", userId = "u-9"),
            VenueMessage(id = "2", body = "hey", userId = "u-1"),
            VenueMessage(id = "3", body = "Venue news", userId = "u-9", role = ChatRole.VENUE),
        )
        assertEquals(listOf("2", "3"), ChatMutes.visible(lines, setOf("u-9")).map { it.id })
        assertEquals(lines, ChatMutes.visible(lines, emptySet()))
    }

    @Test
    fun promptLabelsJumpToTheirPrompt() {
        fun prompt(id: String, lineId: String, shownAs: ChatPromptShownAs) = VenueMessage(
            id = lineId, body = "q", userId = "aud", kind = ChatKind.PROMPT, postedAsAudience = true,
            prompt = ChatPrompt(id = id, text = "q", shownAs = shownAs),
        )
        val lines = listOf(
            prompt("p-1", "m-1", ChatPromptShownAs.PINNED),
            prompt("p-2", "m-2", ChatPromptShownAs.CARD),
            prompt("p-3", "m-3", ChatPromptShownAs.PINNED),
            VenueMessage(id = "m-4", body = "a", userId = "u-1", promptId = "p-1"),
        )
        assertEquals(PromptJump.Bar, PromptJump.to("p-3", lines), "the current pinned prompt")
        assertEquals(PromptJump.Line("m-2"), PromptJump.to("p-2", lines), "a card in the thread")
        assertEquals(PromptJump.Line("m-1"), PromptJump.to("p-1", lines), "an older pinned prompt in history")
        assertNull(PromptJump.to("p-9", lines), "not loaded")
        assertNull(PromptJump.to(null, lines))
    }
}
