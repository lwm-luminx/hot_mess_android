package social.hotmess.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.AudienceKitClient
import com.audiencekit.CommunityDirectory
import com.audiencekit.CommunityResource
import com.audiencekit.DigitalFlyer
import com.audiencekit.FlyerCopy
import com.audiencekit.FlyerDismissals
import com.audiencekit.FlyerLinePicker
import com.audiencekit.FlyersCardContent
import com.audiencekit.ResourceNeed
import com.audiencekit.ResourceSlot
import com.audiencekit.android.FlyerDismissalPreferences
import com.audiencekit.flyerCopy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.TextRow
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.EmergencyNumber
import social.hotmess.core.RidePickup
import social.hotmess.core.SupportResources
import social.hotmess.core.SupportRide
import java.text.DateFormatSymbols
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Digital flyers on this phone: which flyers and kinds of flyer were hidden, the words around them, and the city's
 * resource directory.
 *
 * Hidden flyers and kinds live in their own shared preferences (com.audiencekit.flyers.xml), left out of Auto Backup and
 * never sent anywhere, because which kinds of help someone hid is nobody else's business. The directory is fetched
 * whole for the city without the session token as soon as the city is known; "I need…" filters it here, so the choice
 * makes no request at all.
 */
class FlyerSettings(context: Context, private val client: AudienceKitClient, private val scope: CoroutineScope) {
    private val store = FlyerDismissalPreferences(context)
    private val dismissals = FlyerDismissals(store)
    private val picker = FlyerLinePicker(store)
    private val _dismissed = MutableStateFlow(dismissals.dismissedFlyers.toSet())
    private val _hidden = MutableStateFlow(dismissals.hiddenCategories)
    private val _copy = MutableStateFlow<FlyerCopy?>(null)
    private val _directory = MutableStateFlow<CommunityDirectory?>(null)
    private val _directoryFailed = MutableStateFlow(false)
    private var directoryJob: Job? = null

    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()
    val hiddenCategories: StateFlow<Set<String>> = _hidden.asStateFlow()
    val copy: StateFlow<FlyerCopy?> = _copy.asStateFlow()
    val directory: StateFlow<CommunityDirectory?> = _directory.asStateFlow()
    val directoryFailed: StateFlow<Boolean> = _directoryFailed.asStateFlow()

    fun visible(content: FlyersCardContent, dismissed: Set<String>, hidden: Set<String>): List<DigitalFlyer> =
        content.flyers.filter { it.id !in dismissed && it.category !in hidden }

    /** The line a flyer shows, picked once when it appears: never the one shown last. */
    fun line(flyer: DigitalFlyer): String? = picker.line(flyer)

    fun categoryLabel(key: String, copy: FlyerCopy?): String =
        copy?.categories?.firstOrNull { it.key == key }?.label
            ?: key.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

    fun dismiss(flyer: DigitalFlyer) {
        dismissals.dismiss(flyer)
        _dismissed.value = dismissals.dismissedFlyers.toSet()
    }

    fun setHidden(category: String, hide: Boolean) {
        dismissals.setHidden(category, hide)
        _hidden.value = dismissals.hiddenCategories
    }

    /** Shows every flyer and kind again. */
    fun reset() {
        dismissals.reset()
        _dismissed.value = emptySet()
        _hidden.value = emptySet()
    }

