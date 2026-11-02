package social.hotmess.android.ui.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.components.TextRow
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.FixDetail
import social.hotmess.core.Formatting
import social.hotmess.core.SuggestionFormat
import social.hotmess.core.SuggestionKind
import social.hotmess.core.SuggestionOutcome
import social.hotmess.core.SuggestionRequest
import social.hotmess.core.SuggestionStatus
import social.hotmess.core.SuggestionSummary
import social.hotmess.core.Venue
import social.hotmess.core.isIn
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/*
 * Suggestions, as on iOS: "Don't see your local spot?" at the end of Venues, "Suggest a fix" on venue and event
 * pages, the forms behind them, the note on a venue reported closed, and Me > Your suggestions. A person checks
 * every suggestion before anything changes, and the venue never sees who sent it.
 */

/** The listing a fix is about. */
data class FixTarget(val isVenue: Boolean, val id: String, val name: String)

/** Fewer listed venues than this and the Venues tab's card is the prominent one. */
private const val THIN_CITY_VENUE_COUNT = 5

private const val CHECKED_LINE = "A person checks every suggestion before it goes up. The venue never sees who sent it."

/**
 * The end of the Venues tab (`venues.thinCity`): "Don't see your local spot?", with the forms to suggest a missing
 * venue or event. Prominent where a city has only a few venues listed, a quiet line everywhere else.
 */
@Composable
fun ThinCityCard(venueCount: Int, cityName: String?, navigator: Navigator) {
    val city = cityName?.takeIf { it.isNotBlank() }
    if (venueCount < THIN_CITY_VENUE_COUNT) {
        Surface(shape = Radius.lg, color = tokens.surfaceRaised, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text("Don't see your local spot?", style = HotMessType.heading, color = tokens.ink)
                Text(
                    (if (city != null) "Help us build out $city. " else "") +
                        "Tell us about a bar, a club or a night we're missing, and we'll check it and add it.",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                )
                PrimaryButton("Suggest a venue", onClick = { navigator.openSuggestListing(event = false) },
                    modifier = Modifier.fillMaxWidth().testTag("venues.suggestVenue"))
                SecondaryButton("Suggest an event", onClick = { navigator.openSuggestListing(event = true) }, modifier = Modifier.fillMaxWidth())
                val count = when {
                    city == null -> ""
                    venueCount == 1 -> "1 venue so far in $city. "
                    else -> "$venueCount venues so far in $city. "
                }
                Text("${count}A person checks every suggestion before it goes up.", style = HotMessType.caption, color = tokens.inkMuted)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text("Don't see your local spot?", style = HotMessType.label, color = tokens.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                SecondaryButton("Suggest a venue", onClick = { navigator.openSuggestListing(event = false) }, modifier = Modifier.testTag("venues.suggestVenue"))
                SecondaryButton("Suggest an event", onClick = { navigator.openSuggestListing(event = true) })
            }
        }
    }
}

/**
 * The note at the top of a venue reported closed: it's left off Tonight while a person checks, and anyone who knows
 * it's still open can say so. [onReopened] hears whether it's still reported closed afterwards.
 */
@Composable
fun ReportedClosedNotice(venueId: String, onReopened: (Boolean) -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var sending by remember(venueId) { mutableStateOf(false) }
    var sent by remember(venueId) { mutableStateOf(false) }
    var failure by remember(venueId) { mutableStateOf<String?>(null) }
    Surface(shape = Radius.lg, color = tokens.warningSoft, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Icon(Icons.Rounded.Warning, contentDescription = null, tint = tokens.warning, modifier = Modifier.size(20.dp))
                Text("People told us it has closed", style = HotMessType.label, color = tokens.ink)
            }
            Text(
                "We're checking. Until we know, it's left off Tonight and Near you. Nothing here has been removed.",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
            )
            when {
                sent -> Text("Thanks, we'll check", style = HotMessType.label, color = tokens.ink)
                else -> SecondaryButton(
                    if (sending) "Sending…" else "It's still open",
                    enabled = !sending,
                    modifier = Modifier.testTag("venue.stillOpen"),
                    onClick = {
                        sending = true
                        failure = null
                        scope.launch {
                            try {
                                val still = graph.api.reportVenueOpen(venueId)
                                sent = true
                                onReopened(still)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = ApiError.of(e).message ?: "Try again in a moment."
                            } finally {
                                sending = false
                            }
                        }
                    },
                )
            }
            failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
        }
    }
}

