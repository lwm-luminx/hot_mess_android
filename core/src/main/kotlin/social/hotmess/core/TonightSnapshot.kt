package social.hotmess.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

/**
 * What the Tonight home screen widget shows: the next few events (with where they are, for its rotating card and its
 * Going and Ping buttons) and who's in on friends' Pings. The Android
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
    /** The city's id, for a Ping the widget sends. */
    val localeId: String? = null,
    /** The venue the user is in, and who's on there now. */
    val venueName: String? = null,
    val onNow: String? = null,
    val events: List<Event> = emptyList(),
    val pings: List<Ping> = emptyList(),
) {
    /** Something coming up in the user's city, or the event they're at. */
    @Serializable
    data class Event(
        val id: String,
        val name: String,
        /** The venue's name, when it has one. */
        val place: String? = null,
        @Serializable(with = InstantSerializer::class) val startsAt: Instant,
        @Serializable(with = InstantSerializer::class) val endsAt: Instant? = null,
        val url: String,
        /** The venue's street address ("1201 E Pike St, Seattle"), when listed. */
        val address: String? = null,
        /** The user said they're going. */
        val going: Boolean = false,
        /** The user's own Ping names this event. */
        val pinged: Boolean = false,
    ) {
        /** Where it is: "Purr · 1518 11th Ave" (the address up to its first comma), the venue alone, or the address alone. */
        val location: String?
            get() {
                val street = address?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
                return when {
                    place != null && street != null && street != place -> "$place · $street"
                    else -> place ?: street
                }
            }

        /**
         * When it is, said against [now]: "On now", "9:00 PM", "Tomorrow 9:00 PM", "Fri 9:00 PM", or "Fri, Dec 5".
         */
        fun timeLabel(now: Instant, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): String {
            if (!startsAt.isAfter(now)) return "On now"
            val start = startsAt.atZone(zone)
            val today = now.atZone(zone).toLocalDate()
            val time = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(start)
            return when {
                start.toLocalDate() == today -> time
                start.toLocalDate() == today.plusDays(1) -> "Tomorrow $time"
                startsAt.isBefore(now.plus(Duration.ofDays(6))) ->
                    java.time.format.DateTimeFormatter.ofPattern("EEE", locale).format(start) + " " + time
                else -> java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(start)
            }
        }

        /** Whether a Ping (which lasts until 5am) can name it at [now]: it's on now or starts within [TONIGHT]. */
        fun isPingable(now: Instant): Boolean = isOn(now) && startsAt.isBefore(now.plus(TONIGHT))

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

    /**
     * The event the widget's card features at [now]. It moves on to the next one every [ROTATION], counted from a
     * fixed point so a redraw (after a button, or when the app saves) keeps the same one up.
     */
    fun featuredEvent(now: Instant): Event? {
        val on = events.filter { it.isOn(now) }
        if (on.isEmpty()) return null
        val slot = Math.floorDiv(now.epochSecond, ROTATION.seconds)
        return on[Math.floorMod(slot, on.size.toLong()).toInt()]
    }

    /** With the event [id] changed by [change] (after a button). */
    fun updatingEvent(id: String, change: (Event) -> Event): TonightSnapshot =
        copy(events = events.map { if (it.id == id) change(it) else it })

    /** With the events the user's Ping [myPing] names marked, and the rest not. */
    fun markingPinged(myPing: social.hotmess.core.Ping?, at: Instant): TonightSnapshot {
        val pinged = pingedEventIds(myPing, at)
        return copy(events = events.map { it.copy(pinged = it.id in pinged) })
    }

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

        /** How far ahead counts as tonight, for the Ping button. */
        val TONIGHT: Duration = Duration.ofHours(12)

        /** How far ahead the widget's rotating card looks for events. */
        val UPCOMING: Duration = Duration.ofDays(7)

        /** How long the card features one event; the widget redraws about this often (`tonight_widget_info.xml`). */
        val ROTATION: Duration = Duration.ofMinutes(30)

        /** The most events and Pings kept: the large widget shows about this many. */
        const val LIMIT = 6

        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** The saved snapshot, or null when there's none or it no longer decodes. */
        fun decode(text: String?): TonightSnapshot? =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }

        /** From a freshly loaded Now: the event the user is at, the next week's events, who's on, and the Pings. */
        fun from(now: social.hotmess.core.Now, at: Instant = Instant.now()): TonightSnapshot = TonightSnapshot(
            updatedAt = at,
            localeName = now.locale?.name,
            localeId = now.locale?.id,
            venueName = now.venue?.name ?: now.event?.venue?.name,
            onNow = now.onNow.firstOrNull()?.person?.name?.takeIf { it.isNotBlank() },
            events = upcoming(now.events, now.event, at),
            pings = pings(now.myPing, now.friendPings, at),
        ).markingPinged(now.myPing, at)

        /**
         * The events worth rotating through: [leading] (the one the user is at) first, then the rest that aren't
         * over and start within [UPCOMING], soonest first; at most [LIMIT].
         */
        fun upcoming(events: List<social.hotmess.core.Event>, leading: social.hotmess.core.Event? = null, at: Instant): List<Event> {
            val end = at.plus(UPCOMING)
            val rest = events.filter { it.id != leading?.id && it.startAt.isBefore(end) }.sortedBy { it.startAt }
            return (listOfNotNull(leading) + rest).distinctBy { it.id }.map(::entry).filter { it.isOn(at) }.take(LIMIT)
        }

        /** The events the user's own Ping names, by id. */
        fun pingedEventIds(myPing: social.hotmess.core.Ping?, at: Instant): Set<String> =
            myPing?.takeIf { it.expiresAt?.isAfter(at) ?: false }?.targets?.mapNotNull { it.event?.id }?.toSet().orEmpty()

        /** The user's Ping's event picks with [eventId] added at the end (a new Ping names just the event). */
        fun pingEventIds(eventId: String, myPing: social.hotmess.core.Ping?): List<String> {
            val ids = myPing?.targets?.mapNotNull { it.event?.id }.orEmpty()
            return if (eventId in ids) ids else ids + eventId
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
            address = event.venue?.address?.takeIf { it.isNotBlank() },
            going = event.rsvp == social.hotmess.core.Rsvp.ATTENDING,
        )
    }
}
