package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatPromptsTest {
    private val venue = "8c2b7c86-5b6e-4a52-9f0f-1d1c1e6f2a10"
    private val utc = ZoneId.of("UTC")

    private fun posted(fields: String): VenueMessage {
        val text = """{"identifier":"x","message":{"result":{"data":{"venueChat":""" +
            """{"__typename":"ChatMessagePosted","senderPresence":null,"message":{$fields}}}},"more":true}}"""
        return assertIs<ChatProtocol.Frame.Message>(ChatProtocol.parse(text).single()).message
    }

    @Test
    fun asksForPromptsAndAnswersOnEveryLine() {
        val fields = ChatProtocol.MESSAGE_FIELDS
        assertTrue(fields.contains("postedAsAudience"))
        assertTrue(fields.contains("promptId"))
        assertTrue(fields.contains("prompt { id kind text answer revealAt shownAs number total nextAt }"))
        assertTrue(ChatProtocol.VENUE_ROOM_DOCUMENT.contains("prompt { id kind"))
        assertTrue(Documents.CHAT_LINE_FIELDS.contains("postedAsAudience promptId"))
    }

    @Test
    fun decodesAPinnedPromptPostedAsTheAudience() {
        val line = posted(
            """"id":"m-1","message":"Who should have won last season?","userId":"u-0","name":"Hot Mess","kind":"PROMPT",
              "postedAsAudience":true,"sentAt":"2026-10-24T03:30:00Z",
              "prompt":{"id":"p-2","kind":"CONVERSATION","text":"Who should have won last season? Find someone near you who disagrees.",
                "answer":null,"revealAt":null,"shownAs":"PINNED","number":2,"total":6,"nextAt":"2026-10-24T04:15:00Z"}""",
        )
        assertEquals(ChatKind.PROMPT, line.kind)
        assertTrue(line.kind.isRich)
        assertTrue(line.postedAsAudience)
        assertTrue(line.isPostedAsAudience)
        assertTrue(line.isFromListing, "a prompt has no presence or friend's name")
        val prompt = line.prompt!!
        assertEquals("p-2", prompt.id)
        assertEquals(ChatPromptShownAs.PINNED, prompt.shownAs)
        assertEquals("Tonight's prompt · 2 of 6", prompt.overline)
        assertEquals("Prompt", prompt.answerLabel)
        assertEquals(Instant.parse("2026-10-24T04:15:00Z"), prompt.nextAt)
        assertEquals(
            "14 answers · Next one at 4:15am",
            ChatPrompts.footer(prompt, 14, at = Instant.parse("2026-10-24T03:40:00Z"), zone = utc),
        )
        assertEquals("1 answer", ChatPrompts.footer(prompt, 1, at = Instant.parse("2026-10-24T05:00:00Z"), zone = utc))
        assertNull(ChatPrompts.footer(prompt.copy(nextAt = null), 0))
    }

    @Test
    fun decodesTriviaWithItsHiddenAnswer() {
        val line = posted(
            """"id":"m-2","message":"Who won season 4?","userId":"u-0","kind":"PROMPT","postedAsAudience":true,
              "prompt":{"id":"p-3","kind":"TRIVIA","text":"Who won season 4?","answer":"Sharon Needles",
                "revealAt":"2026-10-24T04:00:00Z","shownAs":"PINNED","number":3,"total":6}""",
        )
        val prompt = line.prompt!!
        assertTrue(prompt.isTrivia)
        assertEquals("Trivia · 3 of 6", prompt.overline)
        assertEquals("Trivia", prompt.answerLabel)
        assertEquals("Sharon Needles", prompt.answer)
        assertFalse(prompt.isRevealed(Instant.parse("2026-10-24T03:59:59Z")))
        assertTrue(prompt.isRevealed(Instant.parse("2026-10-24T04:00:00Z")))
        assertFalse(prompt.copy(revealAt = null).isRevealed(Instant.parse("2027-01-01T00:00:00Z")), "no reveal time: only a tap shows it")
    }

    @Test
    fun decodesAnAnswer() {
        val line = posted(""""id":"m-3","message":"Plane Jane","userId":"u-1","name":"Jordan P.","kind":"TEXT","promptId":"p-2","postedAsAudience":false""")
        assertEquals("p-2", line.promptId)
        assertEquals(ChatKind.TEXT, line.kind)
        assertFalse(line.isFromListing)
    }

    @Test
    fun toleratesOlderServersAndOddFrames() {
        // No prompt fields at all, as today's server sends.
        val plain = posted(""""id":"m-4","message":"hi","userId":"u-1"""")
        assertNull(plain.prompt)
        assertNull(plain.promptId)
        assertFalse(plain.postedAsAudience)

        // A kind this app doesn't know reads as text.
        assertEquals(ChatKind.TEXT, posted(""""id":"m-5","message":"Requested a song","userId":"u-1","kind":"SOMETHING_NEW"""").kind)

        // A malformed prompt drops only the prompt; unknown prompt kinds and shownAs fall back.
        val bad = posted(""""id":"m-6","message":"Talk to someone","userId":"u-0","kind":"PROMPT","prompt":{"id":"p-9","number":"two"}""")
        assertEquals("m-6", bad.id)
        assertNull(bad.prompt, "a prompt needs its text")
        assertTrue(bad.isPostedAsAudience, "a PROMPT line is the audience's even when the server doesn't say so")
        val odd = posted(
            """"id":"m-7","message":"Ask whoever is next to you","userId":"u-0","kind":"prompt",
              "prompt":{"id":"p-10","text":"Ask whoever is next to you","kind":"RIDDLE","shownAs":"BANNER","number":"x","total":6}""",
        ).prompt!!
        assertEquals(ChatPromptKind.CONVERSATION, odd.kind)
        assertEquals(ChatPromptShownAs.CARD, odd.shownAs)
        assertNull(odd.number)
        assertEquals("Tonight's prompt", odd.overline)
    }

    @Test
    fun findsTheNewestPinnedPromptAndCountsItsAnswers() {
        fun prompt(id: String, shownAs: ChatPromptShownAs) =
            VenueMessage(id = "m-$id", body = id, userId = "u-0", kind = ChatKind.PROMPT, prompt = ChatPrompt(id, "Prompt $id", shownAs = shownAs))
        val messages = listOf(
            prompt("p-1", ChatPromptShownAs.PINNED),
            VenueMessage(id = "a-1", body = "x", userId = "u-1", promptId = "p-1"),
            prompt("p-2", ChatPromptShownAs.PINNED),
            prompt("p-3", ChatPromptShownAs.CARD),
            VenueMessage(id = "a-2", body = "y", userId = "u-2", promptId = "p-2"),
            VenueMessage(id = "a-3", body = "z", userId = "u-3", promptId = "p-2"),
            VenueMessage(id = "t-1", body = "hi", userId = "u-4"),
        )
        assertEquals("m-p-2", ChatPrompts.pinned(messages)?.id, "a card never takes the bar")
        assertEquals(2, ChatPrompts.answerCount(messages, "p-2"))
        assertEquals("Prompt", ChatPrompts.label("p-2", messages))
        assertEquals("Prompt", ChatPrompts.label("p-gone", messages), "an answer to a prompt not in view is still labelled")
        assertNull(ChatPrompts.label(null, messages))
        assertNull(ChatPrompts.pinned(messages.filter { it.kind != ChatKind.PROMPT }))
    }

    @Test
    fun sendsThePromptAnAnswerIsFor() {
        assertEquals(
            """{"venueId":"$venue","message":"Team Sapphira","promptId":"p-2"}""",
            ChatProtocol.sendInput(ChatRoom.venue(venue.uppercase()), "Team Sapphira", promptId = "p-2").toString(),
        )
        assertEquals("""{"venueId":"$venue","message":"hi"}""", ChatProtocol.sendInput(ChatRoom.venue(venue), "hi", promptId = " ").toString())
        assertEquals("""{"localeId":"l-1","message":"hi"}""", ChatProtocol.sendInput(ChatRoom.locale("l-1"), "hi", promptId = "p-2").toString())
    }

    @Test
    fun decodesAPreviewLineFromTheAudience() {
        val line = HotMessApi.json.decodeFromString(
            ChatLine.serializer(),
            """{"id":"m-1","message":"Who should have won?","userId":"u-0","name":"Hot Mess","kind":"PROMPT","postedAsAudience":true}""",
        )
        assertTrue(line.isPostedAsAudience)
        assertTrue(line.isFromListing)
        val answer = HotMessApi.json.decodeFromString(ChatLine.serializer(), """{"id":"m-2","message":"x","userId":"u-1","promptId":"p-1"}""")
        assertEquals("p-1", answer.promptId)
        assertFalse(answer.isFromListing)
    }

    private fun api(vararg responses: Pair<String, String>): Pair<HotMessApi, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { request ->
            val body = request.body?.toString(Charsets.UTF_8).orEmpty()
            bodies += body
            val match = responses.firstOrNull { body.contains(it.first) }
                ?: return@HttpTransport com.audiencekit.HttpResponse(404, ByteArray(0))
            com.audiencekit.HttpResponse(200, match.second.toByteArray())
        }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client) to bodies
    }

    @Test
    fun sendsAnAnswerWithItsPrompt() = runTest {
        val (api, bodies) = api(
            "SendMyChatMessage" to """{"data":{"sendMyChatMessage":{"message":{"id":"m-9","message":"Plane Jane","userId":"u-1","kind":"TEXT","promptId":"p-2"}}}}""",
        )
        val line = api.sendChatMessage(ChatRoom.venue("V-1"), "Plane Jane", promptId = "p-2")

        assertEquals("p-2", line?.promptId)
        val body = HotMessApi.json.parseToJsonElement(bodies.single()).jsonObject
        assertEquals("""{"input":{"venueId":"v-1","message":"Plane Jane","promptId":"p-2"}}""", body["variables"].toString())
    }

    @Test
    fun readsAndSetsChatInitials() = runTest {
        val (api, bodies) = api(
            "SetChatInitials" to """{"data":{"setChatInitials":{"chatInitials":true}}}""",
            "query ChatInitials" to """{"data":{"me":{"chatInitials":false}}}""",
        )
        assertEquals(false, api.chatInitials())
        assertEquals(true, api.setChatInitials(true))
        assertTrue(Documents.CHAT_INITIALS.contains("me { chatInitials }"))
        assertTrue(Documents.SET_CHAT_INITIALS.contains("setChatInitials(input: { enabled: \$enabled }) { chatInitials }"))
        val body = HotMessApi.json.parseToJsonElement(bodies.last()).jsonObject
        assertEquals("""{"enabled":true}""", body["variables"].toString())
    }

    @Test
    fun signedOutHasNoChatInitials() = runTest {
        val (api, _) = api("query ChatInitials" to """{"data":{"me":null}}""")
        assertNull(api.chatInitials())
    }
}
