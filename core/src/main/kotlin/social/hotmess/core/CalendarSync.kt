package social.hotmess.core

import java.time.Duration
import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * The user's private calendar subscription (API doc/CALENDAR_SYNC.md): the events they're going to, and might go to,
 * as a feed their calendar app keeps up with. [googleUrl] adds it to Google Calendar; [webcalUrl] suits other apps.
 */
@Serializable
data class CalendarFeed(val url: String, val webcalUrl: String, val googleUrl: String)

/** What "Add to calendar" puts in the user's calendar for an event: a copy, which later changes don't follow. */
data class CalendarEntry(
    val title: String,
    val start: Instant,
    val end: Instant,
    val location: String?,
    val url: String,
) {
    companion object {
        /** How long an event with no end runs, as the API's calendar feed does. */
        val DEFAULT_LENGTH: Duration = Duration.ofHours(3)

        fun from(event: Event): CalendarEntry {
            val end = event.endAt?.takeIf { it.isAfter(event.startAt) } ?: event.startAt.plus(DEFAULT_LENGTH)
            val location = event.venue?.let { venue ->
                listOfNotNull(venue.name.ifBlank { null }, venue.address?.ifBlank { null }).joinToString(", ").ifBlank { null }
            }
            return CalendarEntry(event.name, event.startAt, end, location, "https://hotmess.social${event.sharePath}")
        }
    }
}