    suspend fun loadCopy() {
        if (_copy.value != null) return
        try {
            _copy.value = client.flyerCopy()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Fetches the city's directory unless it's already here (or on its way). */
    fun prefetchDirectory(localeId: String?) {
        val id = localeId?.lowercase() ?: return
        if (_directory.value?.locale?.id?.lowercase() == id) return
        if (directoryJob?.isActive == true && fetching == id) return
        directoryJob?.cancel()
        fetching = id
        directoryJob = scope.launch {
            try {
                _directory.value = client.communityDirectory(id)
                _directoryFailed.value = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _directoryFailed.value = true
            }
        }
    }

    private var fetching: String? = null

    /** Forgets what Find Help Nearby had open; the directory itself stays, as it's the same for everyone. */
    fun retry(localeId: String?) {
        _directory.value = null
        prefetchDirectory(localeId)
    }
}

/**
 * Now's digital flyers: cards about what's on in the city, chosen by the server from the city and the time and
 * nothing else. Each one says so and links to the explainer. Hiding a flyer or a kind of flyer stays on this phone.
 */
@Composable
fun FlyersCardView(content: FlyersCardContent) {
    val graph = LocalAppGraph.current
    val settings = graph.flyers
    val dismissed by settings.dismissed.collectAsStateWithLifecycle()
    val hidden by settings.hiddenCategories.collectAsStateWithLifecycle()
    val copy by settings.copy.collectAsStateWithLifecycle()
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    var findingHelp by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { settings.loadCopy() }
    LaunchedEffect(locale?.id) { settings.prefetchDirectory(locale?.id) }

    val flyers = settings.visible(content, dismissed, hidden)
    if (flyers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        flyers.forEach { flyer ->
            FlyerView(flyer, content, settings.categoryLabel(flyer.category, copy)) { findingHelp = it ?: ALL }
        }
        TextButton(onClick = { findingHelp = ALL }, modifier = Modifier.testTag("flyers.findHelp")) {
            Text("Find food, health and support nearby", style = HotMessType.label, color = tokens.accentInk)
        }
    }
    findingHelp?.let { focus ->
        ResourcesDialog(focusResourceId = focus.takeUnless { it == ALL }) { findingHelp = null }
    }
}

private const val ALL = "all"

@Composable
private fun FlyerView(flyer: DigitalFlyer, content: FlyersCardContent, category: String, openResource: (String?) -> Unit) {
    val context = LocalContext.current
    val settings = LocalAppGraph.current.flyers
    val line = remember(flyer.id) { settings.line(flyer) }
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(Radius.photo).background(tokens.surfaceRaised).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(category.uppercase(), style = HotMessType.label, color = tokens.inkMuted, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Flyer options", tint = tokens.inkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Hide this flyer") }, onClick = {
                        menu = false
                        settings.dismiss(flyer)
                    })
                    DropdownMenuItem(text = { Text("Hide all $category flyers") }, onClick = {
                        menu = false
                        settings.setHidden(flyer.category, true)
                    })
                    DropdownMenuItem(text = { Text(content.explainerLabel) }, onClick = {
                        menu = false
                        context.openHttps(content.explainerUrl)
                    })
                }
            }
        }
        line?.let { Text(it, style = HotMessType.label, color = tokens.accentInk) }
        Text(flyer.headline, style = HotMessType.heading, color = tokens.ink)
        flyer.body?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
        val resource = flyer.resource
        val link = flyer.linkUrl?.takeIf { it.startsWith("https://") }
        when {
            resource != null -> TextButton(onClick = { openResource(resource.id) }) {
                Text(flyer.linkLabel ?: "Details", style = HotMessType.label, color = tokens.accentInk)
            }
            link != null -> TextButton(onClick = { context.openHttps(link) }) {
                Text(flyer.linkLabel ?: "Learn more", style = HotMessType.label, color = tokens.accentInk)
            }
        }
        // The server's words, swappable there pending legal review.
        Text(content.disclosure, style = HotMessType.caption, color = tokens.inkMuted)
        TextButton(onClick = { context.openHttps(content.explainerUrl) }, modifier = Modifier.testTag("flyers.explainer")) {
            Text(content.explainerLabel, style = HotMessType.caption, color = tokens.accentInk)
        }
    }
}

/**
 * "Need support?": the region's support lines (they work with no city chosen), then the city's food, shelter, health,
 * support and safety resources, from the directory fetched whole, and one "Get a ride" row.
 * "I need…" filters it on this phone, so choosing a need sends nothing anywhere and nothing is logged.
 *
 * It's a full-screen dialog with its own state, not a navigation route, so nothing lands in the back stack, restored
 * state or favorites. FLAG_SECURE keeps it out of screenshots and blanks it in Recents. "Leave now" closes it and
 * sends the app to the background at once. DV and stalking resources go by their neutral titles in lists and titles.
 */
