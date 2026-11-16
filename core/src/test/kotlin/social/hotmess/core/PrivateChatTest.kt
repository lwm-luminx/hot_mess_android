package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateChatTest {
    private fun api(response: String): HotMessApi {
        val transport = HttpTransport { HttpResponse(200, response.toByteArray()) }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client)
    }

    private val line = """{"id":"ping-p1.l1","chatId":"ping-p1","body":"On my way","isMine":false,
        "sentAt":"2026-11-16T23:00:00Z","sender":{"id":"u2","name":"Ada L."}}"""

    @Test
    fun readsAPingsChat() = runTest {
        val chat = api("""{"data":{"node":{"id":"p1","chat":{"id":"ping-p1","closesAt":"2026-11-17T05:00:00Z","messages":[$line]}}}}""")
            .pingChat("p1")
        assertEquals("ping-p1", chat?.id)
        assertTrue(chat!!.isPing)
        assertEquals("On my way", chat.messages.single().body)
        assertEquals("Ada L.", chat.messages.single().sender?.name)
    }

    @Test
    fun noChatIsNull() = runTest {
        assertNull(api("""{"data":{"node":{"id":"p1","chat":null}}}""").pingChat("p1"))
        assertNull(api("""{"data":{"venues":{"venue":{"pairUp":{"mine":{"match":null}}}}}}""").quickieChat("v1"))
    }

    @Test
    fun readsAQuickieChat() = runTest {
        val chat = api(
            """{"data":{"venues":{"venue":{"pairUp":{"mine":{"match":{"id":"m1",
              "chat":{"id":"quickie-m1","closesAt":"2026-11-17T05:00:00Z","messages":[]}}}}}}}}""",
        ).quickieChat("v1")
        assertEquals("quickie-m1", chat?.id)
    }

    @Test
    fun sendsALine() = runTest {
        val sent = api("""{"data":{"sendPrivateChatMessage":{"message":$line}}}""").sendPrivateChatMessage("ping-p1", "On my way")
        assertEquals("ping-p1.l1", sent.id)
    }

    @Test
    fun readsLinesAndClosingsFromTheCable() {
        val message = """{"message":{"result":{"data":{"privateChat":{"chatId":"ping-p1","closed":false,"message":$line}}},"more":true}}"""
        val frame = PrivateChatProtocol.frames(GraphQLCable.parse(message)).single()
        assertEquals("On my way", (frame as PrivateChatProtocol.Frame.Message).message.body)

        val closed = """{"message":{"result":{"data":{"privateChat":{"chatId":"ping-p1","closed":true,"message":null}}},"more":true}}"""
        assertEquals(PrivateChatProtocol.Frame.Closed("ping-p1"), PrivateChatProtocol.frames(GraphQLCable.parse(closed)).single())

        val first = """{"message":{"result":{"data":null},"more":true}}"""
        assertTrue(PrivateChatProtocol.frames(GraphQLCable.parse(first)).isEmpty())

        val refused = """{"message":{"result":{"data":null,"errors":[{"message":"no"}]},"more":false}}"""
        assertEquals(PrivateChatProtocol.Frame.Rejected, PrivateChatProtocol.frames(GraphQLCable.parse(refused)).single())
    }

    @Test
    fun mergesLinesOnceInOrder() {
        fun at(id: String, second: Long) =
            PrivateChatMessage(id, "ping-p1", id, sentAt = Instant.ofEpochSecond(second))
        val merged = PrivateChatLines.merge(listOf(at("b", 2), at("a", 1)), listOf(at("c", 3), at("b", 2)))
        assertEquals(listOf("a", "b", "c"), merged.map { it.id })
    }
}
