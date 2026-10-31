package social.hotmess.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * One entry in a venue night's running order (`Venue.people.runningOrder`): an act, one or several people
 * on as one (a duet's two, billed in order), or an intermission. Kinds, roles, states and timings come as
 * GraphQL's enum names; read [role], [state] and [isHardStart].
 */
@Serializable
data class LineupEntry(
    val id: String,
    @SerialName("kind") val kindName: String? = null,
    @SerialName("role") val roleName: String? = null,
    @SerialName("state") val stateName: String? = null,
    @SerialName("startTiming") val startTimingName: String? = null,
    @Serializable(with = InstantSerializer::class) val startsAt: Instant? = null,
    @Serializable(with = InstantSerializer::class) val endsAt: Instant? = null,
    /** A duet's billing or an intermission's label, when it has one. */
    val title: String? = null,
    /** Who, or the first of them; null for an intermission. */
    val person: Person? = null,
    @SerialName("people") private val peopleNodes: Connection<Person>? = null,
    /** An act's scenes (its numbers, each with who's on) in order; empty for anything else. [inTurn] lists them. */
    val scenes: List<Scene> = emptyList(),
    /** A break inside an act: the scene it follows. */
    val afterScene: Ref? = null,
) {
    /** One number in an act: who's on it, and when advancing put it on and took it off. */
    @Serializable
    data class Scene(
        val id: String,
        val performers: List<Person?> = emptyList(),
        @Serializable(with = InstantSerializer::class) val wentOnAt: Instant? = null,
        @Serializable(with = InstantSerializer::class) val cameOffAt: Instant? = null,
    )

    @Serializable data class Ref(val id: String)

    /** Who's on it, in billing order; just [person] from an API that doesn't list them. */
    val people: List<Person> get() = peopleNodes?.nodes.orEmpty().filterNotNull().ifEmpty { listOfNotNull(person) }

    val isIntermission: Boolean get() = kindName.equals("INTERMISSION", ignoreCase = true)

    /** A karaoke signup. Singers are guests in the room, never on its lineup. */
    val isSinger: Boolean get() = roleName.equals("SINGER", ignoreCase = true)

    /** What they're doing; null for an intermission, a singer, or a role this build doesn't know. */
    val role: AppearanceRole? get() = AppearanceRole.of(roleName)

    val state: LineupState get() = LineupState.of(stateName)

    /** On at its time by the clock, the only kind of start the lineup shows a time for. */
    val isHardStart: Boolean get() = startTimingName.equals("HARD", ignoreCase = true)

    /** Its billing, else its people's names ("Aurora Borealis & Lady Bunny Hop"). Listing names are public, so in full. */
    val name: String get() = title?.takeIf { it.isNotBlank() } ?: people.map { it.name }.filter { it.isNotBlank() }.joinToString(" & ")

    /**
     * Its scenes in turn, each an entry of its own: the scene's ID and performers (else the act's people), no billing,
     * and a state that's the act's and, in an act on now, as advancing marked it. In an act on now with no scene put
     * on yet (run as one act), its first scene is on. Just itself without scenes.
     */
    fun turns(): List<LineupEntry> {
        if (scenes.isEmpty() || isIntermission) return listOf(this)
        val started = scenes.any { it.wentOnAt != null }
        return scenes.mapIndexed { index, scene ->
            val sceneState = when {
                state != LineupState.ON -> state
                scene.cameOffAt != null -> LineupState.DONE
                scene.wentOnAt != null -> LineupState.ON
                !started && index == 0 -> LineupState.ON
                else -> LineupState.UPCOMING
            }
            val performers = scene.performers.filterNotNull().ifEmpty { people }
            LineupEntry(
                id = scene.id, kindName = kindName, roleName = roleName, stateName = sceneState.name,
                startTimingName = if (index == 0) startTimingName else "NONE", startsAt = if (index == 0) startsAt else null,
                person = performers.firstOrNull(), peopleNodes = Connection(performers),
            )
        }
    }

    /** Its set, "9:00 PM – close" until it has an end time, for a hard start only; null otherwise. */
    fun hours(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val start = startsAt?.takeIf { isHardStart } ?: return null
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)
        return "${time.format(start)} – ${endsAt?.let(time::format) ?: "close"}"
    }
}

/**
 * A running order as it runs: an act with scenes is each of its scenes in turn (who's on, not its billing like "First
 * Set"), each followed by the breaks after it.
 */
fun List<LineupEntry>.inTurn(): List<LineupEntry> {
    val sceneIds = filter { !it.isIntermission }.flatMap { act -> act.scenes.map { it.id } }.toSet()
    val inside = { entry: LineupEntry -> entry.afterScene?.id in sceneIds }
    val after = filter(inside).groupBy { it.afterScene?.id }
    return filterNot(inside).flatMap { entry ->
        if (entry.scenes.isEmpty() || entry.isIntermission) listOf(entry)
        else entry.scenes.zip(entry.turns()).flatMap { (scene, turn) -> listOf(turn) + after[scene.id].orEmpty() }
    }
}

/** The entry [inTurn] lists for [id] (an act's, as `upNext` gives it): its scene in [state], else its first. */
fun List<LineupEntry>.turnId(id: String?, state: LineupState): String? {
    val act = firstOrNull { it.id == id }?.takeIf { it.scenes.isNotEmpty() && !it.isIntermission } ?: return id
    val turns = act.turns()
    return (turns.firstOrNull { it.state == state } ?: turns.firstOrNull())?.id
}

/** Where a lineup entry is in its night (GraphQL's AppearanceState). */
enum class LineupState {
    UPCOMING,
    ON,
    DONE,
    ;