@Composable
fun ResourcesDialog(focusResourceId: String?, sharedNote: String? = null, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val settings = graph.flyers
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val directory by settings.directory.collectAsStateWithLifecycle()
    val failed by settings.directoryFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var need by remember { mutableStateOf<ResourceNeed?>(null) }
    // Typed and matched on this phone only; it isn't saved, so closing the dialog forgets it.
    var search by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<CommunityResource?>(null) }
    var focused by remember { mutableStateOf(false) }
    var pickingRide by remember { mutableStateOf(false) }
    // The support lines and ride apps for where the phone is: only the region (US-UT) is sent.
    var support by remember { mutableStateOf<SupportResources?>(null) }
    var rides by remember { mutableStateOf<List<SupportRide>>(emptyList()) }
    val position = graph.location.coordinates.value
    val pickup = position?.let { RidePickup(it.latitude, it.longitude) }
    LaunchedEffect(Unit) {
        val region = supportRegion(context, position?.latitude, position?.longitude)
        support = runCatchingUnlessCancelled { graph.api.supportResources(region) }
        rides = runCatchingUnlessCancelled { graph.api.supportRides(region) }.orEmpty()
    }
    LaunchedEffect(locale?.id) { settings.prefetchDirectory(locale?.id) }
    LaunchedEffect(directory) {
        if (!focused && focusResourceId != null) {
            directory?.resources?.firstOrNull { it.id == focusResourceId }?.let {
                opened = it
                focused = true
            }
        }
    }

    val leaveNow: () -> Unit = {
        need = null
        search = ""
        opened = null
        pickingRide = false
        onClose()
        context.findActivity()?.moveTaskToBack(true)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Box(Modifier.fillMaxSize().background(tokens.surface)) {
            val resource = opened
            BackHandler(enabled = resource != null || pickingRide) { if (resource != null) opened = null else pickingRide = false }
            ScreenScaffold(
                title = resource?.title ?: if (pickingRide) "Get a ride" else "Need support?",
                onBack = {
                    when {
                        resource != null -> opened = null
                        pickingRide -> pickingRide = false
                        else -> onClose()
                    }
                },
                actions = {
                    Button(
                        onClick = leaveNow,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.inkMuted),
                        modifier = Modifier.testTag("resources.leaveNow"),
                    ) { Text(directory?.quickExit ?: "Leave now") }
                },
            ) {
                val loaded = directory
                val extras = SupportExtras(sharedNote, support, rides) { pickingRide = true }
                when {
                    resource != null -> ResourceDetail(resource, loaded?.advocacy.orEmpty())
                    pickingRide -> RideOptionsList(rides, pickup, null)
                    loaded != null -> ResourceList(loaded, need, search, extras, { need = it }, { search = it }) { opened = it }
                    locale == null -> Feed {
                        noteItem(extras)
                        item { DetailSection { EmptyRow("Choose a city to see food, shelter and health near you.") } }
                        supportItems(extras)
                    }
                    failed -> Feed {
                        noteItem(extras)
                        item {
                            DetailSection {
                                EmptyRow("Couldn't load resources. Check your connection and try again. The emergency number below works without it.")
                                RowDivider()
                                InfoRow("Try again", onClick = { settings.retry(locale?.id) })
                            }
                        }
                        supportItems(extras)
                    }
                    else -> Feed {
                        noteItem(extras)
                        item { DetailSection { EmptyRow("Loading help near you…") } }
                        supportItems(extras)
                    }
                }
            }
        }
    }
}

