package social.hotmess.core

/** A titled run of events on the Events tab. */
data class EventSection(val id: String, val title: String, val events: List<Event>)

data class EventListing(val sections: List<EventSection> = emptyList()) {
    val isEmpty: Boolean get() = sections.all { it.events.isEmpty() }

    /** Every event across the sections, once each, soonest first. */
    val allEvents: List<Event> get() = sections.flatMap { it.events }.distinctBy { it.id }.sortedBy { it.startAt }

    companion object {
        /**
         * Splits upcoming events the way the iOS app does: up to two events with a cover photo as
         * "Featured", then every other event, soonest first, as "Upcoming", so none shows twice.
         */
        fun upcoming(events: List<Event>): EventListing {
            val sorted = events.sortedBy { it.startAt }
            val featured = sorted.filter { it.bannerUrl != null }.take(2)
            val featuredIds = featured.map { it.id }.toSet()
            val upcoming = sorted.filterNot { it.id in featuredIds }
            return EventListing(
                listOf(
                    EventSection(id = "featured", title = "Featured", events = featured),
                    EventSection(id = "upcoming", title = "Upcoming", events = upcoming),
                ).filter { it.events.isNotEmpty() },
            )
        }
    }
}
