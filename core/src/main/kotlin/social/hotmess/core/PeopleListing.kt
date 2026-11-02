package social.hotmess.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** A locale by its ID alone. */
@Serializable
data class LocaleRef(val id: String)

/** A venue a person is tied to, as the People tab needs it: its city and your friends out there now. */
@Serializable
data class PersonPlace(
    val name: String? = null,
    val locale: LocaleRef? = null,
    @SerialName("friends") private val friendsMixin: FriendsList? = null,
) {
    val friends: List<Friend> get() = friendsMixin?.list?.nodes.orEmpty().filterNotNull()

    @Serializable
    data class FriendsList(val list: Connection<Friend>? = null)
}

/** A person's venues mixin (`venues { home }`): their home venue, when they have one. */
@Serializable
data class PersonVenues(val home: PersonPlace? = null)

/** One of a person's events, as much as "Playing this week" needs: when, which city and where. */
@Serializable
data class PersonGigEvent(
    @Serializable(with = InstantSerializer::class) val startAt: Instant? = null,
    val locale: LocaleRef? = null,
    /** The events mixin: a display name, and the venue when there is one. */
    val venue: Venue? = null,
) {
    @Serializable
    data class Venue(val name: String? = null, val venue: PersonPlace? = null)
}

/** A person's events mixin (`events { involvements }`): every event they're on. */
@Serializable
data class PersonGigs(val involvements: Connection<PersonGigEvent>? = null)

/** A gig of a person's: when, in which locale, and the venue's name. */
data class Gig(val date: Instant, val localeId: String?, val venueName: String?)

/**
 * The People tab's list, as iOS's PeopleListing: everyone, or who's local to one city: people playing it this
 * week (soonest gig first), then the ones based or featured there who aren't.
 */
data class PeopleListing(val everyone: List<Person>) {
    data class Playing(val person: Person, val gig: Gig)

    data class Local(val playing: List<Playing>, val based: List<Person>) {
        val isEmpty: Boolean get() = playing.isEmpty() && based.isEmpty()
    }

    fun local(localeId: String, now: Instant = Instant.now()): Local {
        val earliest = now.minus(GRACE)
        val latest = now.plus(WINDOW)
        val playing = everyone.mapNotNull { person ->
            person.gigs.firstOrNull { RecordId.same(it.localeId, localeId) && it.date >= earliest && it.date <= latest }
                ?.let { Playing(person, it) }
        }.sortedBy { it.gig.date }
        val playingIds = playing.mapTo(HashSet()) { it.person.id }
        val based = everyone.filter { person ->
            person.id !in playingIds && person.localeIds.any { RecordId.same(it, localeId) }
        }
        return Local(playing, based)
    }

    companion object {
        /** How far ahead "Playing this week" looks. */
        val WINDOW: Duration = Duration.ofDays(7)

        /** How far back a gig that started tonight still counts. */
        val GRACE: Duration = Duration.ofHours(6)
    }
}