/**
 * The list, in order of urgency: the needs (short labels from the server, only the ones with something listed here),
 * a search, then crisis lines with a call button on the row, then everything else, then the emergency number. No state
 * is a dead end: an empty search still offers the crisis lines and a way back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResourceList(
    directory: CommunityDirectory,
    need: ResourceNeed?,
    search: String,
    extras: SupportExtras,
    choose: (ResourceNeed?) -> Unit,
    onSearch: (String) -> Unit,
    open: (CommunityResource) -> Unit,
) {
    val results = remember(directory, need, search) { ResourceBrowser.results(directory, need, search) }
    val needs = remember(directory) { ResourceBrowser.offeredNeeds(directory) }
    val browsing = need == null && search.isBlank()
    Feed {
        noteItem(extras)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("I need…", style = HotMessType.subheading, color = tokens.ink, modifier = Modifier.semantics { heading() })
                // Wraps, so every choice is on screen without scrolling sideways.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FilterChip(selected = need == null, onClick = { choose(null) }, label = { Text("Everything") })
                    needs.forEach { choice ->
                        FilterChip(
                            selected = need?.key == choice.key,
                            onClick = { choose(choice) },
                            // One line per chip; the labels are 18 characters at most.
                            label = { Text(choice.label, maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("resources.need.${choice.key}"),
                        )
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearch,
                    placeholder = { Text("Search this list") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { onSearch("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("resources.search"),
                )
                Text(
                    "For ${directory.locale.name}. Filtered on your phone: what you tap or type here isn't sent anywhere.",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                )
            }
        }
        when {
            results.isEmpty -> {
                item {
                    DetailSection {
                        EmptyRow("Nothing listed here matches that. Try another word, or see everything in ${directory.locale.name}.")
                        RowDivider()
                        InfoRow("Show everything", onClick = {
                            choose(null)
                            onSearch("")
                        })
                    }
                }
                // Never a dead end: the crisis lines are still here.
                val lines = ResourceBrowser.urgent(directory.resources)
                if (lines.isNotEmpty()) item { ResourceSection("Help right now", lines, open) }
            }
            browsing && results.urgent.isNotEmpty() -> {
                item { ResourceSection("Help right now", results.urgent, open) }
                // The lines that work anywhere sit with the city's crisis lines.
                extras.support?.takeIf { it.lines.isNotEmpty() }?.let { item { SupportLinesSection(it) } }
                if (results.rest.isNotEmpty()) item { ResourceSection("More in ${directory.locale.name}", results.rest, open) }
            }
            else -> item { ResourceSection(need?.label ?: "Results", results.urgent + results.rest, open) }
        }
        when {
            browsing && results.urgent.isNotEmpty() -> supportItems(extras, lines = false)
            browsing || results.isEmpty -> supportItems(extras)
            else -> item { EmergencySection(extras.support?.emergency ?: EmergencyNumber.current) }
        }
    }
}

/** What "Need support?" adds around the city's resources: staff's note, the support lines and the way home. */
private class SupportExtras(
    val sharedNote: String?,
    val support: SupportResources?,
    val rides: List<SupportRide>,
    val onRide: () -> Unit,
)

/** Staff's note when they shared this from an alert, first in every state. */
private fun LazyListScope.noteItem(extras: SupportExtras) {
    extras.sharedNote?.takeIf { it.isNotBlank() }?.let { note -> item { DetailSection("A note from the venue") { TextRow(note) } } }
}

/** The support lines, the way home and the emergency number: what every state ends with, so none is a dead end. */
private fun LazyListScope.supportItems(extras: SupportExtras, lines: Boolean = true) {
    if (lines) extras.support?.takeIf { it.lines.isNotEmpty() }?.let { item { SupportLinesSection(it) } }
    item { DetailSection("Get home safe") { GetARideRow(extras.rides, extras.onRide) } }
    item { EmergencySection(extras.support?.emergency ?: EmergencyNumber.current) }
}

private suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

@Composable
private fun ResourceSection(title: String, resources: List<CommunityResource>, open: (CommunityResource) -> Unit) {
    DetailSection(title) {
        resources.forEachIndexed { index, resource ->
            if (index > 0) RowDivider()
            ResourceRow(resource) { open(resource) }
        }
    }
}

/**
 * A resource in the list: what it is, its title (neutral for DV and stalking), when it's next on and who it's for,
 * with a call button of its own so the number is one tap from the list.
 */
