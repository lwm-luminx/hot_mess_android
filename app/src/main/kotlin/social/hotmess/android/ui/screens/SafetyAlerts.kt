package social.hotmess.android.ui.screens

import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.DetailRowPadding
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.components.TextRow
import social.hotmess.android.ui.dial
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.HelpVenue
import social.hotmess.core.HotMessApi
import social.hotmess.core.RidePickup
import social.hotmess.core.SafetyAlert
import social.hotmess.core.SafetyAlertDraft
import social.hotmess.core.SafetyAlertState
import social.hotmess.core.SafetyConcern
import social.hotmess.core.SafetyPlace
import social.hotmess.core.SupportLine
import social.hotmess.core.SupportResources
import social.hotmess.core.SupportRide
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// "Get help" at a venue, and the alert once it's sent: a digital "Ask for Angela" (api doc/SAFETY_ALERTS.md,
// doc/design-system/SafetyAlerts.md). Built so someone looking over the user's shoulder sees nothing alarming:
// normal surfaces, no red except 911, and nothing here ever shows in chat or on their profile.

/**
 * One Get help screen's state: what the user fills in, the alert once it's sent, and keeping it fresh. There's
 * no subscription for alerts, so it reads the alert again every few seconds while the screen is open.
 */
class SafetyAlertController(private val api: HotMessApi, private val scope: CoroutineScope, venue: HelpVenue?, alertId: String?) {
    var venue by mutableStateOf(venue)
        private set
    var alert by mutableStateOf<SafetyAlert?>(null)
        private set

    /** Whether anyone on shift hears alerts: without them the screen leads with friend, ride, support and 911. */
    var staffOnShift by mutableStateOf(true)
        private set
    var draft by mutableStateOf(SafetyAlertDraft())
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var now by mutableStateOf(Instant.now())
        private set

    /** The user chose "Keep waiting for staff" on the no-answer screen. */
    var keepsWaiting by mutableStateOf(false)

    /** Set when someone picks it up, for a buzz with no sound. */
    var pickedUp by mutableStateOf(0)
        private set

    /** "Get a ride": the ride apps for where the user is. */
    var rides by mutableStateOf<List<SupportRide>>(emptyList())
        private set

    /** Where a ride picks them up: the venue, else where the phone is. */
    var pickup by mutableStateOf<RidePickup?>(null)
        private set

    private var pendingId: String? = alertId

    /** The ride apps for where the user is, with the pickup at the venue when the app knows where it is. */
    suspend fun loadRides(context: Context, latitude: Double?, longitude: Double?) {
        pickup = venue?.ridePickup ?: if (latitude != null && longitude != null) RidePickup(latitude, longitude) else null
        val region = supportRegion(context, pickup?.latitude, pickup?.longitude)
        rides = quietly { api.supportRides(region) }.orEmpty()
    }

    suspend fun load() {
        val id = pendingId ?: alert?.id
        if (id != null) {
            quietly { api.safetyAlert(id) }?.let(::apply)
            pendingId = null
            return
        }
        val venueId = venue?.id ?: return
        quietly { api.venueSafety(venueId) }?.let { staffOnShift = it.safetyStaffOnShift }
        quietly { api.safetyAlerts() }
            ?.firstOrNull { it.isActive && it.venue.id.equals(venueId, ignoreCase = true) }
            ?.let(::apply)
    }

    /** Ticks the clock every second and reads the open alert every five. */
    suspend fun keepFresh() {
        var ticks = 0
        while (true) {
            delay(1_000)
            now = Instant.now()
            ticks += 1
            val id = alert?.id ?: continue
            if (ticks % 5 == 0 && alert?.isActive == true) quietly { api.safetyAlert(id) }?.let(::apply)
        }
    }

    fun send(done: () -> Unit) {
        val venueId = venue?.id ?: return
        run({ api.sendSafetyAlert(venueId, draft) }) { draft = SafetyAlertDraft(); done() }
    }

    fun addDetails(done: () -> Unit) {
        val id = alert?.id ?: return
        run({ api.updateSafetyAlert(id, draft) }) { draft = SafetyAlertDraft(); done() }
    }

    fun remind() {
        val id = alert?.id ?: return
        keepsWaiting = true
        run({ api.remindSafetyAlert(id) })
    }

