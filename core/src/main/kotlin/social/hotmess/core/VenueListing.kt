package social.hotmess.core

/**
 * The Venues list as paged in so far, then [next] after it: each venue once (a venue that moved between pages while
 * paging stays where it was first seen), and [next]'s cursor.
 */
operator fun VenuePage.plus(next: VenuePage): VenuePage =
    VenuePage((venues + next.venues).distinctBy(Venue::listKey), next.nextCursor)

/**
 * The ids in [wanted] (a card's venues) that neither [have] nor [asked] already covers, each once: the venues a card
 * points at that the list hasn't paged in and nobody has looked up yet.
 */
fun missingVenueIds(wanted: Iterable<String>, have: Iterable<Venue>, asked: Set<String> = emptySet()): List<String> {
    val known = have.mapTo(mutableSetOf(), Venue::listKey) + asked.map(::venueKey)
    return wanted.filter { venueKey(it) !in known }.distinctBy(::venueKey)
}

/** A venue id as the list compares them, whatever case it comes in. */
fun venueKey(id: String): String = RecordId.normalize(id) ?: id.lowercase()

private val Venue.listKey: String get() = venueKey(id)