@Composable
private fun ResourceRow(resource: CommunityResource, open: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = Space.s4, vertical = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Icon(ResourceBrowser.icon(resource), contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(resource.title, style = HotMessType.subheading, color = tokens.ink)
            val about = ResourceBrowser.kinds(resource) + ResourceWords.badges(resource)
            if (about.isNotEmpty()) Text(about.joinToString(" · "), style = HotMessType.bodySmall, color = tokens.inkMuted)
            ResourceWords.nextSummary(resource)?.let { Text(it, style = HotMessType.caption, color = tokens.inkMuted) }
        }
        resource.phone?.let(ResourceBrowser::dialUri)?.let { uri ->
            IconButton(
                onClick = { context.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse(uri))) },
                modifier = Modifier.size(48.dp).background(tokens.accentSoft, CircleShape),
            ) {
                Icon(Icons.Rounded.Phone, contentDescription = "Call ${resource.title}", tint = tokens.accentInk)
            }
        }
    }
}

/** A resource's page: how to reach it first, then when and where. */
@Composable
private fun ResourceDetail(resource: CommunityResource, advocacy: List<String>) {
    val context = LocalContext.current
    Feed {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                // The page names it; the title bar keeps the neutral title.
                Text(resource.name, style = HotMessType.title, color = tokens.ink, modifier = Modifier.semantics { heading() })
                resource.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.body, color = tokens.inkMuted) }
                (ResourceBrowser.kinds(resource) + ResourceWords.badges(resource)).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = HotMessType.label, color = tokens.ink)
                }
                resource.intakeNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.bodySmall, color = tokens.ink) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                resource.phone?.let { phone ->
                    ResourceBrowser.dialUri(phone)?.let { uri ->
                        Button(
                            onClick = { context.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse(uri))) },
                            modifier = Modifier.fillMaxWidth().testTag("resource.call"),
                        ) {
                            Icon(Icons.Rounded.Phone, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("Call ${ResourceBrowser.phoneText(phone)}", modifier = Modifier.padding(start = Space.s2))
                        }
                    }
                }
                resource.textNumber?.let { text ->
                    ResourceBrowser.dialUri(text, "smsto")?.let { uri ->
                        ResourceAction("Text ${ResourceBrowser.phoneText(text)}", Icons.AutoMirrored.Rounded.Chat) {
                            context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse(uri)))
                        }
                    }
                }
                if (resource.address != null || (resource.latitude != null && resource.longitude != null)) {
                    ResourceAction("Directions", Icons.Rounded.Map) { context.startSafely(directions(resource)) }
                }
                resource.url?.takeIf { it.startsWith("https://") }?.let { url ->
                    ResourceAction("Website", Icons.Rounded.Language) { context.openHttps(url) }
                }
                resource.address?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
            }
        }
        if (resource.sensitive && advocacy.isNotEmpty()) {
            item { DetailSection { advocacy.forEach { Text(it, style = HotMessType.body, modifier = Modifier.padding(Space.s4)) } } }
        }
        if (resource.hours.isNotEmpty()) {
            item { DetailSection("Hours") { SlotRows(resource.hours) } }
        }
        if (resource.sessions.isNotEmpty()) {
            item { DetailSection("When and where") { SlotRows(resource.sessions) } }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                Text(
                    ResourceWords.checkedDate(resource)?.let { "Last checked $it" } ?: "Details not yet checked. Call ahead before you go.",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                )
                resource.sourceUrl?.takeIf { it.startsWith("https://") }?.let { source ->
                    TextButton(onClick = { context.openHttps(source) }) {
                        Text("Where this came from", style = HotMessType.caption, color = tokens.accentInk)
                    }
                }
            }
        }
        item { EmergencySection() }
    }
}

@Composable
private fun ResourceAction(text: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text, modifier = Modifier.padding(start = Space.s2))
    }
}

@Composable
private fun SlotRows(slots: List<ResourceSlot>) {
    slots.forEachIndexed { index, slot ->
        if (index > 0) RowDivider()
        InfoRow(ResourceWords.describe(slot), listOfNotNull(slot.place ?: slot.address, slot.note).joinToString("\n").ifEmpty { null })
    }
}

