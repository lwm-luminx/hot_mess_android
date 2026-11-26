package social.hotmess.android.ui.screens

import com.audiencekit.CommunityDirectory
import com.audiencekit.CommunityResource
import com.audiencekit.DirectoryLocale
import com.audiencekit.DigitalFlyer
import com.audiencekit.FlyersCardContent
import com.audiencekit.ResourceNeed
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

    private val browsed = CommunityDirectory(
        locale = DirectoryLocale("l1", "Salt Lake"),
        needs = listOf(ResourceNeed("food", "Food", listOf("food")), ResourceNeed("shelter", "Shelter", listOf("shelter")), ResourceNeed("safe", "Safety at home", listOf("dv-stalking"))),
        resources = listOf(
            CommunityResource(id = "1", title = "Calvary pantry", name = "Calvary pantry", description = "Thursday groceries.", facets = listOf("food", "no-id-required"), address = "460 W Century Dr"),
            CommunityResource(id = "2", title = "Crisis Care Center", name = "Crisis Care Center", facets = listOf("crisis", "mental-health")),
            CommunityResource(id = "3", title = "Support line", name = "YWCA Utah", sensitive = true, facets = listOf("dv-stalking", "crisis")),
        ),
    )

    @Test
    fun theBrowserOffersOnlyNeedsWithSomethingListedAndPutsCrisisLinesFirst() {
        assertEquals(listOf("food", "safe"), ResourceBrowser.offeredNeeds(browsed).map { it.key })
        val all = ResourceBrowser.results(browsed, null, "")
        assertEquals(listOf("2", "3"), all.urgent.map { it.id })
        assertEquals(listOf("1"), all.rest.map { it.id })
        val food = ResourceBrowser.results(browsed, browsed.needs[0], "")
        assertEquals(emptyList<String>(), food.urgent.map { it.id })
        assertEquals(listOf("1"), food.rest.map { it.id })
    }

    @Test
    fun searchRunsOverNamesDescriptionsKindsBadgesAndAddresses() {
        fun found(search: String) = ResourceBrowser.results(browsed, null, search).let { it.urgent + it.rest }.map { it.id }
        assertEquals(listOf("1"), found("  thursday FOOD "))
        assertEquals(listOf("1"), found("century"))
        assertEquals(listOf("3"), found("ywca"))
        assertEquals(emptyList<String>(), found("food shelter"))
        assertEquals(true, ResourceBrowser.results(browsed, null, "zzzz").isEmpty)
    }

    @Test
    fun aDvResourceStaysNeutralAndNumbersReadAndDialProperly() {
        assertEquals(listOf("Crisis support", "Mental health"), ResourceBrowser.kinds(browsed.resources[1]))
        assertEquals(emptyList<String>(), ResourceBrowser.kinds(browsed.resources[2]))
        assertEquals("(801) 264-9999", ResourceBrowser.phoneText("8012649999"))
        assertEquals("(801) 537-8600", ResourceBrowser.phoneText("+18015378600"))
        assertEquals("988", ResourceBrowser.phoneText("988"))
        assertEquals("+442079460000", ResourceBrowser.phoneText("+442079460000"))
        assertEquals("tel:+18015378600", ResourceBrowser.dialUri("+18015378600"))
        assertEquals("smsto:988", ResourceBrowser.dialUri("988", "smsto"))
    }

    @Test
    fun daysInARowReadAsARange() {
        assertEquals("Monday–Friday", ResourceWords.dayText(listOf(1, 2, 3, 4, 5), Locale.US))
        assertEquals("Tuesday, Thursday", ResourceWords.dayText(listOf(2, 4), Locale.US))
        assertEquals("Every day", ResourceWords.dayText(listOf(0, 1, 2, 3, 4, 5, 6), Locale.US))
    }
}
