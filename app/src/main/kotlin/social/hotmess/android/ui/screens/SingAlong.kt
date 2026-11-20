package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MusicVideo
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.audiencekit.AudienceKitClient
import com.audiencekit.NowPlaying
import com.audiencekit.SingAlongAsk
import com.audiencekit.SingAlongNight
import com.audiencekit.SingAlongTrack
import com.audiencekit.SingAlongTrackStatus
import com.audiencekit.nowPlaying
import com.audiencekit.singAlong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Event
import social.hotmess.core.Now
import social.hotmess.core.PingChoices
import social.hotmess.core.SongRequestDraft
import social.hotmess.core.SongRequestKind
import social.hotmess.core.SongRequestState
import social.hotmess.core.VenueLineup
import social.hotmess.core.VenueOverview
import java.time.Duration
import java.time.Instant

/** An event's sing-along from the SDK, null until it loads (or when the API can't say). */
private suspend fun AudienceKitClient.singAlongOrNull(eventId: String): SingAlongNight? = try {
    singAlong(eventId)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * A sing-along night, in the Karaoke section's place on an event's page, on Now and on the venue's page that night, as
 * iOS's SingAlongSection: what's playing and up next, Request a song from the night's playlist, the viewer's requests,
 * and the playlist. The words are never shown here; they're on the venue's screens. While [isLive], the rotation is
 * read every half minute, and as soon as the player's report goes stale. [model] and [lineup] are tonight's lineup at
 * the venue, whose song requests a sing-along takes; requests show only when [viewerName] says someone is signed in.
 */
@Composable
fun SingAlongSection(
    eventId: String,
    isLive: Boolean,
    model: TonightLineupModel?,
    lineup: VenueLineup?,
    viewerName: String?,
) {
    val client = LocalAppGraph.current.audienceKit
    val context = LocalContext.current
    var night by remember(eventId) { mutableStateOf<SingAlongNight?>(null) }
    var picking by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var freeText by remember { mutableStateOf(false) }

    LaunchedEffect(eventId) { client.singAlongOrNull(eventId)?.let { night = it } }
    LaunchedEffect(eventId, isLive) {
        if (!isLive) return@LaunchedEffect
        while (true) {
            delay(nextRefresh(night?.nowPlaying))
            val current = night
            val venueId = current?.venueId
            if (current == null || venueId == null) {
                client.singAlongOrNull(eventId)?.let { night = it }
                continue
            }
            val playing = try {
                client.nowPlaying(venueId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                current.nowPlaying?.takeIf { it.isFresh() }
            }
            night = current.copy(nowPlaying = playing)
        }
    }

    val window = if (isLive) lineup?.songRequests else null
    val asks = window?.let { w ->
        (w.mine + w.queue).filter { it.kind == SongRequestKind.SONG && it.state.isOpen }
            .map { SingAlongAsk(it.title, it.artist, mine = it.mine) }
    }.orEmpty()
    val tracks = night?.playlist?.tracks
    val playing = night?.nowPlaying?.takeIf { isLive }

    DetailSection(
        "Sing-along",
        modifier = Modifier.testTag("singAlong"),
        footer = if (!isLive && night?.playlist != null) {
            { Text("Requests open on the night.", style = HotMessType.caption, color = tokens.inkMuted) }
        } else {
            null
        },
    ) {
        if (playing != null) {
            val mineUp = window?.mine.orEmpty().any {
                it.kind == SongRequestKind.SONG && it.state == SongRequestState.UP &&
                    SingAlongTrack(it.title, it.artist, uri = "").matches(playing.title, playing.artist)
            }
            NowPlayingRow(playing, mineUp)
        } else {
            Intro(isLive)
        }
        if (window != null && window.permitted && viewerName != null && model != null) {
            RowDivider()
            val mine = window.mine.filter { it.kind == SongRequestKind.SONG && it.state.isOpen }
            Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                RequestButton(SongRequestKind.SONG, window, if (mine.isEmpty()) "Request a song" else "Request another") {
                    if (tracks.isNullOrEmpty()) freeText = true else picking = true
                }
            }
            val scope = rememberCoroutineScope()
            mine.forEach { request ->
                val status = if (request.state == SongRequestState.UP) {
                    "Playing now"
                } else {
                    when (val before = window.requestsBefore(request)) {
                        null, 0 -> "Next up"
                        1 -> "1 song before yours"
                        else -> "$before songs before yours"
                    }
                }
                RequestRow(Icons.Rounded.MusicNote, request.songLine, "Yours · $status") {
                    if (request.state == SongRequestState.QUEUED) {
                        TextButton(onClick = { scope.launch { model.withdraw(request) } }) { Text("Withdraw", color = tokens.accentInk) }
                    }
                }
            }
        }
        val playlist = night?.playlist
        if (playlist != null) {
            RowDivider()
            if (!tracks.isNullOrEmpty()) {
                InfoRow(
                    if (isLive) "Tonight's playlist" else "The playlist",
                    "${tracks.size} songs",
                    icon = Icons.AutoMirrored.Rounded.QueueMusic,
                    onClick = { browsing = true },
                )
            } else if (playlist.url.startsWith("https://")) {
                InfoRow("Open in Spotify", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(playlist.url) })
            }
        }
    }

    val loaded = night
    if ((picking || browsing) && loaded != null) {
        PlaylistSheet(
            night = loaded,
            asks = asks,
            picks = picking,
            submit = { draft -> model?.request(SongRequestKind.SONG, draft) },
            onDismiss = {
                picking = false
                browsing = false
            },
        )
    }
    if (freeText && model != null) {
        SongRequestSheet(
            kind = SongRequestKind.SONG,
            venueName = lineup?.venueName ?: loaded?.venueName.orEmpty(),
            hostName = window?.host?.billing,
            viewerName = viewerName,
            submit = { draft -> model.request(SongRequestKind.SONG, draft) },
            onDismiss = { freeText = false },
        )
    }
}

