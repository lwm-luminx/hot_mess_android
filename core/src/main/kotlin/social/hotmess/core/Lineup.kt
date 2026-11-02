package social.hotmess.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A venue's lineup tonight (its running order), its karaoke signups and its song requests, as the iOS app reads them
// from the AudienceKit SDK (Lineup.swift). A karaoke signup is a singer on the lineup: an entry with role SINGER
// carrying its songRequest, which the venue calls up by advancing the running order. A song request has no lineup
// entry: it waits in the venue's song queue until the host plays it (UP), then is done or skipped.

/** A song to play, or a karaoke signup. */
@Serializable
enum class SongRequestKind { SONG, KARAOKE, UNKNOWN }

/** Where a request is: waiting, up (playing, or the singer is on), or finished. */
@Serializable
enum class SongRequestState {
    QUEUED, UP, DONE, SKIPPED, WITHDRAWN, UNKNOWN;

    /** Still waiting or up. */
    val isOpen: Boolean get() = this == QUEUED || this == UP
}

/** A song request or karaoke signup made from a venue's chat or page. */
@Serializable
data class SongRequest(
    val id: String,
    val kind: SongRequestKind = SongRequestKind.UNKNOWN,
    val title: String = "",
    val artist: String? = null,
    /** Who's singing a karaoke signup, as they gave it ("Ada & Bo"). */
    val singer: String? = null,
    val requesterName: String? = null,
    val note: String? = null,
    /** Whether the signed-in user asked. */
    val mine: Boolean = false,
    val state: SongRequestState = SongRequestState.UNKNOWN,
    val summary: String? = null,
    /** Its place in the night's queue of its kind, from 0. */
    val position: Int? = null,
) {
    /** "Title · Artist", or the title alone. */
    val songLine: String get() = listOfNotNull(title.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

@Serializable
data class SongRequestNodes(val nodes: List<SongRequest> = emptyList())

/** Whether a venue takes requests of one kind now (`requests { karaoke | song }`), the viewer's own, and the song queue. */
@Serializable
data class SongRequestWindow(
    /** Whether the venue takes them at all: karaoke run with AudienceKit, or song requests switched on. */
    val permitted: Boolean = false,
    /** Whether it does right now: a host (or DJ) is on and the venue hasn't paused them. */
    val open: Boolean = false,
    /** The venue paused them for tonight. */
    val paused: Boolean = false,
    /** Why it doesn't, worded for the room; null while open. */
    val closedReason: String? = null,
    /** Tonight's cutoff closed them. */
    val cutOff: Boolean = false,
    val reopened: Boolean = false,
    /** The lineup entry taking them: the host (or DJ) on now. */
    val host: LineupEntry? = null,
    @SerialName("mine") val mineNodes: SongRequestNodes? = null,
    @SerialName("queue") val queueNodes: SongRequestNodes? = null,
) {
    /** The viewer's requests of this kind tonight, oldest first. */
    val mine: List<SongRequest> get() = mineNodes?.nodes.orEmpty()

    /** Tonight's song queue, waiting or up, in order; empty for karaoke. */
    val queue: List<SongRequest> get() = queueNodes?.nodes.orEmpty()

    /** Closed for the night: paused by the venue or past the cutoff. The "+" then reads "Signups closed". */
    val closed: Boolean get() = paused || cutOff

    /** What's playing now: the request the host called up. */
    val playing: SongRequest? get() = queue.firstOrNull { it.state == SongRequestState.UP }

    /** How many requests are ahead of one still waiting; null once it's called or when it isn't queued. */
    fun requestsBefore(request: SongRequest): Int? {
        if (request.state != SongRequestState.QUEUED) return null
        return queue.indexOfFirst { it.id == request.id }.takeIf { it >= 0 }
    }
}

/** Who runs a venue's karaoke: AudienceKit's own signups, or a partner such as KaraFun with a session to join. */
@Serializable
data class VenueKaraoke(
    val provider: String? = null,
    val providerName: String? = null,
    val joinUrl: String? = null,
    val sessionCode: String? = null,
    /** Whether tonight is one of the venue's karaoke nights; null from an API that doesn't say. */
    val tonight: Boolean? = null,
) {
    val isAudienceKit: Boolean get() = provider == "audiencekit"

    /** The join link, only when it's a web page: never an arbitrary scheme from a response. */
    val joinLink: String? get() = Lineups.webLink(joinUrl)

    /** A partner's session to show tonight: the venue has one and tonight is a karaoke night. */
    val isPartnerSessionTonight: Boolean get() = provider != null && !isAudienceKit && tonight != false
}

/** What a karaoke signup or song request asks for. [singer] is for karaoke only; the viewer when left out. */
data class SongRequestDraft(val title: String, val artist: String? = null, val singer: String? = null, val note: String? = null)

/** A venue's lineup tonight, in running order, its karaoke signups and its song requests. */
data class VenueLineup(
    val venueId: String,
    val venueName: String,
    /** Everything on tonight, in running order, each act with scenes as its scenes in turn. */
    val entries: List<LineupEntry>,
    val onStageId: String? = null,
    val upNextId: String? = null,
    val karaoke: SongRequestWindow? = null,
    val karaokeSession: VenueKaraoke? = null,
    val songRequests: SongRequestWindow? = null,
) {
    /** Everyone who isn't a karaoke singer: hosts, DJs, performers, MCs and intermissions. */
    val acts: List<LineupEntry> get() = entries.filterNot { it.isSinger }

    /** The karaoke singers, in running order. */
    val singers: List<LineupEntry> get() = entries.filter { it.isSinger }

    /** The host on now: the host entry that's on, else the one taking karaoke signups. */
    val hostOnNow: LineupEntry?
        get() = entries.firstOrNull { it.role == AppearanceRole.HOST && it.state == LineupState.ON } ?: karaoke?.host

    /** A lineup of only hosts isn't a running order: it reads "Hosted by A and B". */
    val onlyHosts: Boolean get() = acts.isNotEmpty() && acts.all { it.role == AppearanceRole.HOST }

    /** What the karaoke list shows: the singers, and the breaks among them (after the first singer, or on now). */
    val karaokeEntries: List<LineupEntry>
        get() {
            val first = entries.indexOfFirst { it.isSinger }.takeIf { it >= 0 } ?: return emptyList()
            return entries.filterIndexed { index, entry ->
                entry.isSinger || (entry.isIntermission && (index > first || entry.state == LineupState.ON))
            }
        }

    /** The intermission on now: nobody is called up until the venue advances past it. */
    val breakOnNow: LineupEntry? get() = entries.firstOrNull { it.isIntermission && it.state == LineupState.ON }

    /** A singer's number tonight, from 1, in running order. */
    fun singerNumber(entry: LineupEntry): Int? = singers.indexOfFirst { it.id == entry.id }.takeIf { it >= 0 }?.plus(1)

    /** The singer entry for a signup. */
    fun entryFor(request: SongRequest): LineupEntry? = singers.firstOrNull { it.songRequest?.id == request.id }

    /** How many singers are ahead of a signup that hasn't been called: the one on stage and those still to sing. */
    fun singersBefore(request: SongRequest): Int? {
        if (request.state != SongRequestState.QUEUED) return null
        val index = singers.indexOfFirst { it.songRequest?.id == request.id }.takeIf { it >= 0 } ?: return null
        return singers.take(index).count { it.state != LineupState.DONE }
    }

    /** The signup to show the viewer: their earliest still open, else their latest finished one. */
    val featuredSignup: SongRequest?
        get() {
            val mine = karaoke?.mine.orEmpty()
            return mine.firstOrNull { it.state.isOpen } ?: mine.lastOrNull()
        }

    /** The viewer's song requests tonight, newest first. */
    val mySongRequests: List<SongRequest> get() = songRequests?.mine.orEmpty().reversed()

    /** Karaoke signups' "+": AudienceKit karaoke the venue runs tonight (not a partner's session). */
    val takesKaraokeSignups: Boolean get() = karaoke?.permitted == true && karaokeSession?.isPartnerSessionTonight != true

    /** Song requests' "+". */
    val takesSongRequests: Boolean get() = songRequests?.permitted == true

    /** Whether there's anything to show at all. */
    val isEmpty: Boolean
        get() = acts.isEmpty() && singers.isEmpty() && !takesKaraokeSignups && !takesSongRequests &&
            karaokeSession?.isPartnerSessionTonight != true
}

/** How the venue page bills an entry: a singer by the name they gave, else [LineupEntry.name], else "Intermission" or "Someone". */
val LineupEntry.billing: String
    get() {
        if (isSinger) (songRequest?.singer ?: songRequest?.requesterName)?.takeIf { it.isNotBlank() }?.let { return it }
        return name.takeIf { it.isNotBlank() } ?: if (isIntermission) "Intermission" else "Someone"
    }

/**
 * An event that's a karaoke night (`karaoke`, the IKaraokeNight mixin): its venue's partner and the night's session
 * to join, or AudienceKit's own signups on the venue's page.
 */
@Serializable
data class KaraokeNight(
    val provider: String? = null,
    val providerName: String? = null,
    val sessionCode: String? = null,
    val ownSessionCode: String? = null,
    val joinUrl: String? = null,
) {
    /** The join link, only when it's a web page. */
    val joinLink: String? get() = Lineups.webLink(joinUrl)

    /** AudienceKit's own karaoke: people sign up on the venue's page or in its chat. */
    val isAudienceKit: Boolean get() = provider == "audiencekit" && joinLink == null
}

object Lineups {
    /** [url] when it's an http(s) page with a host and no credentials, else null. */
    fun webLink(url: String?): String? {
        val uri = url?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.userInfo != null) return null
        return uri.toString()
    }

    /** "Ada", "Ada and Bo", "Ada, Bo and Cy". */
    fun andList(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    const val SONG_REQUEST_FIELDS = "id kind title artist singer requesterName note mine state summary position"
    private const val ENTRY_FIELDS = "${Documents.LINEUP_ENTRY_FIELDS} songRequest { $SONG_REQUEST_FIELDS }"
    private const val WINDOW_FIELDS = "permitted open paused closedReason cutOff reopened host { $ENTRY_FIELDS } mine { nodes { $SONG_REQUEST_FIELDS } }"

    /** A venue's lineup tonight, its karaoke and its song requests (the SDK's VenueLineup query). */
    const val VENUE_LINEUP: String = "query VenueLineup(\$id: ID!) { venues { venue(id: \$id) { id name " +
        "people { runningOrder { nodes { $ENTRY_FIELDS } } onStage { id } upNext { id } } " +
        "karaoke { provider providerName joinUrl sessionCode tonight } " +
        "requests { karaoke { $WINDOW_FIELDS } song { $WINDOW_FIELDS queue { nodes { $SONG_REQUEST_FIELDS } } } } } } }"

    /** A karaoke signup (`kind: KARAOKE`) or a song request (`kind: SONG`). */
    const val REQUEST_SONG: String = "mutation RequestSong(\$venueId: ID!, \$kind: SongRequestKind!, \$title: String!, " +
        "\$artist: String, \$singer: String, \$note: String) { requestSong(input: { venueId: \$venueId, kind: \$kind, " +
        "title: \$title, artist: \$artist, singer: \$singer, note: \$note }) { request { $SONG_REQUEST_FIELDS } } }"

    /** Takes back one of the viewer's signups or song requests that hasn't been called up. */
    const val WITHDRAW_SONG_REQUEST: String =
        "mutation WithdrawSongRequest(\$id: ID!) { withdrawSongRequest(input: { id: \$id }) { request { $SONG_REQUEST_FIELDS } } }"
}

@Serializable
internal data class TonightLineupResponse(val venues: Venues? = null) {
    @Serializable
    data class Venues(val venue: Venue? = null)

    @Serializable
    data class Venue(
        val id: String,
        val name: String? = null,
        val people: People? = null,
        val karaoke: VenueKaraoke? = null,
        val requests: Requests? = null,
    )

    @Serializable
    data class People(
        val runningOrder: Order? = null,
        val onStage: LineupEntry.Ref? = null,
        val upNext: LineupEntry.Ref? = null,
    )

    @Serializable
    data class Order(val nodes: List<LineupEntry?> = emptyList())

    @Serializable
    data class Requests(val karaoke: SongRequestWindow? = null, val song: SongRequestWindow? = null)

    val lineup: VenueLineup?
        get() = venues?.venue?.let { venue ->
            val order = venue.people?.runningOrder?.nodes.orEmpty().filterNotNull()
            VenueLineup(
                venueId = venue.id,
                venueName = venue.name.orEmpty(),
                entries = order.inTurn(),
                onStageId = order.turnId(venue.people?.onStage?.id, LineupState.ON),
                upNextId = order.turnId(venue.people?.upNext?.id, LineupState.UPCOMING),
                karaoke = venue.requests?.karaoke,
                karaokeSession = venue.karaoke,
                songRequests = venue.requests?.song,
            )
        }
}

@Serializable
internal data class SongRequestPayload(val request: SongRequest)

@Serializable
internal data class RequestSongResponse(val requestSong: SongRequestPayload)

@Serializable
internal data class WithdrawSongRequestResponse(val withdrawSongRequest: SongRequestPayload)