/** "Suggest a fix" as a row on a venue or event page. */
@Composable
fun SuggestFixRow(onClick: () -> Unit) {
    DetailSection(null) {
        InfoRow("Suggest a fix", icon = Icons.Rounded.Edit, onClick = onClick, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
    }
}

/** "Thanks, it's sent", then [onDone]. */
@Composable
private fun SentDialog(merged: Boolean, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text("OK") } },
        title = { Text("Thanks, it's sent", style = HotMessType.heading) },
        text = { Text(SuggestionOutcome.sentMessage(merged), style = HotMessType.body) },
        containerColor = tokens.surfaceRaised,
    )
}

/** "Suggest a fix for …": what's wrong with a venue or event. */
@Composable
fun SuggestFixScreen(target: FixTarget, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var confirmsClosed by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<Boolean?>(null) }

    fun reportClosed() {
        sending = true
        failure = null
        scope.launch {
            try {
                val request = SuggestionRequest(SuggestionKind.CLOSED, targetId = target.id, localeId = graph.location.locale.value?.id)
                (graph.api.suggest(request) as? SuggestionOutcome.Sent)?.let { sent = it.merged }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                sending = false
            }
        }
    }

    ScreenScaffold(title = "Suggest a fix", onBack = navigator::back) {
        Feed {
            item {
                DetailSection(null, footer = { Footnote("A person checks it first. ${target.name} never sees who sent it.") }) {
                    FixOption(
                        "Fix a detail",
                        if (target.isVenue) "Name, phone, website or description" else "Name, times, ticket link or cancelled",
                        Icons.Rounded.Edit,
                        tint = tokens.accentInk,
                    ) { navigator.openFixDetail(target) }
                    if (target.isVenue) {
                        RowDivider()
                        FixOption("It's closed for good", "Not just tonight: it has shut for good", Icons.Rounded.Close, tint = tokens.danger, enabled = !sending) {
                            confirmsClosed = true
                        }
                        RowDivider()
                        FixOption("Add a missing event", "A night here that isn't listed", Icons.Rounded.EventAvailable, tint = tokens.accentInk) {
                            navigator.openSuggestListing(event = true, venueId = target.id, venueName = target.name)
                        }
                    }
                }
            }
            failure?.let { item { Text(it, style = HotMessType.bodySmall, color = tokens.danger) } }
        }
    }

    if (confirmsClosed) {
        AlertDialog(
            onDismissRequest = { confirmsClosed = false },
            title = { Text("Not just tonight?", style = HotMessType.heading) },
            text = { Text("We'll check before anything changes.", style = HotMessType.body) },
            confirmButton = {
                TextButton(onClick = {
                    confirmsClosed = false
                    reportClosed()
                }) { Text("It's closed for good", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmsClosed = false }) { Text("Cancel") } },
            containerColor = tokens.surfaceRaised,
        )
    }
    sent?.let { merged -> SentDialog(merged) { sent = null; navigator.closeSuggestion() } }
}

@Composable
private fun FixOption(title: String, help: String, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(DetailRowPadding),
    ) {
        Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.12f), Radius.md), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = HotMessType.label, color = tokens.ink)
            Text(help, style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = HotMessType.bodySmall, color = tokens.inkMuted)
}

/** One editable field of a fix: its API name, label and what it is now. */
private data class FixField(val key: String, val label: String, val current: String?, val keyboard: KeyboardType = KeyboardType.Text, val multiline: Boolean = false)

/**
 * "Fix a detail": the listing's details as they are, any of which the member can change. Only what they change is
 * sent, with links that show it. A changed website or ticket link needs a link to back it up.
 */