/** Half a minute, or sooner when the player's report runs out first. */
private fun nextRefresh(playing: NowPlaying?): Long {
    val expires = playing?.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return 30_000L
    val seconds = Duration.between(Instant.now(), expires).seconds + 1
    return seconds.coerceIn(5, 30) * 1_000L
}

@Composable
private fun Intro(isLive: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(Space.s4).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.MusicVideo, contentDescription = null, tint = tokens.success, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text("Sing-along night", style = HotMessType.subheading, color = tokens.ink)
            Text(
                if (isLive) "It's on. Look up, the words are on the screens." else "No sign-ups, no solos. The whole bar sings, words on the screens.",
                style = HotMessType.body,
                color = tokens.inkMuted,
            )
        }
    }
}

/** What's playing: the cover, the song, a "Now playing" pill, and the next three songs. One element each for TalkBack. */
@Composable
private fun NowPlayingRow(playing: NowPlaying, mine: Boolean) {
    val upNext = playing.upNext.take(3)
    Column(Modifier.fillMaxWidth().padding(Space.s4).testTag("singAlong.nowPlaying"), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        val label = "Now playing: ${playing.title}" + (playing.artist?.let { " by $it" } ?: "")
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.Top,
        ) {
            val art = playing.artworkUrl?.takeIf { it.startsWith("https://") }
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tokens.successSoft), contentAlignment = Alignment.Center) {
                if (art != null) {
                    RemoteImage(art, Modifier.size(48.dp))
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = tokens.success)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(playing.title, style = HotMessType.subheading, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val artist = if (mine) listOfNotNull("Your request", playing.artist).joinToString(" · ") else playing.artist
                artist?.let { Text(it, style = HotMessType.body, color = tokens.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Surface(shape = CircleShape, color = tokens.successSoft) {
                Row(Modifier.padding(horizontal = Space.s2, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = tokens.success, modifier = Modifier.size(14.dp))
                    Text("Now playing", style = HotMessType.caption, color = tokens.success, maxLines = 1)
                }
            }
        }
        if (upNext.isNotEmpty()) {
            val songs = upNext.joinToString(", then ") { song -> song.artist?.let { "${song.title} by $it" } ?: song.title }
            Column(
                Modifier.padding(start = 60.dp).clearAndSetSemantics { contentDescription = "Up next: $songs" },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Up next", style = HotMessType.caption, color = tokens.inkMuted)
                upNext.forEach { song ->
                    Text(
                        song.title + (song.artist?.let { " · $it" } ?: ""),
                        style = HotMessType.body,
                        color = tokens.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The night's playlist, A to Z with search. Picking a song (when [picks]) goes on to a dedication and Send request.
 * Songs that are on, up next or already asked for are dimmed with why. Only songs on the playlist can be asked for:
 * the screens only have the words for those. [submit] returns the server's refusal, or null once it went.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSheet(
    night: SingAlongNight,
    asks: List<SingAlongAsk>,
    picks: Boolean,
    submit: suspend (SongRequestDraft) -> String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by rememberSaveable { mutableStateOf("") }
    var chosen by remember { mutableStateOf<SingAlongTrack?>(null) }
    val total = night.playlist?.tracks?.size ?: 0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val track = chosen
        if (track != null) {
            RequestForm(track, submit = submit, onBack = { chosen = null }, onSent = onDismiss)
            return@ModalBottomSheet
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (picks) "Request a song" else "Tonight's playlist", style = HotMessType.heading, color = tokens.ink, modifier = Modifier.weight(1f))
                night.playlist?.url?.takeIf { it.startsWith("https://") }?.let { url ->
                    TextButton(onClick = { context.openUrl(url) }) { Text("Open in Spotify", color = tokens.accentInk) }
                }
            }
            if (picks) {
                night.venueName?.let { Text("Tonight at $it. Pick from the playlist.", style = HotMessType.body, color = tokens.inkMuted) }
            }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search $total songs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("singAlong.search"),
            )
        }
        val tracks = night.pickableTracks(search)
        if (tracks.isEmpty() && search.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(Space.s5), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Not on tonight's playlist", style = HotMessType.subheading, color = tokens.ink)
                Text("Try another. Everyone needs to know the words.", style = HotMessType.body, color = tokens.inkMuted)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(tracks, key = { it.uri }) { track ->
                val status = night.status(track, asks)
                TrackRow(track, status, onClick = if (picks && status == null) ({ chosen = track }) else null)
            }
        }
    }
}

@Composable
private fun TrackRow(track: SingAlongTrack, status: SingAlongTrackStatus?, onClick: (() -> Unit)?) {
    val label = status?.label
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = "Request ${track.title}", onClick = onClick) else Modifier)
            .alpha(if (status == null) 1f else 0.55f)
            .padding(horizontal = Space.s4, vertical = Space.s2)
            .semantics(mergeDescendants = true) { label?.let { stateDescription = it } }
            .testTag("singAlong.track"),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = HotMessType.body, color = tokens.ink)
            track.artist?.let { Text(it, style = HotMessType.caption, color = tokens.inkMuted) }
        }
        if (status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(status.icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(14.dp))
                Text(status.label, style = HotMessType.caption, color = tokens.inkMuted)
            }
        }
    }
}

