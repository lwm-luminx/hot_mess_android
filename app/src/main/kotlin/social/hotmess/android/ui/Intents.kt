package social.hotmess.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import social.hotmess.core.ApiError
import social.hotmess.core.HotMessApi

/** Opens a link in whatever app handles it (a browser, Facebook, Instagram, the dialer). */
fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

fun Context.share(url: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The Messages app, addressed to [smsUri] (`smsto:` one or more numbers) with [body] written. With no app for SMS (a
 * tablet, no SIM) the share sheet gets the same text instead.
 */
fun Context.textMessage(smsUri: String, body: String) {
    try {
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(smsUri)).putExtra("sms_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
        startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Shares a venue's or event's page by a link the API signs to open it for a month ([HotMessApi.shareLink]), so it
 * says it has expired after that. The share sheet opens once the link comes back; when it can't be had,
 * [ShareLinkFailure] says why, and nothing is shared (there's no unsigned link to fall back on).
 */
@Stable
class ShareLinks(private val context: Context, private val api: HotMessApi, private val scope: CoroutineScope) {
    /** Why the last share didn't work, until it's dismissed. */
    var error: String? by mutableStateOf(null)

    private var sharing = false

    fun share(path: String, title: String) {
        if (sharing) return
        sharing = true
        scope.launch {
            try {
                context.share(api.shareLink(path), title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                sharing = false
            }
        }
    }
}

@Composable
fun rememberShareLinks(): ShareLinks {
    val api = LocalAppGraph.current.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(api, context, scope) { ShareLinks(context, api, scope) }
}

/** Why sharing didn't work, when it didn't. */
@Composable
fun ShareLinkFailure(links: ShareLinks) {
    val message = links.error ?: return
    AlertDialog(
        onDismissRequest = { links.error = null },
        confirmButton = { TextButton(onClick = { links.error = null }) { Text("OK") } },
        title = { Text("Couldn't share") },
        text = { Text(message) },
    )
}

/** Directions in the map app. */
fun Context.openMap(latitude: Double, longitude: Double, name: String) {
    openUrl("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(name)})")
}

fun Context.dial(phone: String) {
    try {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.filter { it.isDigit() || it == '+' }}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