@Composable
fun FixDetailScreen(target: FixTarget, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fields by remember { mutableStateOf<List<FixField>?>(null) }
    var times by remember { mutableStateOf<Pair<Instant, Instant?>?>(null) }
    val text = remember { mutableStateOf(mapOf<String, String>()) }
    var startsAt by remember { mutableStateOf<Instant?>(null) }
    var endsAt by remember { mutableStateOf<Instant?>(null) }
    var canceled by remember { mutableStateOf(false) }
    val links = remember { mutableStateListOf<String>() }
    var newLink by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(target) {
        try {
            val loaded = if (target.isVenue) {
                val state = graph.api.venueSuggestionState(target.id)
                listOf(
                    FixField("name", "Name", state?.name ?: target.name),
                    FixField("phone", "Phone", state?.phone, KeyboardType.Phone),
                    FixField("website", "Website", state?.website, KeyboardType.Uri),
                    FixField("description", "Description", state?.description, multiline = true),
                )
            } else {
                val event = graph.api.event(target.id)
                times = event.startAt to event.endAt
                startsAt = event.startAt
                endsAt = event.endAt
                listOf(
                    FixField("name", "Name", event.name),
                    FixField("ticket_url", "Ticket link", null, KeyboardType.Uri),
                )
            }
            text.value = loaded.associate { it.key to it.current.orEmpty() }
            fields = loaded
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = ApiError.of(e).message ?: "Couldn't load the details. Try again in a moment."
            fields = emptyList()
        }
    }

    val shown = fields
    val proposed = FixDetail.proposed(
        fields = shown.orEmpty().associate { it.key to (it.current to text.value[it.key].orEmpty()) },
        start = times?.first?.let { was -> startsAt?.let { was to it } },
        end = times?.second?.let { was -> endsAt?.let { was to it } },
        canceled = canceled,
    )
    val evidence = if (newLink.isBlank()) links.toList() else links + newLink.trim()
    val needsLink = FixDetail.needsLink(proposed)
    val canSend = proposed.isNotEmpty() && (!needsLink || evidence.isNotEmpty()) && !sending

    fun send() {
        sending = true
        failure = null
        scope.launch {
            try {
                val request = SuggestionRequest(
                    SuggestionKind.CORRECTION,
                    targetId = target.id,
                    localeId = graph.location.locale.value?.id,
                    proposed = proposed,
                    body = note,
                    evidence = evidence,
                )
                (graph.api.suggest(request) as? SuggestionOutcome.Sent)?.let { sent = it.merged }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                sending = false
            }
        }
    }

    ScreenScaffold(
        title = "Fix a detail",
        onBack = navigator::back,
        actions = {
            TextButton(onClick = ::send, enabled = canSend, modifier = Modifier.testTag("fixDetail.send")) {
                Text(
                    when {
                        sending -> "Sending…"
                        proposed.size <= 1 -> "Send fix"
                        else -> "Send ${proposed.size} fixes"
                    },
                )
            }
        },
    ) {
        Feed {
            if (shown == null) {
                item { DetailSection(target.name) { EmptyRow("Loading…") } }
            } else {
                item {
                    DetailSection(target.name, footer = { Footnote("Change what's wrong. Only what you change is sent.") }) {
                        shown.forEach { field ->
                            val value = text.value[field.key].orEmpty()
                            val changed = field.key in proposed
                            Column(
                                Modifier.fillMaxWidth().background(if (changed) tokens.accentSoft else tokens.surfaceRaised).padding(DetailRowPadding),
                                verticalArrangement = Arrangement.spacedBy(Space.s1),
                            ) {
                                Text(field.label, style = HotMessType.caption, color = tokens.inkMuted)
                                if (changed && !field.current.isNullOrBlank()) {
                                    Text("Now ${field.current}", style = HotMessType.caption, color = tokens.inkMuted, textDecoration = TextDecoration.LineThrough)
                                }
                                OutlinedTextField(
                                    value = value,
                                    onValueChange = { text.value = text.value + (field.key to it) },
                                    singleLine = !field.multiline,
                                    minLines = if (field.multiline) 2 else 1,
                                    maxLines = if (field.multiline) 6 else 1,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = field.keyboard,
                                        capitalization = if (field.keyboard == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        startsAt?.let { start ->
                            RowDivider()
                            InfoRow("Starts", Formatting.dateTime(start), onClick = {
                                pickDateTime(context, start) { picked ->
                                    // An end that's no longer after the start moves with it.
                                    endsAt = endsAt?.let { end -> if (end.isBefore(picked)) picked else end }
                                    startsAt = picked
                                }
                            })
                        }
                        endsAt?.let { end ->
                            RowDivider()
                            InfoRow("Ends", Formatting.dateTime(end), onClick = { pickDateTime(context, end) { endsAt = it } })
                        }
                        if (!target.isVenue) {
                            RowDivider()
                            CheckRow("It's cancelled", canceled) { canceled = it }
                        }
                    }
                }
                item {
                    DetailSection(
                        "How do you know?",
                        footer = {
                            Footnote(
                                if (needsLink) "A new website or ticket link needs a link that shows it, like the venue's own page or post."
                                else "A link to the venue's own page or post helps us check it faster.",
                            )
                        },
                    ) {
                        links.forEachIndexed { index, link ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                                Text(link, style = HotMessType.body, color = tokens.ink, maxLines = 1, modifier = Modifier.weight(1f))
                                IconButton(onClick = { links.removeAt(index) }) { Icon(Icons.Rounded.Close, contentDescription = "Remove link") }
                            }
                            RowDivider()
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(DetailRowPadding)) {
                            OutlinedTextField(
                                value = newLink,
                                onValueChange = { newLink = it },
                                placeholder = { Text("Add a link") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                links.add(newLink.trim())
                                newLink = ""
                            }, enabled = newLink.isNotBlank()) { Text("Add") }
                        }
                    }
                }
                item { NoteSection(note) { note = it } }
            }
            failure?.let { item { Text(it, style = HotMessType.bodySmall, color = tokens.danger) } }
        }
    }
    sent?.let { merged -> SentDialog(merged) { sent = null; navigator.closeSuggestion() } }
}

@Composable
private fun NoteSection(note: String, onChange: (String) -> Unit) {
    DetailSection(null, footer = { Footnote(CHECKED_LINE) }) {
        OutlinedTextField(
            value = note,
            onValueChange = onChange,
            placeholder = { Text("Anything else? (optional)") },
            minLines = 2,
            maxLines = 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().padding(DetailRowPadding),
        )
    }
}

@Composable
private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = tokens.accent))
        Text(title, style = HotMessType.body, color = tokens.ink)
    }
}

