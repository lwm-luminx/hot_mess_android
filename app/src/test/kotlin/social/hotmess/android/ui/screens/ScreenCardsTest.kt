package social.hotmess.android.ui.screens

import com.audiencekit.CardLayout
import com.audiencekit.Coordinates
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCardsTest {
    private val nyne = Coordinates(47.6575451, -117.414777)
    private val server = CardLayout(id = "server", cards = emptyList(), refreshAfter = 60)
    private val builtIn = CardLayout.fallback(listOf("builtIn"))

    @Test
    fun aFailingServerIsNotAskedAgainOnEveryRefresh() = runTest {
        var now = 0L
        var calls = 0
        val cards = ScreenCards(listOf("builtIn"), nowMs = { now }) { _, _ -> calls += 1; builtIn }

        repeat(50) {
            cards.load(nyne, "locale")
            now += 1_000
        }
        // 50 seconds of refreshes: the first ask, then one after 15 s and one after another 30 s.
        assertEquals(3, calls)
        assertTrue(cards.layout.value.isFallback)
    }

    @Test
    fun concurrentLoadsShareOneAsk() = runTest {
        var calls = 0
        val cards = ScreenCards(listOf("builtIn"), nowMs = { 0L }) { _, _ ->
            calls += 1
            delay(100)
            server
        }

        listOf(
            async { cards.load(nyne, "locale", force = true) },
            async { cards.load(nyne, "locale", force = true) },
            async { cards.load(nyne, "locale") },
        ).awaitAll()

        assertEquals(1, calls)
        assertEquals("server", cards.layout.value.id)
    }

    @Test
    fun aFailureKeepsTheServersLastOrder() = runTest {
        var succeed = true
        var calls = 0
        val cards = ScreenCards(listOf("builtIn"), nowMs = { 0L }) { _, _ ->
            calls += 1
            if (succeed) server else builtIn
        }

        cards.load(nyne, "locale")
        assertEquals("server", cards.layout.value.id)

        succeed = false
        cards.load(nyne, "locale", force = true)
        assertEquals(2, calls)
        assertEquals("server", cards.layout.value.id)
    }
}