private val SingAlongTrackStatus.label: String
    get() = when (this) {
        SingAlongTrackStatus.PLAYING_NOW -> "Playing now"
        SingAlongTrackStatus.UP_NEXT -> "Up next"
        SingAlongTrackStatus.REQUESTED -> "Requested"
        SingAlongTrackStatus.IN_QUEUE -> "In the queue"
    }

private val SingAlongTrackStatus.icon
    get() = when (this) {
        SingAlongTrackStatus.PLAYING_NOW -> Icons.AutoMirrored.Rounded.VolumeUp
        SingAlongTrackStatus.UP_NEXT -> Icons.Rounded.SkipNext
        SingAlongTrackStatus.REQUESTED -> Icons.Rounded.Check
        SingAlongTrackStatus.IN_QUEUE -> Icons.Rounded.Schedule
    }

/** The song picked, an optional dedication, and Send request; the server's refusal shows above, worded for the room. */
@Composable
private fun RequestForm(track: SingAlongTrack, submit: suspend (SongRequestDraft) -> String?, onBack: () -> Unit, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var note by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(Space.s4).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Request a song", style = HotMessType.heading, color = tokens.ink, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back", color = tokens.accentInk) }
        }
        failure?.let { Text(it, style = HotMessType.body, color = tokens.danger) }
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(track.title, style = HotMessType.subheading, color = tokens.ink)
            track.artist?.let { Text(it, style = HotMessType.body, color = tokens.inkMuted) }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(280) },
            label = { Text("Dedication or note (optional)") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("singAlong.note"),
        )
        PrimaryButton(
            if (sending) "Sending…" else "Send request",
            onClick = {
                sending = true
                failure = null
                scope.launch {
                    val error = submit(SongRequestDraft(title = track.title, artist = track.artist, note = note.trim().ifEmpty { null }))
                    sending = false
                    if (error == null) onSent() else failure = error
                }
            },
            enabled = !sending,
            modifier = Modifier.fillMaxWidth().testTag("singAlong.send"),
        )
    }
}

/** The event the user is at when it's a sing-along night, whose card takes the place of karaoke on Now. */
val Now.singAlongEventId: String? get() = event?.takeIf { it.karaoke?.singAlong == true }?.id

/** Tonight's sing-along at a venue, if it has one, for its page. */
val VenueOverview.singAlongTonight: Event?
    get() = events.firstOrNull { it.karaoke?.singAlong == true && PingChoices.isTonight(it) }