    companion object {
        /** Anything unknown reads as still to come. */
        fun of(wire: String?): LineupState = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) } ?: UPCOMING
    }
}

/**
 * Whether anyone who manages a lineup listing is around: in the room ([ONLINE]), reachable by a notification
 * ([PUSH]), or neither. The room rolls it up per listing (ChatRoster `lineup`, ChatLineupChanged).
 */
enum class LineupPresence(val words: String) {
    ONLINE("In chat now"),
    PUSH("Gets notifications"),
    OFFLINE("Not in chat yet"),
    ;

    companion object {
        fun of(wire: String?): LineupPresence? = entries.firstOrNull { it.name.equals(wire, ignoreCase = true) }
    }
}

/** A lineup entry's state badge, in the lineup's own words. */
enum class LineupBadge(val label: String) {
    ON_NOW("On now"),
    UP_NEXT("Up next"),
    DONE("Done"),
}

/** One face on a room's lineup: the [entry], its [badge] if any, and its [presence] when the room says (null otherwise). */
data class LineupFace(val entry: LineupEntry, val badge: LineupBadge?, val presence: LineupPresence?) {
    val id: String get() = entry.id
    val isDone: Boolean get() = badge == LineupBadge.DONE
}

/** A venue's night, as a chat room shows it: the venue's face, and its running order with what's up next. */
data class VenueNight(
    val venueId: String,
    val name: String,
    val photoUrl: String? = null,
    val entries: List<LineupEntry> = emptyList(),
    /** What advancing the running order puts on next. */
    val upNextId: String? = null,
)

/**
 * Who the room lists on tonight's lineup, and in what order (design system NowHosts.md): one face per
 * lineup entry, singers and intermissions left out; the host first, as VenueHostRow leads with them;
 * then who's on now, up next, the rest still to come, and done acts last.
 */
object RoomLineup {
    /** How many lineup faces the Here now strip shows before folding the rest into a "+N on the lineup" chip. */
    const val STRIP_LIMIT: Int = 6

    /**
     * The lineup's faces from [entries] (in running order), with [upNextId]'s marked Up next. [presence] is the
     * room's per-listing presence by lowercase person id; null when the room doesn't send it, so no face has any.
     */
    fun faces(entries: List<LineupEntry>, upNextId: String?, presence: Map<String, LineupPresence>?): List<LineupFace> =
        entries
            .filter { !it.isSinger && !it.isIntermission && it.people.isNotEmpty() }
            .distinctBy { it.id }
            .map { LineupFace(it, badge(it, upNextId), presence?.let { known -> presenceOf(it, known) }) }
            // Stable, so each group keeps the running order.
            .sortedWith(compareBy<LineupFace> { if (it.entry.role == AppearanceRole.HOST) 0 else 1 }.thenBy { rank(it) })

    /** The badge for [entry]: on now, up next (it's [upNextId] and still to come), done, or none. */
    fun badge(entry: LineupEntry, upNextId: String?): LineupBadge? = when {
        entry.state == LineupState.ON -> LineupBadge.ON_NOW
        entry.state == LineupState.DONE -> LineupBadge.DONE
        upNextId != null && RecordId.same(entry.id, upNextId) -> LineupBadge.UP_NEXT
        else -> null
    }

    /** In chat if any of the entry's people are, else reachable if any are, else offline. */
    fun presenceOf(entry: LineupEntry, presence: Map<String, LineupPresence>): LineupPresence {
        val known = entry.people.mapNotNull { presence[it.id.lowercase()] }
        return when {
            LineupPresence.ONLINE in known -> LineupPresence.ONLINE
            LineupPresence.PUSH in known -> LineupPresence.PUSH
            else -> LineupPresence.OFFLINE
        }
    }

    /** The strip's faces: the first [limit], and how many fold into the chip. */
    fun strip(faces: List<LineupFace>, limit: Int = STRIP_LIMIT): Pair<List<LineupFace>, Int> =
        faces.take(limit) to (faces.size - limit).coerceAtLeast(0)

    /** The strip's header: "Tonight · 23 here", counting who's in the room now. */
    fun header(here: Int): String = "Tonight · $here here"

    /** The chip folding the rest of the lineup: "+5 on the lineup". */
    fun moreLabel(folded: Int): String = "+$folded on the lineup"

    /** The sheet's subtitle: "Drag Bingo · The Wildrose", or the venue alone when the room is the venue's. */
    fun subtitle(room: String, venue: String?): String = when {
        venue.isNullOrBlank() -> room
        room.isBlank() || room.equals(venue, ignoreCase = true) -> venue
        else -> "$room · $venue"
    }

    /**
     * Small talk's line on Now: "The Wildrose, 3 from tonight's lineup and Jordan · 23 here". Without lineup
     * people in chat, "The Wildrose · 23 here"; without a friend, "The Wildrose and 3 from tonight's lineup".
     * The count is left off when it isn't known.
     */
    fun peekLine(venue: String, lineupInChat: Int, friend: String?, count: Int?): String {
        val who = when {
            lineupInChat <= 0 -> venue
            friend.isNullOrBlank() -> "$venue and $lineupInChat from tonight's lineup"
            else -> "$venue, $lineupInChat from tonight's lineup and $friend"
        }
        return if (count == null) who else "$who · $count here"
    }

    private fun rank(face: LineupFace): Int = when (face.badge) {
        LineupBadge.ON_NOW -> 0
        LineupBadge.UP_NEXT -> 1
        null -> 2
        LineupBadge.DONE -> 3
    }
}