/** A geo: link to the map app. A DV or stalking resource's name never goes into the map app's history. */
private fun directions(resource: CommunityResource): Intent {
    val lat = resource.latitude
    val lng = resource.longitude
    val query = resource.address ?: if (resource.sensitive) null else resource.name
    val uri = when {
        lat != null && lng != null -> "geo:$lat,$lng?q=" + Uri.encode(query ?: "$lat,$lng")
        else -> "geo:0,0?q=" + Uri.encode(query.orEmpty())
    }
    return Intent(Intent.ACTION_VIEW, Uri.parse(uri))
}

/** Me > Cards you see > Digital flyers: the kinds hidden on this phone, and a way to show everything again. */
@Composable
fun FlyerCategoriesSection() {
    val settings = LocalAppGraph.current.flyers
    val copy by settings.copy.collectAsStateWithLifecycle()
    val hidden by settings.hiddenCategories.collectAsStateWithLifecycle()
    val dismissed by settings.dismissed.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settings.loadCopy() }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        DetailSection("Digital flyers") {
            copy?.categories.orEmpty().forEachIndexed { index, category ->
                if (index > 0) RowDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(category.label, style = HotMessType.body, color = tokens.ink, modifier = Modifier.weight(1f))
                    Switch(checked = category.key !in hidden, onCheckedChange = { settings.setHidden(category.key, !it) })
                }
            }
            if (hidden.isNotEmpty() || dismissed.isNotEmpty()) {
                RowDivider()
                InfoRow("Show all flyers again", onClick = settings::reset)
            }
        }
        Text(
            "Flyers are chosen from your city and the time, nothing else. What you hide stays on this phone: it isn't " +
                "sent to Hot Mess or any analytics, isn't backed up, and uninstalling the app clears it.",
            style = HotMessType.bodySmall,
            color = tokens.inkMuted,
            modifier = Modifier.padding(horizontal = Space.s5),
        )
    }
}

/**
 * How the resource browser filters, orders and names things. All of it runs on the phone over the directory already
 * fetched: nothing here asks the server for anything.
 */
object ResourceBrowser {
    /** Crisis and any-time lines carry this facet; they lead the list. */
    const val URGENT_FACET = "crisis"

    data class Results(val urgent: List<CommunityResource>, val rest: List<CommunityResource>) {
        val isEmpty: Boolean get() = urgent.isEmpty() && rest.isEmpty()
    }

    /** The needs with at least one resource in this city, so no chip leads to an empty list. */
    fun offeredNeeds(directory: CommunityDirectory): List<ResourceNeed> =
        directory.needs.filter { directory.resourcesFor(it).isNotEmpty() }

    fun urgent(resources: List<CommunityResource>): List<CommunityResource> = resources.filter { it.has(URGENT_FACET) }

    /** The resources for a need and a search, crisis lines apart from the rest, each in the server's order. */
    fun results(directory: CommunityDirectory, need: ResourceNeed?, search: String): Results {
        val words = search.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val found = (need?.let(directory::resourcesFor) ?: directory.resources).filter { matches(it, words) }
        return Results(urgent(found), found.filterNot { it.has(URGENT_FACET) })
    }

    /** Every word is somewhere in its title, name, description, kinds, badges or address. */
    fun matches(resource: CommunityResource, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val text = (listOfNotNull(resource.title, resource.name, resource.description, resource.address) +
            kinds(resource) + ResourceWords.badges(resource)).joinToString(" ").lowercase()
        return words.all { it in text }
    }

    private val kindFacets = listOf(
        "crisis" to "Crisis support",
        "food" to "Food",
        "shelter" to "Shelter",
        "health-coverage" to "Health coverage",
        "sti-testing" to "Testing",
        "mental-health" to "Mental health",
    )

    /**
     * What it offers, in a word or two. A DV or stalking resource says nothing here: its neutral title is all a glance
     * at the list should give away.
     */
    fun kinds(resource: CommunityResource): List<String> =
        if (resource.sensitive) emptyList() else kindFacets.filter { resource.has(it.first) }.map { it.second }

