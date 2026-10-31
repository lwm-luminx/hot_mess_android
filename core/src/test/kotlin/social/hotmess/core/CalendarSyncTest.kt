package social.hotmess.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarSyncTest {
    private val start = Instant.parse("2026-10-31T03:00:00Z")

    @Test
    fun fillsTheEntryFromTheEventWithThreeHoursWhenItHasNoEnd() {
        val event = HotMessApi.json.decodeFromString(
            Event.serializer(),
            """{"id":"0A1E0AAC-1D01-42D1-9DE5-F0279842D9C0","name":"Drag Bingo","startAt":"2026-10-31T03:00:00Z",
               "venue":{"venue":{"id":"v1","name":"The Stud","profile":{"address":"1123 Folsom St"}}}}""",
        )
        val entry = CalendarEntry.from(event)
        assertEquals("Drag Bingo", entry.title)
        assertEquals(start.plusSeconds(3 * 3600), entry.end)
        assertEquals("The Stud, 1123 Folsom St", entry.location)
        assertEquals("https://hotmess.social/events/0a1e0aac-1d01-42d1-9de5-f0279842d9c0", entry.url)
    }

    @Test
    fun keepsTheEventsOwnEndAndNoLocationWithoutAVenue() {
        val entry = CalendarEntry.from(Event(id = "e1", name = "Late", startAt = start, endAt = start.plusSeconds(5 * 3600)))
        assertEquals(start.plusSeconds(5 * 3600), entry.end)
        assertNull(entry.location)
    }

    @Test
    fun asksForTheSubscriptionLink() {
        assertTrue(Documents.CREATE_CALENDAR_FEED.contains("createCalendarFeed(input: { reset: \$reset }) { calendarFeed { url webcalUrl googleUrl } }"))
        assertTrue(Documents.CALENDAR_FEED.contains("me { calendarFeed { url webcalUrl googleUrl } }"))
    }
}
