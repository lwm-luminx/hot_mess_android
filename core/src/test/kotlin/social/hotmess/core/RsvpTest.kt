package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Picking an RSVP on the event screen: what's shown, what's saved, and what happens when the save fails. */
class RsvpChangeTest {
    private val shown = mutableListOf<Rsvp>()
    private val saved = mutableListOf<Rsvp>()

    @Test
    fun showsTheNewChoiceAndSavesIt() = runTest {
        val message = RsvpChange.choose(Rsvp.UNSURE, Rsvp.ATTENDING, shown::add) { saved += it }

        assertNull(message)
        assertEquals(listOf(Rsvp.ATTENDING), shown)
        assertEquals(listOf(Rsvp.ATTENDING), saved)
    }

    @Test
    fun everyChoiceThePickerOffersIsSavedAsIs() = runTest {
        Rsvp.selectable.forEach { choice -> RsvpChange.choose(Rsvp.UNSURE, choice, shown::add) { saved += it } }

        assertEquals(Rsvp.selectable, saved)
    }

    @Test
    fun theChoiceIsOnScreenBeforeTheSaveFinishes() = runTest {
        val answer = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            RsvpChange.choose(Rsvp.MAYBE, Rsvp.DECLINED, shown::add) { answer.await() }
        }

        assertEquals(listOf(Rsvp.DECLINED), shown, "shown while the save is still waiting")
        answer.complete(Unit)
        job.join()
        assertEquals(listOf(Rsvp.DECLINED), shown, "nothing changes once it saves")
    }

    @Test
    fun pickingTheAnswerYouAlreadyGaveDoesNothing() = runTest {
        val message = RsvpChange.choose(Rsvp.MAYBE, Rsvp.MAYBE, shown::add) { saved += it }

        assertNull(message)
        assertTrue(shown.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun pickingBeforeTheEventLoadsDoesNothing() = runTest {
        RsvpChange.choose(null, Rsvp.ATTENDING, shown::add) { saved += it }

        assertTrue(shown.isEmpty())
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aFailedSavePutsTheOldAnswerBackAndSaysWhy() = runTest {
        val message = RsvpChange.choose(Rsvp.MAYBE, Rsvp.ATTENDING, shown::add) { throw IOException("reset") }

        assertEquals(listOf(Rsvp.ATTENDING, Rsvp.MAYBE), shown)
        assertEquals(ApiError.Offline.message, message)
    }

    @Test
    fun aRefusalShowsTheApisOwnWords() = runTest {
        val message = RsvpChange.choose(Rsvp.UNSURE, Rsvp.ATTENDING, shown::add) {
            throw ApiError.Rejected("CONFLICT", "This event has ended")
        }

        assertEquals("This event has ended", message)
        assertEquals(Rsvp.UNSURE, shown.last())
    }

    @Test
    fun aFailureWithoutAMessageStillSaysSomething() = runTest {
        val message = RsvpChange.choose(Rsvp.UNSURE, Rsvp.DECLINED, shown::add) { throw IllegalStateException() }

        assertEquals("Something went wrong.", message)
    }

    @Test
    fun aCancelledSaveStaysCancelledAndLeavesTheScreenAlone() = runTest {
        assertFailsWith<CancellationException> {
            RsvpChange.choose(Rsvp.UNSURE, Rsvp.MAYBE, shown::add) { throw CancellationException("left the screen") }
        }
        assertEquals(listOf(Rsvp.MAYBE), shown, "the old answer isn't put back")
    }
}

/** What the app sends for an RSVP, and how it reads one back. */
class RsvpApiTest {
    private fun api(response: String): Pair<HotMessApi, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { request ->
            bodies += request.body?.toString(Charsets.UTF_8).orEmpty()
            HttpResponse(200, response.toByteArray())
        }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client) to bodies
    }

    private fun answered(state: String) =
        """{"data":{"rsvpEvent":{"event":{"id":"e-1","rsvp":{"count":1,"mine":"$state","mineSource":"RSVP"}}}}}"""

    @Test
    fun sendsTheEventAndTheChoice() = runTest {
        Rsvp.selectable.forEach { choice ->
            val (api, bodies) = api(answered(choice.name))
            api.setRsvp(choice, "e-1")

            val body = HotMessApi.json.parseToJsonElement(bodies.single()).jsonObject
            val variables = body["variables"]!!.jsonObject
            assertEquals("e-1", variables["eventId"]!!.jsonPrimitive.content)
            assertEquals(choice.name, variables["state"]!!.jsonPrimitive.content)
            assertTrue(body["query"]!!.jsonPrimitive.content.contains("rsvpEvent"))
        }
    }

    @Test
    fun aRefusedRsvpIsRejectedWithTheApisWords() = runTest {
        val (api, _) = api(
            """{"data":{"rsvpEvent":null},"errors":[{"message":"Sign in to RSVP","extensions":{"code":"UNAUTHENTICATED"}}]}""",
        )
        assertFailsWith<ApiError.Unauthorized> { api.setRsvp(Rsvp.ATTENDING, "e-1") }
    }

    @Test
    fun anEventsOtherFieldsSurviveAChangedAnswer() {
        val event = HotMessApi.json.decodeFromString(
            Event.serializer(),
            """{"id":"e-1","name":"Drag Brunch","startAt":"2026-10-10T04:00:00Z","rsvp":{"mine":"MAYBE"},
               "venue":{"name":"The Wildrose","venue":{"id":"v-1","name":"The Wildrose"}}}""",
        )
        val changed = event.withRsvp(Rsvp.ATTENDING)

        assertEquals(Rsvp.ATTENDING, changed.rsvp)
        assertEquals(Rsvp.MAYBE, event.rsvp)
        assertEquals(event.name, changed.name)
        assertEquals(event.venue, changed.venue)
        assertEquals(event.startAt, changed.startAt)
    }
}