/** The platform's date picker, then its time picker, starting from [from]. */
private fun pickDateTime(context: Context, from: Instant, onPicked: (Instant) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = from.atZone(zone)
    DatePickerDialog(context, { _, year, month, day ->
        TimePickerDialog(context, { _, hour, minute ->
            onPicked(ZonedDateTime.of(LocalDate.of(year, month + 1, day), LocalTime.of(hour, minute), zone).toInstant())
        }, start.hour, start.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}

/** Tonight at [hour]:[minute], as the event form starts. */
private fun tonightAt(hour: Int, minute: Int): Instant =
    ZonedDateTime.of(LocalDate.now(), LocalTime.of(hour, minute), ZoneId.systemDefault()).toInstant()

/**
 * "Suggest a venue" or "Suggest an event": something Hot Mess is missing. Only the name and where it is are needed
 * (and when, for an event). When it's already listed, nothing is filed and the member is shown the listing instead.
 * [venueId] fixes the event's venue when it was suggested from the venue's page.
 */
@Composable
fun SuggestListingScreen(event: Boolean, venueId: String?, venueName: String?, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var venues by remember { mutableStateOf<List<Venue>>(emptyList()) }
    var pickedVenue by remember { mutableStateOf<Pair<String, String>?>(venueId?.let { it to venueName.orEmpty() }) }
    var picking by remember { mutableStateOf(false) }
    var venueTyped by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf(tonightAt(21, 0)) }
    var endsAt by remember { mutableStateOf<Instant?>(null) }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var alreadyListed by remember { mutableStateOf<SuggestionOutcome.AlreadyListed?>(null) }
    var sent by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(event, venueId) {
        if (!event || venueId != null) return@LaunchedEffect
        venues = try {
            // The API lists every venue, the city's first; the picker offers only the city's.
            val localeId = graph.location.locale.value?.id
            graph.api.venues(localeId).filter { localeId == null || it.isIn(localeId) }.sortedBy { it.name.lowercase(Locale.getDefault()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    val canSend = !sending && name.isNotBlank() && (if (event) pickedVenue != null || venueTyped.isNotBlank() else address.isNotBlank())

    fun send(notListed: Boolean) {
        sending = true
        failure = null
        scope.launch {
            try {
                val proposed = buildMap<String, Any?> {
                    put("name", name.trim())
                    if (event) {
                        val picked = pickedVenue
                        if (picked != null) put("venue_id", picked.first) else put("venue_name", venueTyped.trim())
                        put("start_at", SuggestionFormat.timestamp(startsAt))
                        endsAt?.let { put("end_at", SuggestionFormat.timestamp(it)) }
                        link.trim().takeIf { it.isNotEmpty() }?.let { put("ticket_url", it) }
                    } else {
                        put("address", address.trim())
                        geocode(context, address.trim())?.let { (latitude, longitude) ->
                            put("latitude", latitude)
                            put("longitude", longitude)
                        }
                        link.trim().takeIf { it.isNotEmpty() }?.let { put("website", it) }
                    }
                }
                val request = SuggestionRequest(
                    if (event) SuggestionKind.MISSING_EVENT else SuggestionKind.MISSING_VENUE,
                    localeId = graph.location.locale.value?.id,
                    proposed = proposed,
                    body = note,
                    evidence = listOfNotNull(link.trim().takeIf { it.isNotEmpty() }),
                    notListed = notListed,
                )
                when (val outcome = graph.api.suggest(request)) {
                    is SuggestionOutcome.AlreadyListed -> alreadyListed = outcome
                    is SuggestionOutcome.Sent -> sent = outcome.merged
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                sending = false
            }
        }
    }

    ScreenScaffold(
        title = if (event) "Suggest an event" else "Suggest a venue",
        onBack = navigator::back,
        actions = {
            TextButton(onClick = { send(notListed = false) }, enabled = canSend, modifier = Modifier.testTag("suggest.send")) {
                Text(if (sending) "Sending…" else "Send")
            }
        },
    ) {
        Feed {
            item {
                DetailSection(
                    "Name",
                    footer = { Footnote(if (event) "Only the name, where and when are needed." else "Only the name and where it is are needed.") },
                ) {
                    FormField(name, if (event) "Name of the night" else "Name of the bar or club", Modifier.testTag("suggest.name"),
                        capitalization = KeyboardCapitalization.Words) { name = it }
                }
            }
            if (event) {
                item {
                    DetailSection("Where") {
                        if (venueId != null) {
                            TextRow(venueName.orEmpty())
                        } else {
                            Box {
                                InfoRow("Venue", pickedVenue?.second ?: "Somewhere not listed", onClick = { picking = true })
                                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                                    DropdownMenuItem(text = { Text("Somewhere not listed") }, onClick = {
                                        pickedVenue = null
                                        picking = false
                                    })
                                    venues.forEach { venue ->
                                        DropdownMenuItem(text = { Text(venue.name) }, onClick = {
                                            pickedVenue = venue.id to venue.name
                                            picking = false
                                        })
                                    }
                                }
                            }
                            if (pickedVenue == null) {
                                RowDivider()
                                FormField(venueTyped, "Where is it?", capitalization = KeyboardCapitalization.Words) { venueTyped = it }
                            }
                        }
                    }
                }
                item {
                    DetailSection("When") {
                        InfoRow("Starts", Formatting.dateTime(startsAt), onClick = {
                            pickDateTime(context, startsAt) { picked ->
                                endsAt = endsAt?.let { end -> if (end.isBefore(picked)) picked else end }
                                startsAt = picked
                            }
                        })
                        RowDivider()
                        CheckRow("Ends", endsAt != null) { on -> endsAt = if (on) startsAt.plusSeconds(3 * 3600) else null }
                        endsAt?.let { end ->
                            InfoRow("Ends at", Formatting.dateTime(end), onClick = {
                                pickDateTime(context, end) { picked -> endsAt = if (picked.isBefore(startsAt)) startsAt else picked }
                            })
                        }
                    }
                }
                item {
                    DetailSection(null, footer = { Footnote("A link helps us check it faster.") }) {
                        FormField(link, "Ticket or event link (optional)", keyboard = KeyboardType.Uri) { link = it }
                    }
                }
            } else {
                item {
                    DetailSection("Where is it?") {
                        FormField(address, "Street address", Modifier.testTag("suggest.address"), maxLines = 3) { address = it }
                    }
                }
                item {
                    DetailSection(null, footer = { Footnote("A link helps us check it faster.") }) {
                        FormField(link, "Website or Instagram (optional)", keyboard = KeyboardType.Uri) { link = it }
                    }
                }
            }
            item { NoteSection(note) { note = it } }
            failure?.let { item { Text(it, style = HotMessType.bodySmall, color = tokens.danger) } }
        }
    }

    alreadyListed?.let { listed ->
        AlertDialog(
            onDismissRequest = { alreadyListed = null },
            title = { Text("It's already on Hot Mess", style = HotMessType.heading) },
            text = {
                Text(
                    if (listed.isVenue) "We found a venue with a name like that close by. Is this the one?"
                    else "We found an event like that at the same place and time. Is this the one?",
                    style = HotMessType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    alreadyListed = null
                    navigator.closeSuggestion()
                    navigator.open(listed.route)
                }) { Text("Open ${listed.name}") }
            },
            dismissButton = {
                TextButton(onClick = {
                    alreadyListed = null
                    send(notListed = true)
                }, modifier = Modifier.testTag("suggest.differentPlace")) {
                    Text(if (listed.isVenue) "No, it's a different place" else "No, it's a different event")
                }
            },
            containerColor = tokens.surfaceRaised,
        )
    }
    sent?.let { merged -> SentDialog(merged) { sent = null; navigator.closeSuggestion() } }
}

@Composable
private fun FormField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    maxLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder) },
        singleLine = maxLines == 1,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            capitalization = if (keyboard == KeyboardType.Text) capitalization else KeyboardCapitalization.None,
            autoCorrectEnabled = keyboard == KeyboardType.Text,
        ),
        modifier = modifier.fillMaxWidth().padding(DetailRowPadding),
    )
}