    fun cancel() {
        val id = alert?.id ?: return
        run({ api.cancelSafetyAlert(id) })
    }

    fun sendMessage(body: String, done: () -> Unit) {
        val id = alert?.id ?: return
        if (body.isBlank()) return
        run({ api.sendSafetyAlertMessage(id, body) }) { done() }
    }

    private fun run(action: suspend () -> SafetyAlert, then: () -> Unit = {}) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                apply(action())
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Rejected) {
                error = e.detail
            } catch (_: Exception) {
                error = "That didn't go through. Try again, or find a member of staff."
            } finally {
                busy = false
            }
        }
    }

    private fun apply(fresh: SafetyAlert) {
        if (alert?.state == SafetyAlertState.OPEN && fresh.state == SafetyAlertState.ACKNOWLEDGED) pickedUp += 1
        alert = fresh
        if (venue == null) venue = HelpVenue(fresh.venue.id, fresh.venue.name ?: "the venue")
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}

/** The quiet way in on a venue the user is at: `ink-muted` with a shield, not a red SOS. */
@Composable
fun GetHelpRow(onClick: () -> Unit) {
    DetailSection(null) {
        InfoRow("Get help", icon = Icons.Rounded.Shield, onClick = onClick, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
    }
}

/** Get help at [venue], or the alert [alertId] (from a push or Me). */
@Composable
fun GetHelpScreen(venue: HelpVenue?, alertId: String?, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val controller = remember(venue, alertId) { SafetyAlertController(graph.api, scope, venue, alertId) }
    var telling by remember { mutableStateOf(false) }
    var addingMore by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    val context = LocalContext.current
    LaunchedEffect(controller) { controller.load() }
    LaunchedEffect(controller) {
        val position = graph.location.coordinates.value
        controller.loadRides(context, position?.latitude, position?.longitude)
    }
    LaunchedEffect(controller) { controller.keepFresh() }
    LaunchedEffect(controller.pickedUp) {
        if (controller.pickedUp > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    controller.error?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.error = null },
            confirmButton = { TextButton(onClick = { controller.error = null }) { Text("OK") } },
            title = { Text("Couldn't reach staff") },
            text = { Text(message) },
        )
    }

    val title = controller.venue?.name?.let { "Get help at $it" } ?: "Get help"
    val alert = controller.alert
    when {
        telling || addingMore -> ScreenScaffold(
            title = if (telling) "Tell staff quietly" else "Add more",
            onBack = { telling = false; addingMore = false },
        ) {
            TellStaffForm(controller, sending = telling) { telling = false; addingMore = false }
        }
        alert != null -> ScreenScaffold(title = title, onBack = navigator::back) {
            AlertStatus(controller, alert, onAddMore = { addingMore = true }, onSupport = { navigator.openSupport(alert.supportNote) })
        }
        else -> ScreenScaffold(title = title, onBack = navigator::back) {
            Feed {
                item {
                    DetailSection(null, footer = { Footnote("Nothing here shows in chat or on your profile.") }) {
                        if (controller.venue != null && controller.staffOnShift) {
                            HelpRow("Tell staff I feel unsafe", "The staff on shift hear it quietly and can come find you.", Icons.Rounded.Shield) {
                                telling = true
                            }
                            RowDivider()
                        }
                        EscapeRows(controller) { navigator.openSupport(null) }
                    }
                }
                item { EmergencySection() }
            }
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = HotMessType.bodySmall, color = tokens.inkMuted)
}

/** A row on the Get help screen: an icon on `accent-soft`, a title and a line. */
@Composable
private fun HelpRow(title: String, subtitle: String?, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    RowButton(if (enabled) onClick else null) {
        Box(Modifier.size(36.dp).background(tokens.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = HotMessType.label, color = tokens.ink)
            if (subtitle != null) Text(subtitle, style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
    }
}

/** "Message a friend", "Get a ride" and "Need support?". */
@Composable
private fun EscapeRows(controller: SafetyAlertController, onSupport: () -> Unit) {
    val context = LocalContext.current
    val venue = controller.venue
    HelpRow("Message a friend", "Send them where you are.", Icons.Rounded.Forum) {
        context.shareText("${venue?.whereIAm.orEmpty()} Can you come find me?".trim())
    }
    RowDivider()
    RideRows(controller.rides, controller.pickup, venue?.pickup ?: "Pick me up here.")
    RowDivider()
    HelpRow("Need support?", "Free, confidential lines, any time.", Icons.Rounded.Favorite, onClick = onSupport)
}

/**
 * "Get a ride": a row for each ride app the API lists for the region, with the pickup at the venue or where the
 * phone is, then sharing the pickup for any other app or a friend who can drive. Plain links: the app opens if
 * it's installed, its site if not.
 */
@Composable
private fun RideRows(rides: List<SupportRide>, pickup: RidePickup?, shareMessage: String) {
    val context = LocalContext.current
    val pickupLine = when {
        pickup == null -> "Opens the app to set your pickup."
        pickup.name != null -> "Pickup at ${pickup.name}."
        else -> "Pickup where you are now."
    }
    rides.forEach { ride ->
        HelpRow("Get a ride with ${ride.name}", pickupLine, Icons.Rounded.DirectionsCar) { context.openUrl(ride.link(pickup)) }
        RowDivider()
    }
    if (rides.isEmpty()) {
        HelpRow("Get a ride", "Share where to pick you up with a ride app.", Icons.Rounded.DirectionsCar) {
            context.shareText(shareMessage)
        }
    } else {
        HelpRow("Share where to pick me up", "For another ride app, or a friend who can drive.", Icons.Rounded.Share) {
            context.shareText(shareMessage)
        }
    }
}

private fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** "In danger right now? Call 911": the one red thing, always last. */
@Composable
private fun EmergencySection(number: String = "911") {
    val context = LocalContext.current
    DetailSection("In danger right now?", footer = { Footnote("Hot Mess isn't a crisis service. In an emergency, call $number.") }) {
        Surface(color = tokens.dangerSoft, modifier = Modifier.fillMaxWidth()) {
            RowButton({ context.dial(number) }) {
                Icon(Icons.Rounded.Call, contentDescription = null, tint = tokens.danger, modifier = Modifier.size(20.dp))
                Text("Call $number", style = HotMessType.label, color = tokens.danger)
            }
        }
    }
}

/** "Tell staff quietly", or "Add more" once it's sent: where, what and anything else, all optional. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TellStaffForm(controller: SafetyAlertController, sending: Boolean, done: () -> Unit) {
    val draft = controller.draft
    Feed {
        item {
            DetailSection("Where are you?") {
                FlowRow(Modifier.padding(DetailRowPadding), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    SafetyPlace.entries.forEach { place ->
                        Chip(place.label, selected = draft.place == place) { controller.draft = draft.choose(place) }
                    }
                }
            }
        }
        item {
            DetailSection("What's going on?") {
                FlowRow(Modifier.padding(DetailRowPadding), horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    SafetyConcern.entries.forEach { concern ->
                        Chip(concern.label, selected = concern in draft.concerns) { controller.draft = draft.toggle(concern) }
                    }
                }
            }
        }
        item {
            DetailSection(
                "Anything that helps staff spot them",
                footer = {
                    Footnote(
                        if (sending) "You can send without filling anything in. Only staff see who sent this."
                        else "Staff see this straight away.",
                    )
                },
            ) {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { controller.draft = draft.copy(description = it) },
                    placeholder = { Text("What they look like, what they're wearing") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(DetailRowPadding),
                )
            }
        }
        item {
            PrimaryButton(
                text = when {
                    controller.busy -> "Sending…"
                    sending -> "Send to staff"
                    else -> "Add to my alert"
                },
                onClick = { if (sending) controller.send(done) else controller.addDetails(done) },
                enabled = !controller.busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A 36dp pill: `control-fill`, or `accent-soft` with an accent border when chosen. */
@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = Radius.pill,
        color = if (selected) tokens.accentSoft else tokens.controlFill,
        contentColor = if (selected) tokens.accentInk else tokens.ink,
        border = if (selected) BorderStroke(1.dp, tokens.accent) else null,
        modifier = Modifier
            .heightIn(min = 36.dp)
            .semantics { this.selected = selected }
            .clickable(role = Role.Checkbox, onClick = onClick),
    ) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, style = if (selected) HotMessType.label else HotMessType.body)
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun time(iso: String?): String? =
    iso?.let { runCatching { timeFormat.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull() }

/** The alert once sent: steps, what was sent, the private thread, and "I'm OK now"; other ways to get help after 2 minutes. */
@Composable
private fun AlertStatus(controller: SafetyAlertController, alert: SafetyAlert, onAddMore: () -> Unit, onSupport: () -> Unit) {
    var confirmingCancel by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            confirmButton = { TextButton(onClick = { confirmingCancel = false; controller.cancel() }) { Text("I'm OK now, cancel it") } },
            dismissButton = { TextButton(onClick = { confirmingCancel = false }) { Text("Keep it open") } },
            title = { Text("Cancel your alert?") },
            text = { Text("Staff will see that you're OK. You can send another any time.") },
        )
    }

    Feed {
        if (alert.isUnanswered(controller.now) && !controller.keepsWaiting) {
            item {
                DetailSection("Nobody's picked it up yet", footer = { Footnote("Stay where you feel safest. These can help while you wait.") }) {
                    HelpRow("Tell staff again", "It goes to the managers too.", Icons.Rounded.Refresh, enabled = !controller.busy) { controller.remind() }
                    RowDivider()
                    EscapeRows(controller, onSupport)
                    RowDivider()
                    InfoRow("Keep waiting for staff", onClick = { controller.keepsWaiting = true })
                }
            }
        }

        if (alert.isActive) {
            item {
                val acknowledged = alert.state == SafetyAlertState.ACKNOWLEDGED
                DetailSection(
                    if (acknowledged) "Someone's on the way" else "Staff have your alert",
                    footer = {
                        Footnote(
                            if (acknowledged) "Stay where you are if you can. They know where you said you'd be."
                            else "Stay where you feel safest. You'll feel a buzz, with no sound, when someone picks it up. " +
                                "If nobody does in 2 minutes, we'll help you reach a friend, a ride or 911.",
                        )
                    },
                ) {
                    Column(Modifier.padding(DetailRowPadding), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                        Step("Sent to the staff on shift", time(alert.createdAt), StepState.DONE)
                        if (acknowledged) {
                            Step("Seen", null, StepState.DONE)
                            Step("${alert.acknowledgedByName ?: "Someone"} is on the way", time(alert.acknowledgedAt), StepState.CURRENT)
                        } else {
                            Step(if (alert.seen) "Seen by staff" else "Waiting for someone to pick it up", null, StepState.CURRENT)
                            Step("Someone's on the way", null, StepState.UPCOMING)
                        }
                    }
                }
            }
            item {
                DetailSection("What you sent") {
                    TextRow(alert.summary)
                    alert.description?.takeIf { it.isNotBlank() }?.let { EmptyRow(it) }
                    RowDivider()
                    InfoRow("Add more", onClick = onAddMore)
                }
            }
        } else {
            item {
                DetailSection(null) {
                    TextRow(
                        if (alert.state == SafetyAlertState.CANCELLED) "You cancelled your alert. You can send another any time."
                        else "Staff closed your alert. You can send another any time.",
                    )
                }
            }
        }

        if (alert.isActive || alert.messages.isNotEmpty()) {
            item {
                DetailSection("Private thread", footer = { Footnote("Just you and the staff on shift. This isn't in venue chat.") }) {
                    Column(Modifier.padding(DetailRowPadding), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        alert.messages.forEach { message ->
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = if (message.fromStaff) Alignment.Start else Alignment.End,
                            ) {
                                Text(message.senderName, style = HotMessType.caption, color = tokens.inkMuted)
                                Text(
                                    message.body,
                                    style = HotMessType.body,
                                    color = tokens.ink,
                                    modifier = Modifier
                                        .background(if (message.fromStaff) tokens.controlFill else tokens.accentSoft, Radius.bubble)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                        if (alert.isActive) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = reply,
                                    onValueChange = { reply = it },
                                    placeholder = { Text("Message the staff on shift") },
                                    maxLines = 4,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { controller.sendMessage(reply) { reply = "" } },
                                    enabled = reply.isNotBlank() && !controller.busy,
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = tokens.accentInk)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (alert.supportSharedAt != null) {
            item {
                DetailSection("From the staff at ${alert.venue.name ?: "the venue"}") {
                    alert.supportNote?.takeIf { it.isNotBlank() }?.let { TextRow(it); RowDivider() }
                    HelpRow("Need support?", null, Icons.Rounded.Favorite, onClick = onSupport)
                }
            }
        }

        if (alert.isActive) {
            item {
                SecondaryButton(
                    "I'm OK now, cancel the alert",
                    onClick = { confirmingCancel = true },
                    enabled = !controller.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { EmergencySection() }
    }
}

private enum class StepState { DONE, CURRENT, UPCOMING }

/** A step dot (done: a check on `success`; current: an accent ring; not yet: `control-fill`) and its title. */
@Composable
private fun Step(title: String, note: String?, state: StepState) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            StepState.DONE -> Box(Modifier.size(24.dp).background(tokens.success, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.surfaceRaised, modifier = Modifier.size(16.dp))
            }
            StepState.CURRENT -> Surface(shape = CircleShape, color = tokens.accentSoft, border = BorderStroke(2.dp, tokens.accent), modifier = Modifier.size(24.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Schedule, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(14.dp))
                }
            }
            StepState.UPCOMING -> Box(Modifier.size(24.dp).background(tokens.controlFill, CircleShape))
        }
        Column {
            Text(title, style = HotMessType.label, color = if (state == StepState.UPCOMING) tokens.inkMuted else tokens.ink)
            if (note != null) Text(note, style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
    }
}

/**
 * "Need support?": free, confidential crisis and support lines for where the user is, with Call and Text and
 * the numbers written out, then ride apps to get home. 911 comes last. When staff shared it from an alert, their note comes first.
 */
@Composable
fun SupportScreen(sharedNote: String?, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    var resources by remember { mutableStateOf<SupportResources?>(null) }
    var failed by remember { mutableStateOf(false) }
    var rides by remember { mutableStateOf<List<SupportRide>>(emptyList()) }
    val position = graph.location.coordinates.value
    val pickup = position?.let { RidePickup(it.latitude, it.longitude) }
    val pickupMessage = position
        ?.let { "Can you pick me up? I'm here: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, it.latitude, it.longitude) }
        ?: "Can you pick me up?"

    LaunchedEffect(Unit) {
        val region = supportRegion(context, position?.latitude, position?.longitude)
        try {
            resources = graph.api.supportResources(region)
            rides = try {
                graph.api.supportRides(region)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    ScreenScaffold(title = "Need support?", onBack = navigator::back) {
        Feed {
            sharedNote?.takeIf { it.isNotBlank() }?.let { note ->
                item { DetailSection("A note from the venue") { TextRow(note) } }
            }
            item {
                DetailSection(null) {
                    TextRow("You don't have to be in crisis to reach out. The people who answer are there to listen.")
                }
            }
            val loaded = resources
            when {
                loaded != null -> {
                    item {
                        val checked = loaded.checkedDate?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it) }
                        DetailSection(
                            "Talk to someone, any time",
                            footer = { Footnote(if (checked != null) "Numbers for ${loaded.name}, last checked $checked." else "Numbers for ${loaded.name}.") },
                        ) {
                            loaded.lines.forEachIndexed { index, line ->
                                if (index > 0) RowDivider()
                                SupportLineRow(line, first = index == 0)
                            }
                        }
                    }
                    if (rides.isNotEmpty()) {
                        item { DetailSection("Get home safe") { RideRows(rides, pickup, pickupMessage) } }
                    }
                    item { EmergencySection(loaded.emergency) }
                }
                failed -> {
                    item { DetailSection(null) { TextRow("Couldn't load support lines. In the US, call or text 988 any time.") } }
                    item { EmergencySection() }
                }
                else -> item { DetailSection(null) { EmptyRow("Loading…") } }
            }
        }
    }
}

/** A line: its name, who it's for, the numbers written out, then Call and Text. The first line's Call is filled. */
@Composable
private fun SupportLineRow(line: SupportLine, first: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(DetailRowPadding), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(line.name, style = HotMessType.heading, color = tokens.ink)
        line.description?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
        if (line.numbers.isNotEmpty()) Text(line.numbers, style = HotMessType.bodySmall, color = tokens.inkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            line.phone?.let { phone ->
                if (first) PrimaryButton("Call", onClick = { context.dial(phone) }) else SecondaryButton("Call", onClick = { context.dial(phone) })
            }
            line.textNumber?.let { number ->
                SecondaryButton("Text", onClick = {
                    val body = line.textBody?.let { "?body=${Uri.encode(it)}" }.orEmpty()
                    context.openUrl("sms:$number$body")
                })
            }
            if (line.phone == null && line.textNumber == null) {
                line.url?.let { url -> SecondaryButton("Website", onClick = { context.openUrl(url) }) }
            }
        }
    }
}

/** Where the user is as ISO 3166-2 (US-UT) when the phone can tell from their position, else their country. */
private suspend fun supportRegion(context: Context, latitude: Double?, longitude: Double?): String? {
    val country = Locale.getDefault().country.takeIf { it.isNotBlank() }
    if (latitude == null || longitude == null || !Geocoder.isPresent()) return country
    return withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val address = Geocoder(context, Locale.US).getFromLocation(latitude, longitude, 1)?.firstOrNull()
            val code = address?.countryCode ?: return@withContext country
            val area = address.adminArea?.let { US_STATES[it] ?: it.takeIf { name -> name.length <= 3 } }
            if (area != null) "$code-${area.uppercase()}" else code
        } catch (_: Exception) {
            country
        }
    }
}

/** Android's Geocoder names US states in full; the support config keys them by their ISO code. */
private val US_STATES = mapOf(
    "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR", "California" to "CA",
    "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE", "District of Columbia" to "DC", "Florida" to "FL",
    "Georgia" to "GA", "Hawaii" to "HI", "Idaho" to "ID", "Illinois" to "IL", "Indiana" to "IN", "Iowa" to "IA",
    "Kansas" to "KS", "Kentucky" to "KY", "Louisiana" to "LA", "Maine" to "ME", "Maryland" to "MD",
    "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN", "Mississippi" to "MS", "Missouri" to "MO",
    "Montana" to "MT", "Nebraska" to "NE", "Nevada" to "NV", "New Hampshire" to "NH", "New Jersey" to "NJ",
    "New Mexico" to "NM", "New York" to "NY", "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH",
    "Oklahoma" to "OK", "Oregon" to "OR", "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC",
    "South Dakota" to "SD", "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT", "Vermont" to "VT",
    "Virginia" to "VA", "Washington" to "WA", "West Virginia" to "WV", "Wisconsin" to "WI", "Wyoming" to "WY",
)

/** Me > Your safety alerts: still open, and closed in the last day. Each opens its Get help screen. */
@Composable
fun SafetyAlertsScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    var alerts by remember { mutableStateOf<List<SafetyAlert>?>(null) }
    LaunchedEffect(Unit) {
        alerts = try {
            graph.api.safetyAlerts()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }
    ScreenScaffold(title = "Your safety alerts", onBack = navigator::back) {
        Feed {
            item {
                DetailSection(null) {
                    val list = alerts
                    when {
                        list == null -> EmptyRow("Loading…")
                        list.isEmpty() -> EmptyRow("Alerts you send to venue staff show here while they're open, and for a day after.")
                        else -> list.forEachIndexed { index, alert ->
                            if (index > 0) RowDivider()
                            InfoRow(
                                alert.venue.name ?: "A venue",
                                value = listOfNotNull(status(alert), time(alert.createdAt)).joinToString(" · "),
                                onClick = { navigator.openSafetyAlert(alert.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun status(alert: SafetyAlert): String = when (alert.state) {
    SafetyAlertState.OPEN -> "Waiting for staff"
    SafetyAlertState.ACKNOWLEDGED -> "${alert.acknowledgedByName ?: "Staff"} is on it"
    SafetyAlertState.CANCELLED -> "Cancelled"
    SafetyAlertState.CLOSED, SafetyAlertState.UNKNOWN -> "Closed"
}

/** Me > Getting help at a venue: what "Get help" does, before anyone needs it. */
@Composable
fun GettingHelpScreen(navigator: Navigator) {
    ScreenScaffold(title = "Getting help", onBack = navigator::back) {
        Feed {
            item {
                DetailSection(null) {
                    TextRow("When the app places you at a venue, its page has a quiet Get help button. Tap it to tell the staff on shift you feel unsafe, without anyone around you knowing.")
                    TextRow("Say where you are and what's going on if you can, or just send it. Someone on shift picks it up and you'll see who's coming. You can message them privately; it's never in venue chat.")
                    TextRow("If nobody picks it up in 2 minutes, the venue's managers hear it too, and the app helps you reach a friend, a ride or support.")
                    TextRow("Only staff see who sent an alert. The other person is never told.")
                }
            }
            item { EmergencySection() }
        }
    }
}
