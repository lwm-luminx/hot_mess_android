package social.hotmess.android.ui.components

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.location.LocationAccess
import social.hotmess.android.location.VenueGeofences
import social.hotmess.android.session.SessionStore
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.Space
import java.util.UUID

/**
 * Asks once, on a launch after location was first allowed, to let Hot Mess notice venue arrivals with the app closed
 * (as iOS asks to upgrade When In Use to Always). Google Play requires this explanation in the app, before Android's
 * own prompt, for background location: what it's for, that it works with the app closed or not in use, and the choice
 * to make. "Not now" never asks again; Android's App info page still offers "Allow all the time" later.
 */
@Composable
fun BackgroundLocationPrompt() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val context = LocalContext.current
    val location = LocalAppGraph.current.location
    val access by location.access.collectAsStateWithLifecycle()
    var showing by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Allowed or not, the geofences already registered start or stop arriving in the background on their own.
        location.start()
    }

    LaunchedEffect(access) {
        if (access != LocationAccess.ALLOWED) return@LaunchedEffect
        showing = BackgroundLocationOffer.isDue(context)
    }

    if (!showing) return
    fun answer(allow: Boolean) {
        BackgroundLocationOffer.asked(context)
        showing = false
        if (allow) launcher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }
    AlertDialog(
        onDismissRequest = { answer(allow = false) },
        title = { Text("Let Hot Mess notice when you arrive?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(
                    "Hot Mess uses your location in the background, even when the app is closed or not in use, to " +
                        "notice when you arrive at or leave a venue. That keeps who's here, the venue's chat and Now up " +
                        "to date without you opening the app.",
                )
                Text("Your location goes only to Hot Mess, to work out which venue you're at.")
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        "To turn it on, choose “Allow all the time” on the next screen."
                    } else {
                        "To turn it on, choose “Allow all the time” next."
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { answer(allow = true) }) { Text("Continue") } },
        dismissButton = { TextButton(onClick = { answer(allow = false) }) { Text("Not now") } },
    )
}

/** When to offer background location: once, on a launch after the one where location was first allowed. */
internal object BackgroundLocationOffer {
    private const val ASKED_KEY = "backgroundLocationAsked"
    private const val ALLOWED_LAUNCH_KEY = "locationAllowedLaunch"

    /** This process: a later launch has another. */
    private val launch = UUID.randomUUID().toString()

    fun isDue(context: Context): Boolean {
        if (!VenueGeofences.hasPreciseLocation(context) || VenueGeofences.hasBackgroundLocation(context)) return false
        val preferences = preferences(context)
        if (preferences.getBoolean(ASKED_KEY, false)) return false
        val allowedLaunch = preferences.getString(ALLOWED_LAUNCH_KEY, null)
        if (allowedLaunch == null) {
            // Allowed just now (or before this was tracked): wait for a later launch rather than stack a second prompt.
            preferences.edit().putString(ALLOWED_LAUNCH_KEY, launch).apply()
            return false
        }
        return allowedLaunch != launch
    }

    fun asked(context: Context) {
        preferences(context).edit().putBoolean(ASKED_KEY, true).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(SessionStore.PREFERENCES, Context.MODE_PRIVATE)
}