    /** The row's icon, by the most urgent thing it offers. Sensitive resources get the same one as any other line. */
    fun icon(resource: CommunityResource): ImageVector = when {
        resource.sensitive || resource.has("crisis") -> Icons.Rounded.SupportAgent
        resource.has("food") -> Icons.Rounded.Restaurant
        resource.has("shelter") -> Icons.Rounded.Hotel
        resource.has("health-coverage") || resource.has("sti-testing") -> Icons.Rounded.LocalHospital
        resource.has("mental-health") -> Icons.Rounded.Forum
        else -> Icons.Rounded.Favorite
    }

    /** A tel: (or smsto:) link for a number as the API stores it: digits, maybe a leading +. */
    fun dialUri(number: String, scheme: String = "tel"): String? {
        val digits = number.filter { it in '0'..'9' }
        if (digits.isEmpty()) return null
        return "$scheme:${if (number.startsWith("+")) "+" else ""}$digits"
    }

    /** A number for reading: North American numbers as (801) 264-9999, anything else as stored. */
    fun phoneText(number: String): String {
        val digits = number.filter { it in '0'..'9' }
        val national = when {
            digits.length == 11 && digits.startsWith("1") && (number.startsWith("+1") || !number.startsWith("+")) -> digits.drop(1)
            digits.length == 10 && !number.startsWith("+") -> digits
            else -> return number
        }
        return "(${national.take(3)}) ${national.substring(3, 6)}-${national.takeLast(4)}"
    }
}

/** Words for when a resource is on, and who it's for. */
object ResourceWords {
    private val badgeFacets = listOf(
        "lgbtq-affirming" to "LGBTQ+ affirming",
        "youth-safe" to "Youth-safe",
        "no-id-required" to "No ID needed",
        "faith-based" to "Faith-based",
        "secular" to "Secular",
    )

    fun badges(resource: CommunityResource): List<String> = badgeFacets.filter { resource.has(it.first) }.map { it.second }

    /** "Thursday · 10:00–14:00" style, with "Every day". Days are 0 (Sunday) to 6. */
    fun describe(slot: ResourceSlot, locale: Locale = Locale.getDefault()): String {
        val names = DateFormatSymbols.getInstance(locale).weekdays // index 1 = Sunday
        val days = dayText(slot.days, locale)
        val from = slot.from?.let { clock(it, locale) }
        val until = slot.until?.let { clock(it, locale) }
        return when {
            from != null && until != null -> "$days · $from–$until"
            from != null -> "$days · from $from"
            until != null -> "$days · until $until"
            else -> days
        }
    }

    /** "Every day", "Thursday", "Monday–Friday" (three or more days in a row) or "Tuesday, Thursday". */
    fun dayText(days: List<Int>, locale: Locale = Locale.getDefault()): String {
        val names = DateFormatSymbols.getInstance(locale).weekdays // index 1 = Sunday
        val sorted = days.distinct().sorted().filter { it in 0..6 }
        if (sorted.isEmpty() || sorted.size == 7) return "Every day"
        val inARow = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
        if (inARow && sorted.size > 2) return "${names[sorted.first() + 1]}–${names[sorted.last() + 1]}"
        return sorted.joinToString(", ") { names[it + 1] }
    }

    fun nextSummary(resource: CommunityResource, now: OffsetDateTime = OffsetDateTime.now()): String? {
        val next = resource.nextSession ?: return null
        val starts = runCatching { OffsetDateTime.parse(next.startsAt) }.getOrNull() ?: return null
        val ends = runCatching { OffsetDateTime.parse(next.endsAt) }.getOrNull()
        if (ends != null && !starts.isAfter(now) && ends.isAfter(now)) return "On now"
        return "Next: " + starts.atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.getDefault()))
    }

    fun checkedDate(resource: CommunityResource): String? {
        val checked = resource.checkedAt ?: return null
        return runCatching { LocalDate.parse(checked).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(checked)
    }

    private fun clock(hhmm: String, locale: Locale): String =
        runCatching { LocalTime.parse(hhmm).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) }
            .getOrDefault(hhmm)
}

private fun Context.openHttps(url: String) {
    if (!url.startsWith("https://")) return
    try {
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
