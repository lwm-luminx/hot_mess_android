package social.hotmess.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSenderTest {
    private fun line(fields: String): VenueMessage =
        ChatProtocol.message(Json.parseToJsonElement("{$fields}"))!!

    @Test
    fun asksForTheSenderAndTheOwnMessageRemoval() {
        assertTrue(ChatProtocol.MESSAGE_FIELDS.contains("sender { kind }"))
        assertTrue(ChatProtocol.REMOVE_OWN_MESSAGE.contains("removeMyChatMessage(input: { messageId: \$messageId }) { deletedId }"))
    }

    @Test
    fun readsTheSenderKindTheServerSends() {
        val glam = line(""""id":"m-1","message":"Be kind tonight","userId":"u-0","name":"Glam","role":"VENUE","sender":{"kind":"BOT"}""")
        assertEquals(ChatSenderKind.BOT, glam.senderKind)
        assertEquals(ChatSenderKind.BOT, glam.sender)
        assertTrue(glam.isFromListing, "a bot has no presence and nobody to block")
        assertFalse(glam.opensProfile("me"))

        assertEquals(ChatSenderKind.MEMBER, line(""""message":"hi","userId":"u-1","sender":{"kind":"ROBOT_OVERLORD"}""").sender, "an unknown kind draws as a member")
        assertEquals(ChatSenderKind.MEMBER, line(""""message":"hi","userId":"u-1","sender":"odd"""").sender, "a malformed sender keeps the line")
    }

    @Test
    fun readsAPrivateLine() {
        assertTrue(ChatProtocol.MESSAGE_FIELDS.contains("isPrivate"))
        assertTrue(line(""""message":"Easy there.","userId":"u-0","sender":{"kind":"BOT"},"isPrivate":true"""").isPrivate)
        assertFalse(line(""""message":"hi","userId":"u-1","isPrivate":false"""").isPrivate)
        assertFalse(line(""""message":"hi","userId":"u-1"""").isPrivate, "an older server has no private lines")
    }

    @Test
    fun worksTheSenderOutOnAnOlderServer() {
        val plain = line(""""message":"hi","userId":"u-1"""")
        assertNull(plain.senderKind)
        assertEquals(ChatSenderKind.MEMBER, plain.sender)
        assertEquals(ChatSenderKind.VENUE, line(""""message":"Doors at 9","userId":"u-1","role":"VENUE","postedAsVenue":true""").sender)
        assertEquals(ChatSenderKind.PERSONA, line(""""message":"hi","userId":"u-1","role":"HOST","postedAsPerson":true,"person":{"id":"p-1"}""").sender)
        assertEquals(ChatSenderKind.SYSTEM, line(""""message":"q","userId":"u-0","kind":"PROMPT","postedAsAudience":true""").sender)
    }

    @Test
    fun previewsKnowABot() {
        assertTrue(Documents.CHAT_LINE_FIELDS.contains("sender { kind }"))
        val json = Json { ignoreUnknownKeys = true }
        val glam = json.decodeFromString(ChatLine.serializer(), """{"id":"m-1","message":"Be kind","userId":"u-0","sender":{"kind":"BOT"}}""")
        assertTrue(glam.isBot)
        assertTrue(glam.isFromListing)
        assertFalse(json.decodeFromString(ChatLine.serializer(), """{"id":"m-2","message":"hi","userId":"u-1"}""").isBot)
    }
}
