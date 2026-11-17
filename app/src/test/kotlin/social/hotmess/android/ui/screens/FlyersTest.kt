package social.hotmess.android.ui.screens

import com.audiencekit.CommunityResource
import com.audiencekit.DigitalFlyer
import com.audiencekit.FlyersCardContent
import com.audiencekit.ResourceNextSession
import com.audiencekit.ResourceSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import social.hotmess.android.ui.StubApi
import java.time.OffsetDateTime
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FlyersTest {
    private val content = FlyersCardContent(
        disclosure = "Based on your city and the time, nothing else.",
        explainerLabel = "What's a digital flyer?",
        explainerUrl = "https://audiencekit.com/digital-flyers/",
        flyers = listOf(
            DigitalFlyer(id = "1", headline = "Pantry", category = "food", lines = listOf("a", "b")),
            DigitalFlyer(id = "2", headline = "Show", category = "community"),
            DigitalFlyer(id = "3", headline = "Testing", category = "health"),
        ),
    )

    @Test
    fun hidingStaysOnThePhoneAndResets() {
        val settings = StubApi().graph(signedIn = false).flyers
        settings.reset()
        settings.dismiss(content.flyers[1])
        settings.setHidden("health", true)
        assertEquals(listOf("1"), settings.visible(content, settings.dismissed.value, settings.hiddenCategories.value).map { it.id })

        settings.reset()
        assertEquals(listOf("1", "2", "3"), settings.visible(content, settings.dismissed.value, settings.hiddenCategories.value).map { it.id })
    }

    @Test
    fun aLineIsNeverShownTwiceRunning() {
        val settings = StubApi().graph(signedIn = false).flyers
        var last = settings.line(content.flyers[0])
        repeat(20) {
            val next = settings.line(content.flyers[0])
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun slotsAndBadgesInWords() {
        assertEquals("Thursday", ResourceWords.describe(ResourceSlot(days = listOf(4)), Locale.US))
        assertEquals("Every day · 9:00 AM–5:00 PM", ResourceWords.describe(ResourceSlot(from = "09:00", until = "17:00"), Locale.US).replace(' ', ' '))
        val pantry = CommunityResource(id = "r", title = "Pantry", name = "Pantry", facets = listOf("food", "no-id-required", "secular"))
        assertEquals(listOf("No ID needed", "Secular"), ResourceWords.badges(pantry))
    }

    @Test
    fun onNowWhileASessionIsUnderWay() {
        val now = OffsetDateTime.parse("2026-11-19T18:00:00-07:00")
        val resource = CommunityResource(
            id = "r", title = "Pantry", name = "Pantry",
            nextSession = ResourceNextSession(ResourceSlot(days = listOf(4)), "2026-11-19T17:00:00-07:00", "2026-11-19T20:00:00-07:00"),
        )
        assertEquals("On now", ResourceWords.nextSummary(resource, now))
    }
}