/** A typed address as a pin, when the platform's geocoder knows it; null otherwise. */
@Suppress("DEPRECATION")
private suspend fun geocode(context: Context, address: String): Pair<Double, Double>? {
    if (address.isEmpty() || !Geocoder.isPresent()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context).getFromLocationName(address, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}

/**
 * Me > Your suggestions: what the member suggested, newest first, with where each is. One that wasn't taken shows the
 * reviewer's reason. It never says whether the venue was asked.
 */
@Composable
fun YourSuggestionsScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    var suggestions by remember { mutableStateOf<List<SuggestionSummary>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            suggestions = graph.api.mySuggestions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = ApiError.of(e).message ?: "Couldn't load your suggestions."
            suggestions = emptyList()
        }
    }
    ScreenScaffold(title = "Your suggestions", onBack = navigator::back) {
        Feed {
            item {
                DetailSection(null, footer = { Footnote("The venue never sees who sent a suggestion. New accounts can send 3 suggestions a day.") }) {
                    val list = suggestions
                    when {
                        list == null -> EmptyRow("Loading…")
                        failure != null -> EmptyRow(failure.orEmpty())
                        list.isEmpty() -> EmptyRow("No suggestions yet. Suggest a missing venue from the Venues tab, or a fix from any venue or event page.")
                        else -> list.forEachIndexed { index, suggestion ->
                            if (index > 0) RowDivider()
                            SuggestionRow(suggestion) { navigator.open(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: SuggestionSummary, open: (AppRoute) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(DetailRowPadding), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(suggestion.title, style = HotMessType.label, color = tokens.ink, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s2))
            val tint = when (suggestion.status) {
                SuggestionStatus.APPROVED -> tokens.success
                SuggestionStatus.REJECTED -> tokens.inkMuted
                else -> tokens.accentInk
            }
            Text(
                suggestion.badge,
                style = HotMessType.caption,
                color = tint,
                modifier = Modifier.background(tint.copy(alpha = 0.14f), Radius.pill).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        val details = buildList {
            add(suggestion.kind.title)
            suggestion.created?.let { add(Formatting.dateTime(it)) }
            if (suggestion.reporterCount > 1) add("You and ${suggestion.reporterCount - 1} others suggested it")
        }
        Text(details.joinToString(" · "), style = HotMessType.bodySmall, color = tokens.inkMuted)
        suggestion.rejectionReason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                reason,
                style = HotMessType.bodySmall,
                color = tokens.ink,
                modifier = Modifier.fillMaxWidth().background(tokens.controlFill, Radius.sm).padding(10.dp),
            )
        }
        suggestion.addedRoute?.let { route -> SecondaryButton("Open", onClick = { open(route) }) }
    }
}

/** Me > "Your suggestions", as a row. */
@Composable
fun YourSuggestionsRow(onClick: () -> Unit) {
    InfoRow("Your suggestions", icon = Icons.Rounded.Lightbulb, onClick = onClick, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
}
