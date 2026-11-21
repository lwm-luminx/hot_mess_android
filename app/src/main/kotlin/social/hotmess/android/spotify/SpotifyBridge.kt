package social.hotmess.android.spotify

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitException
import com.audiencekit.EventSetList
import com.audiencekit.SpotifyLink
import com.audiencekit.requestSong
import com.audiencekit.setList
import com.audiencekit.spotifyLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens

// The Spotify bridge (audience_kit_api doc/SPOTIFY_BRIDGE.md): a song from someone's own Spotify goes to the bar as a
// song request, shared to Hot Mess from Spotify's share sheet or pasted on a venue page; and a night's set list goes
// back out to their own Spotify. Nobody signs in to Spotify, and nothing plays here.

/** A Spotify song on its way to a venue as a request; [venueId] null when the person hasn't seen one taking requests. */
data class SpotifyShare(val link: String, val venueId: String?, val venueName: String?)

/**
 * Songs shared to Hot Mess, and the venue whose song requests the person last saw open (kept six hours on this phone),
 * which is where a share goes.
 */
class SpotifyShares(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("spotify_share", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow<SpotifyShare?>(null)

    /** The share the request sheet shows; null when there's none. */
    val pending: StateFlow<SpotifyShare?> = _pending.asStateFlow()

    /** A shared text or link: offers its first Spotify link to the request sheet. False when it held none. */
    fun offer(text: String, now: Long = System.currentTimeMillis()): Boolean {
        val link = SpotifyLink.firstLink(text) ?: return false
        val venue = requestVenue(now)
        _pending.value = SpotifyShare(link, venue?.first, venue?.second)
        return true
    }

    /** A link pasted on a venue page, going to that venue. */
    fun offer(text: String, venueId: String, venueName: String): Boolean {
        val link = SpotifyLink.firstLink(text) ?: return false
        _pending.value = SpotifyShare(link, venueId, venueName)
        return true
    }

    fun dismiss() {
        _pending.value = null
    }

    fun rememberRequestVenue(id: String, name: String, now: Long = System.currentTimeMillis()) {
        preferences.edit().putString(VENUE_ID, id).putString(VENUE_NAME, name).putLong(SEEN_AT, now).apply()
    }

    private fun requestVenue(now: Long): Pair<String, String>? {
        val id = preferences.getString(VENUE_ID, null) ?: return null
        val name = preferences.getString(VENUE_NAME, null) ?: return null
        return (id to name).takeIf { now - preferences.getLong(SEEN_AT, 0) <= VENUE_LIFETIME_MS }
    }

    private companion object {
        const val VENUE_ID = "venue_id"
        const val VENUE_NAME = "venue_name"
        const val SEEN_AT = "seen_at"
        const val VENUE_LIFETIME_MS = 6 * 60 * 60 * 1000L
    }
}

private suspend fun AudienceKitClient.spotifyLinkOrNull(url: String): SpotifyLink? = try {
    spotifyLink(url)
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** The request sheet over every screen while a share is pending. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyRequestSheet(share: SpotifyShare, onSent: () -> Unit = {}, onDismiss: () -> Unit) {
    val client = LocalAppGraph.current.audienceKit
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var song by remember(share) { mutableStateOf<SpotifyLink?>(null) }
    var loaded by remember(share) { mutableStateOf(false) }
    var note by remember(share) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember(share) { mutableStateOf<String?>(null) }

    LaunchedEffect(share) {
        song = client.spotifyLinkOrNull(share.link)
        loaded = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tokens.surfaceRaised,
        modifier = Modifier.testTag("spotifyRequest"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(Space.s4).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text("Request a song", style = HotMessType.heading, color = tokens.ink)
            val current = song
            when {
                current != null -> SongRow(current) { uri.openUri(current.url) }
                loaded -> Text("That isn't a Spotify link.", style = HotMessType.body, color = tokens.inkMuted)
                else -> Text("Reading the link…", style = HotMessType.body, color = tokens.inkMuted)
            }
            if (current != null && !current.isSong) {
                Text(
                    "Request one song at a time: open it in Spotify, tap Share on a song and choose Hot Mess.",
                    style = HotMessType.body,
                    color = tokens.inkMuted,
                )
            } else if (current != null && share.venueId == null) {
                Text(
                    "Open the venue's page in Hot Mess while you're there and its song requests are open, then paste the link under Song requests.",
                    style = HotMessType.body,
                    color = tokens.inkMuted,
                )
            }
            val venueId = share.venueId
            if (current != null && current.isSong && venueId != null) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(280) },
                    label = { Text("A dedication or a word for the host") },
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    "Send to ${share.venueName ?: "the bar"}",
                    enabled = !sending,
                    onClick = {
                        scope.launch {
                            sending = true
                            problem = try {
                                client.requestSong(venueId, spotifyUrl = share.link, note = note)
                                onSent()
                                onDismiss()
                                null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: AudienceKitException) {
                                e.message ?: "Couldn't send it."
                            }
                            sending = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            problem?.let { Text(it, style = HotMessType.body, color = tokens.danger) }
            TextButton(onClick = onDismiss) { Text("Cancel", color = tokens.accentInk) }
        }
    }
}

@Composable
private fun SongRow(song: SpotifyLink, onOpen: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
        song.thumbnailUrl?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(song.title ?: "Spotify", style = HotMessType.subheading, color = tokens.ink)
            song.artist?.let { Text(it, style = HotMessType.caption, color = tokens.inkMuted) }
            Text("From Spotify", style = HotMessType.caption, color = tokens.inkMuted)
        }
        TextButton(onClick = onOpen) { Text("Open in Spotify", color = tokens.accentInk) }
    }
}

/**
 * The night's set list on Spotify, mirrored by the venue: opens it in the person's own Spotify, to save, add to their
 * own playlists or listen to rehearse. Nothing until the venue mirrors it.
 */
@Composable
fun SetListOnSpotifyButton(eventId: String, modifier: Modifier = Modifier) {
    val client = LocalAppGraph.current.audienceKit
    val uri = LocalUriHandler.current
    var setList by remember(eventId) { mutableStateOf<EventSetList?>(null) }
    LaunchedEffect(eventId) {
        setList = try {
            client.setList(eventId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    setList?.let { list ->
        SecondaryButton("Listen to the set list in Spotify", onClick = { uri.openUri(list.spotifyUrl) }, modifier = modifier)
    }
}
