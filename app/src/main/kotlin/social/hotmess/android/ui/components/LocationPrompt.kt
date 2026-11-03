package social.hotmess.android.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import social.hotmess.android.location.LocationAccess
import social.hotmess.android.location.LocationProblem
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.openAppSettings
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppLocale
import social.hotmess.core.cities

/** Why there's no position right now, or null when there is one; follows the permission, the device switch and fixes. */
@Composable
fun rememberLocationProblem(): LocationProblem? {
    val location = LocalAppGraph.current.location
    val access by location.access.collectAsStateWithLifecycle()
    val servicesOn by location.servicesOn.collectAsStateWithLifecycle()
    val coordinates by location.coordinates.collectAsStateWithLifecycle()
    return location.problem(access, servicesOn, coordinates)
}

/** What the button that fixes [problem] says. */
fun LocationProblem.actionLabel(): String = when (this) {
    LocationProblem.SERVICES_OFF -> "Open location settings"
    else -> "Turn on location"
}

/**
 * The one way back to a position, whatever's in the way: asks for location when Android still will, opens the app's
 * settings once it won't (denied twice, or "Don't ask again"), and opens the device's location settings when location
 * is off for the whole phone. Never asks in a loop: each tap does one of these.
 */
@Composable
fun rememberTurnOnLocation(): () -> Unit {
    val context = LocalContext.current
    val location = LocalAppGraph.current.location
    val access by location.access.collectAsStateWithLifecycle()
    val servicesOn by location.servicesOn.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        location.onPermissionResult()
    }
    return {
        val activity = context.findActivity()
        // Once asked and refused, Android says no without a prompt unless it would show a rationale (denied only
        // once): go to the app's settings page instead of a button that seems to do nothing.
        val androidWillAsk = access == LocationAccess.NOT_REQUESTED || (activity != null && LOCATION_PERMISSIONS.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        })
        when {
            access == LocationAccess.ALLOWED && !servicesOn -> context.openLocationSettings()
            access == LocationAccess.ALLOWED -> location.start()
            androidWillAsk -> launcher.launch(LOCATION_PERMISSIONS)
            else -> context.openAppSettings()
        }
    }
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * A screen that needs a city, without one: why, the way to turn location on, and a city to choose instead so the
 * screen isn't a dead end.
 */
@Composable
fun LocationNeeded(title: String, text: String) {
    val problem = rememberLocationProblem()
    val turnOn = rememberTurnOnLocation()
    var choosing by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(Space.s8), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Icon(Icons.Rounded.LocationOff, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(40.dp))
            Text(title, style = HotMessType.heading, color = tokens.ink, textAlign = TextAlign.Center)
            Text(problem.explanation(text), style = HotMessType.body, color = tokens.inkMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.s1))
            if (problem != null && problem != LocationProblem.WAITING) {
                PrimaryButton(problem.actionLabel(), turnOn)
            }
            SecondaryButton("Choose a city", onClick = { choosing = true })
        }
    }
    if (choosing) CityPickerSheet(onDismiss = { choosing = false })
}

/** [text], plus what's in the way in words. */
private fun LocationProblem?.explanation(text: String): String = when (this) {
    LocationProblem.DENIED -> "$text Location is off for Hot Mess. Turn it on, or choose a city instead."
    LocationProblem.SERVICES_OFF -> "$text Location is off on this phone. Turn it on in Settings, or choose a city instead."
    LocationProblem.WAITING -> "$text Still finding where you are. You can choose a city meanwhile."
    else -> "$text Turn on location, or choose a city instead."
}

/** The audience's cities, from its venues; picking one uses it until the device has a position of its own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerSheet(onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val current by graph.location.locale.collectAsStateWithLifecycle()
    var cities by remember { mutableStateOf<List<AppLocale>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(failed) {
        if (cities != null) return@LaunchedEffect
        try {
            cities = graph.api.venues(null).cities()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s4)
                .navigationBarsPadding()
                .padding(bottom = Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text("Choose a city", style = HotMessType.title, color = tokens.ink, modifier = Modifier.semantics { heading() })
            Text(
                "Hot Mess shows this city's events, venues and people until it can tell where you are.",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
            )
            val list = cities
            when {
                list == null && failed -> Card {
                    EmptyRow("Couldn't load the cities.")
                    RowDivider()
                    InfoRow("Try again", onClick = { failed = false })
                }
                list == null -> Box(Modifier.fillMaxWidth().padding(Space.s4), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Card { EmptyRow("There are no cities to choose yet.") }
                else -> Card {
                    list.forEachIndexed { index, city ->
                        if (index > 0) RowDivider()
                        InfoRow(
                            city.name,
                            value = if (city.id == current?.id) "Current" else null,
                            icon = Icons.Rounded.LocationCity,
                            onClick = {
                                graph.location.chooseCity(city)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Android's own Location page, where location goes on for the whole device. */
fun Context.openLocationSettings() {
    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { openAppSettings() }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
