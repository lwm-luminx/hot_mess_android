package social.hotmess.android.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
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
 * Find Help Nearby: the city's food, shelter, health, support and safety resources, from the directory fetched whole.
 * "I need…" filters it on this phone, so choosing a need sends nothing anywhere and nothing is logged.
 *
 * It's a full-screen dialog with its own state, not a navigation route, so nothing lands in the back stack, restored
 * state or favorites. FLAG_SECURE keeps it out of screenshots and blanks it in Recents. "Leave now" closes it and
 * sends the app to the background at once. DV and stalking resources go by their neutral titles in lists and titles.
 */
@Composable
fun ResourcesDialog(focusResourceId: String?, onClose: () -> Unit) {
    val graph = LocalAppGraph.current
    val settings = graph.flyers
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val directory by settings.directory.collectAsStateWithLifecycle()
    val failed by settings.directoryFailed.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var need by remember { mutableStateOf<ResourceNeed?>(null) }
    var opened by remember { mutableStateOf<CommunityResource?>(null) }
    var focused by remember { mutableStateOf(false) }
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
        opened = null
        onClose()
        context.findActivity()?.moveTaskToBack(true)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Box(Modifier.fillMaxSize().background(tokens.surface)) {
            val resource = opened
            BackHandler(enabled = resource != null) { opened = null }
            ScreenScaffold(
                title = resource?.title ?: "Find help nearby",
                onBack = { if (resource != null) opened = null else onClose() },
                actions = {
                    Button(
                        onClick = leaveNow,
                        colors = ButtonDefaults.buttonColors(containerColor = tokens.inkMuted),
                        modifier = Modifier.testTag("resources.leaveNow"),
                    ) { Text(directory?.quickExit ?: "Leave now") }
                },
            ) {
                val loaded = directory
                when {
                    resource != null -> ResourceDetail(resource, loaded?.advocacy.orEmpty())
                    loaded != null -> ResourceList(loaded, need, { need = it }) { opened = it }
                    locale == null -> Feed { item { DetailSection { EmptyRow("Choose a city first. Resources are listed by city.") } } }
                    failed -> Feed {
                        item {
                            DetailSection {
                                EmptyRow("Couldn't load resources. In danger right now? Call your local emergency number.")
                                RowDivider()
                                InfoRow("Try again", onClick = { settings.retry(locale?.id) })
                            }
                        }
                    }
                    else -> Feed { item { DetailSection { EmptyRow("Loading…") } } }
                }
            }
        }
    }
}

@Composable
private fun ResourceList(
    directory: CommunityDirectory,
    need: ResourceNeed?,
    choose: (ResourceNeed?) -> Unit,
    open: (CommunityResource) -> Unit,
) {
    val resources = need?.let(directory::resourcesFor) ?: directory.resources
    Feed {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text("I need…", style = HotMessType.subheading, color = tokens.ink)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FilterChip(selected = need == null, onClick = { choose(null) }, label = { Text("Everything") })
                    directory.needs.forEach { choice ->
                        FilterChip(selected = need?.key == choice.key, onClick = { choose(choice) }, label = { Text(choice.label) })
                    }
                }
                Text(
                    "This list is for ${directory.locale.name} and is filtered on your phone. What you choose here isn't sent anywhere.",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                )
            }
        }
        item {
            DetailSection {
                if (resources.isEmpty()) EmptyRow("Nothing listed for this yet in ${directory.locale.name}.")
                resources.forEachIndexed { index, resource ->
                    if (index > 0) RowDivider()
                    val details = listOfNotNull(ResourceWords.nextSummary(resource), ResourceWords.badges(resource).joinToString(" · ").ifEmpty { null })
                    InfoRow(resource.title, details.joinToString("\n").ifEmpty { null }, onClick = { open(resource) })
                }
            }
        }
        item {
            Text(
                "In danger right now? Call your local emergency number.",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
            )
        }
    }
}

@Composable
private fun ResourceDetail(resource: CommunityResource, advocacy: List<String>) {
    val context = LocalContext.current
    Feed {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                // The page names it; the title bar keeps the neutral title.
                Text(resource.name, style = HotMessType.title, color = tokens.ink)
                resource.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.body, color = tokens.inkMuted) }
                ResourceWords.badges(resource).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = HotMessType.label, color = tokens.ink)
                }
                resource.intakeNote?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.bodySmall, color = tokens.ink) }
            }
        }
        if (resource.sensitive && advocacy.isNotEmpty()) {
            item { DetailSection { advocacy.forEach { Text(it, style = HotMessType.body, modifier = Modifier.padding(Space.s4)) } } }
        }
        item {
            DetailSection("Get in touch") {
                resource.phone?.let { phone ->
                    InfoRow(phone, icon = Icons.Rounded.Phone, onClick = { context.startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${phone.filter(Char::isDigit)}"))) })
                }
                resource.textNumber?.let { text ->
                    RowDivider()
                    InfoRow("Text $text", icon = Icons.AutoMirrored.Rounded.Chat, onClick = { context.startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${text.filter(Char::isDigit)}"))) })
                }
                resource.url?.takeIf { it.startsWith("https://") }?.let { url ->
                    RowDivider()
                    InfoRow("Website", icon = Icons.Rounded.Language, onClick = { context.openHttps(url) })
                }
                if (resource.address != null || (resource.latitude != null && resource.longitude != null)) {
                    RowDivider()
                    InfoRow(resource.address ?: "Directions", icon = Icons.Rounded.Map, onClick = { context.startSafely(directions(resource)) })
                }
            }
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
        val days = if (slot.days.isEmpty()) "Every day" else slot.days.sorted().mapNotNull { names.getOrNull(it + 1) }.joinToString(", ")
        val from = slot.from?.let { clock(it, locale) }
        val until = slot.until?.let { clock(it, locale) }
        return when {
            from != null && until != null -> "$days · $from–$until"
            from != null -> "$days · from $from"
            until != null -> "$days · until $until"
            else -> days
        }
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
