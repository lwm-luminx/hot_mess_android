package social.hotmess.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * What the Tonight home screen widget shows: what's on tonight and who's in on friends' Pings. The Android
 * counterpart of the iOS `TonightSnapshot` (hot_mess_ios `HotMess/Shared/TonightSnapshot.swift`, #210). The app
 * saves it whenever it loads Now or a Ping changes; the widget only reads it, so it never signs in or calls the API
 * on its own. Names are as the API gives them (a friend's full name, "First L." for anyone else), and only Pings a
 * friend sent (or the user's own) are kept.
 */
@Serializable
data class TonightSnapshot(
    @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
    /** The city, for the header ("Tonight in Seattle"). */
    val localeName: String? = null,
    /** The venue the user is in, and who's on there now. */
    val venueName: String? = null,
    val onNow: String? = null,
    val events: List<Event> = emptyList(),
    val pings: List<Ping> = emptyList(),
) {
    /** Something on tonight in the user's city, or at the event they're at. */
    @Serializable
    data class Event(
        val id: String,
        val name: String,
        /** The venue's name, when it has one. */
        val place: String? = null,
        @Serializable(with = InstantSerializer::class) val startsAt: Instant,
        @Serializable(with = InstantSerializer::class) val endsAt: Instant? = null,
        val url: String,
    ) {
        /** Still worth showing at [now]: not over (or, with no end, started less than 4 hours ago). */
        fun isOn(now: Instant): Boolean = endsAt?.isAfter(now) ?: startsAt.plus(NO_END).isAfter(now)

        /** When it drops off the widget. */
        val dropsAt: Instant get() = endsAt ?: startsAt.plus(NO_END)
    }

    /** A friend's Ping, or the user's own. */
    @Serializable
    data class Ping(
        val id: String,
        /** The sender's first name, or "You". */
        val sender: String,
        /** The first pick's name ("Purr"), or null for anywhere. */
        val place: String? = null,
        /** First names of who's in, oldest first. */
        val people: List<String> = emptyList(),
        val joined: Boolean = false,
        val isMine: Boolean = false,
        @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
        val url: String,
    ) {
        /** "Sam, Alex +2", or null when nobody's in yet. */
        val peopleLine: String?
            get() {
                if (people.isEmpty()) return null
                val shown = people.take(2).joinToString(", ")
                val more = people.size - 2
                return if (more > 0) "$shown +$more" else shown
            }

        /** Whether the button can say "I'm in": a friend's, not yet joined. */
        val canJoin: Boolean get() = !isMine && !joined

        /** "Sam's Ping · Purr", "Your Ping · Anywhere". */
        val title: String
            get() = (if (isMine) "Your Ping" else "$sender's Ping") + " · " + (place ?: "Anywhere")
    }

    /** As it stands at [now]: ended Pings and events dropped. */
    fun current(now: Instant): TonightSnapshot =
        copy(pings = pings.filter { it.expiresAt.isAfter(now) }, events = events.filter { it.isOn(now) })

    /** When the widget should next redraw on its own: the next Ping or event to end. */
    fun nextChange(after: Instant): Instant? =
        (pings.map { it.expiresAt } + events.map { it.dropsAt }).filter { it.isAfter(after) }.minOrNull()

    /** With [ping] in place of the entry for it (after "I'm in" on the widget). */
    fun replacing(ping: Ping): TonightSnapshot = copy(pings = pings.map { if (it.id == ping.id) ping else it })

    /** Nothing to show: the widget offers to open the app instead. */
    val isEmpty: Boolean get() = events.isEmpty() && pings.isEmpty() && venueName == null

    /** "Tonight in Seattle", "At Purr", or "Tonight". */
    val header: String
        get() = venueName?.let { "At $it" } ?: localeName?.takeIf { it.isNotBlank() }?.let { "Tonight in $it" } ?: "Tonight"

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private const val SITE = "https://hotmess.social"
        private val NO_END: Duration = Duration.ofHours(4)

        /** How far ahead counts as tonight. */
        val TONIGHT: Duration = Duration.ofHours(12)

        /** The most events and Pings kept: the large widget shows about this many. */
        const val LIMIT = 6

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** The saved snapshot, or null when there's none or it no longer decodes. */
        fun decode(text: String?): TonightSnapshot? =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }

        /** From a freshly loaded Now: the event the user is at and tonight's events, who's on, and the Pings. */
        fun from(now: social.hotmess.core.Now, at: Instant = Instant.now()): TonightSnapshot {
            val end = at.plus(TONIGHT)
            val events = listOfNotNull(now.event).map(::entry) +
                now.events.filter { it.id != now.event?.id && it.startAt.isBefore(end) }.map(::entry)
            return TonightSnapshot(
                updatedAt = at,
                localeName = now.locale?.name,
                venueName = now.venue?.name ?: now.event?.venue?.name,
                onNow = now.onNow.firstOrNull()?.person?.name?.takeIf { it.isNotBlank() },
                events = events.filter { it.isOn(at) }.distinctBy { it.id }.take(LIMIT),
                pings = pings(now.myPing, now.friendPings, at),
            )
        }

        /**
         * The user's own Ping first, then Pings friends sent them (not ones seen only through someone in a circle),
         * ones they're in before the rest, newest first.
         */
        fun pings(myPing: social.hotmess.core.Ping?, friendPings: List<social.hotmess.core.Ping>, at: Instant): List<Ping> {
            fun open(ping: social.hotmess.core.Ping) = ping.expiresAt?.isAfter(at) ?: false
            val friends = friendPings
                .filter { !it.isMine && it.via == null && open(it) }
                .sortedWith(compareBy<social.hotmess.core.Ping> { if (it.joined) 0 else 1 }.thenByDescending { it.createdAt ?: Instant.EPOCH })
            val mine = listOfNotNull(myPing).filter(::open)
            return (mine + friends).distinctBy { it.id }.take(LIMIT).map(::entry)
        }

        fun entry(ping: social.hotmess.core.Ping): Ping = Ping(
            id = ping.id,
            sender = if (ping.isMine) "You" else ping.user.firstName,
            place = ping.targets.firstOrNull()?.name?.takeIf { it.isNotBlank() },
            people = ping.people.map { it.firstName },
            joined = ping.joined,
            isMine = ping.isMine,
            // A Ping always ends at 5am; one the API sent without an end is kept no later than 12 hours.
            expiresAt = ping.expiresAt ?: Instant.now().plus(TONIGHT),
            url = "$SITE/pings/${ping.id}",
        )

        fun entry(event: social.hotmess.core.Event): Event = Event(
            id = event.id,
            name = event.name,
            place = event.venue?.name?.takeIf { it.isNotBlank() },
            startsAt = event.startAt,
            endsAt = event.endAt,
            url = "$SITE/events/${event.id}",
        )
    }
}
