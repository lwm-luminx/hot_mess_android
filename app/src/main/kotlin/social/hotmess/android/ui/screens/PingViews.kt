package social.hotmess.android.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.Card
import social.hotmess.android.ui.components.DateBadge
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.android.ui.textMessage
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.BlockedUser
import social.hotmess.core.Formatting
import social.hotmess.core.Friend
import social.hotmess.core.HotMessApi
import social.hotmess.core.LoadState
import social.hotmess.core.Ping
import social.hotmess.core.PingChoices
import social.hotmess.core.PingContact
import social.hotmess.core.PingContacts
import social.hotmess.core.PingInvitation
import social.hotmess.core.PingPick
import social.hotmess.core.PingReach
import social.hotmess.core.PingSelection
import social.hotmess.core.PingStrip
import social.hotmess.core.PingTarget
import social.hotmess.core.PingText
import social.hotmess.core.RecordId
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget

// Ping: "I want to go out tonight". The send sheet, your own Ping and friends' Pings on Now, and the
// strip on venue and event pages. Pings are tonight only and clear at 5am.

/** "I'm in" and leaving, one at a time, with what went wrong for [PingErrorDialog]. */
class PingActions(
    private val api: HotMessApi,
    private val scope: CoroutineScope,
    private val onPing: (Ping) -> Unit,
) {
    /** The Ping a request is running for, whose buttons are disabled meanwhile. */
    var busy: String? by mutableStateOf<String?>(null)
        private set

    var error: String? by mutableStateOf<String?>(null)

    fun join(ping: Ping, target: PingTarget?) = perform(ping.id) { api.joinPing(ping.id, target?.id) }

    /** "I'm in" from a texted link, on one pick ([targetId]) or the whole Ping, with the link's token. */
    fun join(invitation: PingInvitation, targetId: String?) =
        perform(invitation.id) { api.joinPing(invitation.id, targetId, invitationToken = invitation.token) }

    fun leave(ping: Ping) = perform(ping.id) { api.leavePing(ping.id) }

    private fun perform(id: String, request: suspend () -> Ping) {
        if (busy != null) return
        busy = id
        scope.launch {
            try {
                onPing(request())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                busy = null
            }
        }
    }
}

/** [PingActions] for a screen; [onPing] gets each Ping back as the API now has it. */
@Composable
fun rememberPingActions(onPing: (Ping) -> Unit): PingActions {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPing)
    val actions = remember { PingActions(graph.api, scope) { latest(it) } }
    PingErrorDialog(actions.error) { actions.error = null }
    return actions
}

@Composable
private fun PingErrorDialog(message: String?, onDismiss: () -> Unit) {
    message ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text("Couldn't update the ping") },
        text = { Text(message) },
    )
}

/** The signed-in user's ID, to tell which pick they're in on. */
@Composable
fun rememberUserId(): String? {
    val user by LocalAppGraph.current.session.user.collectAsStateWithLifecycle()
    return user?.id
}

/** The sender and everyone in, as overlapping faces. */
@Composable
fun CircleFaces(people: List<Friend>, size: Dp = 32.dp, limit: Int = 5) {
    Row(horizontalArrangement = Arrangement.spacedBy(-(size / 4))) {
        people.take(limit).forEach { friend ->
            Avatar(
                friend.avatarUrl,
                friend.name,
                size = size,
                modifier = Modifier.border(2.dp, tokens.surfaceRaised, CircleShape),
            )
        }
        if (people.size > limit) {
            Box(
                Modifier.size(size).clip(CircleShape).background(tokens.controlFill).border(2.dp, tokens.surfaceRaised, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("+${people.size - limit}", style = HotMessType.caption, color = tokens.inkMuted)
            }
        }
    }
}

/** A compact action in a row: "I'm in" filled, "You're in" on `accent-soft`. */
@Composable
fun JoinButton(joined: Boolean, enabled: Boolean, onJoin: () -> Unit) {
    if (joined) {
        Row(
            Modifier.clip(Radius.pill).background(tokens.accentSoft).padding(horizontal = Space.s3, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s1),
        ) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(16.dp))
            Text("You're in", style = HotMessType.bodySmall, color = tokens.accentInk)
        }
    } else {
        Button(
            onClick = onJoin,
            enabled = enabled,
            shape = Radius.pill,
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.accent,
                contentColor = tokens.onAccent,
                disabledContainerColor = tokens.controlFill,
                disabledContentColor = tokens.inkMuted,
            ),
            modifier = Modifier.heightIn(min = 32.dp),
        ) {
            Text("I'm in", style = HotMessType.label)
        }
    }
}

private fun Ping.endsText(): String = "Ends " + (expiresAt?.let { Formatting.clock(it) } ?: "5am")

/** What a pick's row says under its name: when the event starts and who's in. */
private fun PingTarget.subtitle(): String {
    val inCount = joins.distinctBy { it.user.id }.size
    val parts = listOfNotNull(
        event?.let { Formatting.eventSubtitle(it) } ?: venue?.let { "Venue" },
        if (inCount > 0) "$inCount in" else null,
    )
    return parts.joinToString(" · ")
}

/** Your own active Ping on Now: the picks, who's in, when it ends, and Edit / End. */
@Composable
fun MyPingCard(ping: Ping, onEdit: () -> Unit, onEnd: () -> Unit, ending: Boolean) {
    var confirming by remember { mutableStateOf(false) }
    DetailSection("Your ping") {
        Row(
            Modifier.fillMaxWidth().padding(Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ping.placesSummary, style = HotMessType.subheading, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${ping.endsText()} · ${ping.reach.title}", style = HotMessType.bodySmall, color = tokens.inkMuted)
                ping.noteText?.let { Text("“$it”", style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleFaces(ping.circle, size = 28.dp)
            Text(
                ping.whoIsIn ?: "No one's in yet",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RowDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton("Edit", onEdit)
            TextButton(onClick = { confirming = true }, enabled = !ending) {
                Text("End", style = HotMessType.label, color = tokens.danger)
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onEnd()
                }) { Text("End ping", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
            title = { Text("End your ping?") },
            text = { Text("Your friends won't see it any more, and everyone who's in is let go.") },
        )
    }
}

/** A friend's Ping on Now: who, via whom, their note, each pick with "I'm in", and a way out once you're in. */
@Composable
fun FriendPingCard(ping: Ping, userId: String?, actions: PingActions, onOpenTarget: (PingTarget) -> Unit) {
    val busy = actions.busy != null
    var acting by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    var blocking by remember { mutableStateOf<BlockedUser?>(null) }
    val sender = BlockedUser(ping.user.id, ping.user.name, ping.user.avatarUrl)
    Card {
        Row(
            Modifier.fillMaxWidth().padding(Space.s4),
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleFaces(ping.circle, size = 36.dp, limit = 4)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ping.user.name, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ping.viaText?.let { Text(it, style = HotMessType.bodySmall, color = tokens.accentInk) }
                val detail = listOfNotNull(
                    ping.noteText?.let { "“$it”" } ?: if (ping.isAnywhere) Ping.ANYWHERE else null,
                    ping.createdAt?.let { Formatting.clock(it) },
                ).joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(detail, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { acting = true }, modifier = Modifier.testTag("ping.more")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Report or block", tint = tokens.inkMuted)
            }
        }
        if (ping.isAnywhere) {
            RowDivider()
            RowButton(null) {
                Text("Anywhere tonight", style = HotMessType.body, color = tokens.ink, modifier = Modifier.weight(1f))
                JoinButton(ping.isIn(null, userId), enabled = !busy) { actions.join(ping, null) }
            }
        } else {
            ping.targets.forEach { target ->
                RowDivider()
                RowButton({ onOpenTarget(target) }) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(target.name, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(target.subtitle(), style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    JoinButton(ping.isIn(target, userId), enabled = !busy) { actions.join(ping, target) }
                }
            }
        }
        val footer = ping.whoIsIn
        if (footer != null || ping.joined) {
            RowDivider()
            Row(
                Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s2, top = Space.s1, bottom = Space.s1).heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    footer ?: "",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ping.joined) {
                    TextButton(onClick = { actions.leave(ping) }, enabled = !busy) {
                        Text("Leave", style = HotMessType.label, color = tokens.inkMuted)
                    }
                }
            }
        }
    }
    if (acting) {
        ReportOrBlockDialog(
            title = "${ping.user.firstName}'s ping",
            reportLabel = "Report ping",
            person = sender,
            onReport = {
                acting = false
                reporting = ReportSubject(ReportTarget.PING, ping.id, sender)
            },
            onBlock = {
                acting = false
                blocking = it
            },
            onDismiss = { acting = false },
        )
    }
    reporting?.let { ReportDialog(it) { reporting = null } }
    blocking?.let { BlockDialog(it) { blocking = null } }
}

/**
 * On a venue or event page: friends who picked this place tonight, "I'm in" on each of their Pings,
 * and "Ping here" when a Ping can pick it ([onPingHere] is null otherwise).
 */
@Composable
fun PingStripSection(
    pings: List<Ping>,
    pickOf: (Ping) -> PingTarget?,
    userId: String?,
    actions: PingActions,
    onPingHere: (() -> Unit)?,
) {
    val friends = pings.filterNot { it.isMine }
    if (friends.isEmpty() && onPingHere == null) return
    DetailSection(null) {
        if (friends.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(Space.s4),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleFaces(PingStrip.senders(friends), size = 32.dp, limit = 4)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(PingStrip.headline(friends), style = HotMessType.subheading, color = tokens.ink)
                    Text(PingStrip.names(friends), style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            friends.forEach { ping ->
                val target = pickOf(ping)
                RowDivider()
                RowButton(null) {
                    Avatar(ping.user.avatarUrl, ping.user.name, size = 28.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ping.user.firstName, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val detail = listOfNotNull(ping.viaText, ping.noteText?.let { "“$it”" }, ping.whoIsIn).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(detail, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    JoinButton(ping.isIn(target, userId), enabled = actions.busy == null) { actions.join(ping, target) }
                }
            }
        }
        if (onPingHere != null) {
            if (friends.isNotEmpty()) RowDivider()
            InfoRow("Ping here", icon = Icons.Rounded.Campaign, onClick = onPingHere)
        }
    }
}

/**
 * The send sheet: tonight's events in your city first, then its venues, any number picked (or none for
 * "anywhere tonight?"), an optional note, and who can see it. With a Ping already out it edits that one.
 * [preselect] starts with that place picked, for "Ping here".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingSheet(preselect: PingPick?, onDismiss: () -> Unit, onSent: (Ping) -> Unit) {
    val graph = LocalAppGraph.current
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val localeId = locale?.id
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<LoadState<PingChoices>>(LoadState.Loading) }

    // Pings are seen by others, so the terms come first, once.
    var agreed by remember { mutableStateOf(!graph.safety.needsTerms) }
    if (!agreed) {
        TermsDialog(onAgreed = { agreed = true }, onDismiss = onDismiss)
        return
    }

    LaunchedEffect(Unit) { graph.location.refreshLocale() }
    LaunchedEffect(localeId, attempt) {
        if (state !is LoadState.Loaded) state = LoadState.Loading
        state = try {
            LoadState.Loaded(graph.api.pingChoices(localeId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.failed(e)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        when (val current = state) {
            LoadState.Loading -> Box(Modifier.fillMaxWidth().padding(Space.s12), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
            is LoadState.Failed -> Column(
                Modifier.fillMaxWidth().padding(Space.s6),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                Text(current.message, style = HotMessType.body, color = tokens.inkMuted)
                if (current.isRetryable) PrimaryButton("Try again", { attempt++ })
            }
            is LoadState.Loaded -> PingForm(
                choices = current.value,
                preselect = preselect,
                hasLocale = localeId != null,
                onCancel = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                onSend = { selection, note, reach ->
                    graph.api.sendPing(selection.venueIds, selection.eventIds, note, localeId, reach)
                },
                onSent = { ping, done ->
                    onSent(ping)
                    if (done) scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
            )
        }
    }
}

@Composable
private fun PingForm(
    choices: PingChoices,
    preselect: PingPick?,
    hasLocale: Boolean,
    onCancel: () -> Unit,
    onSend: suspend (PingSelection, String, PingReach) -> Ping,
    /** The Ping as sent, and whether the sheet is done; it stays open when the texts to contacts couldn't be made. */
    onSent: (Ping, Boolean) -> Unit,
) {
    val editing = choices.myPing
    val api = LocalAppGraph.current.api
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shown = remember(choices, preselect) {
        choices.including(editing?.targets.orEmpty().mapNotNull { PingPick.of(it) } + listOfNotNull(preselect))
    }
    var selection by remember(editing?.id) { mutableStateOf(PingSelection.of(editing, preselect)) }
    var note by remember(editing?.id) { mutableStateOf(editing?.note.orEmpty()) }
    var reach by remember(editing?.id) { mutableStateOf(editing?.reach ?: PingReach.FRIENDS) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Who gets the link by text, for this send only: editing starts with none, to text more people.
    var contacts by remember { mutableStateOf(PingContacts()) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { context.phoneContact(it) }?.let { contacts = contacts.adding(it) }
    }

    fun send() {
        if (sending) return
        sending = true
        error = null
        scope.launch {
            try {
                val ping = onSend(selection, note, reach)
                if (contacts.isEmpty) {
                    onSent(ping, true)
                    return@launch
                }
                // The signed link names you as the sender; the texts go from your own Messages app.
                val url = try {
                    api.pingShareLink(ping.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (url == null) {
                    onSent(ping, false)
                    error = "Your ping is out, but its link couldn't be made for texting. Send again to try the texts again."
                    return@launch
                }
                context.textMessage(PingText.smsUri(contacts.numbers), PingText.body(ping, url))
                onSent(ping, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                sending = false
            }
        }
    }

    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel", style = HotMessType.label, color = tokens.accentInk) }
            Text(
                if (editing != null) "Edit your ping" else "Ping your friends",
                style = HotMessType.heading,
                color = tokens.ink,
                modifier = Modifier.weight(1f).padding(end = Space.s12),
                textAlign = TextAlign.Center,
            )
        }
        LazyColumn(
            Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = Space.s4, vertical = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            item {
                Text("Where do you want to go tonight?", style = HotMessType.subheading, color = tokens.ink, modifier = Modifier.padding(horizontal = Space.s1))
            }
            if (shown.events.isEmpty() && shown.venues.isEmpty()) {
                item {
                    Card {
                        EmptyRow(
                            if (hasLocale) {
                                "Nothing to pick in your city tonight. Send it anyway to ask where people are heading."
                            } else {
                                "Turn on location to pick places in your city. You can still ask where people are heading."
                            },
                        )
                    }
                }
            }
            if (shown.events.isNotEmpty()) {
                item { PickGroup("Tonight") }
                items(shown.events, key = { "event-${it.id}" }) { event ->
                    val pick = PingPick.EventPick(event)
                    PickRow(selection.contains(pick), onToggle = { selection = selection.toggle(pick) }) {
                        DateBadge(event)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(event.name, style = HotMessType.subheading, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(Formatting.eventSubtitle(event), style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (shown.venues.isNotEmpty()) {
                item { PickGroup("Venues") }
                items(shown.venues, key = { "venue-${it.id}" }) { venue ->
                    val pick = PingPick.VenuePick(venue)
                    PickRow(selection.contains(pick), onToggle = { selection = selection.toggle(pick) }) {
                        RemoteImage(venue.photoUrl, Modifier.size(44.dp).clip(Radius.md))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(venue.name, style = HotMessType.subheading, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            venue.summary?.let {
                                Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(NOTE_LIMIT) },
                placeholder = { Text("Add a note (optional)") },
                shape = Radius.md,
                textStyle = HotMessType.body,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.focus,
                    unfocusedBorderColor = tokens.border,
                    cursorColor = tokens.accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Who can see it", style = HotMessType.caption, color = tokens.inkMuted, modifier = Modifier.padding(horizontal = Space.s1))
            ReachPicker(reach) { reach = it }
            Text(
                "Anyone who joins can bring their friends.",
                style = HotMessType.bodySmall,
                color = if (reach == PingReach.FRIENDS_OF_CIRCLE) tokens.ink else tokens.inkMuted,
                modifier = Modifier.padding(horizontal = Space.s1),
            )
            ContactsPicker(
                contacts,
                onAdd = {
                    try {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    } catch (_: ActivityNotFoundException) {
                        error = "There's no contacts app to pick from."
                    }
                },
                onRemove = { contacts = contacts.removing(it) },
            )
            error?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger, modifier = Modifier.padding(horizontal = Space.s1)) }
            PrimaryButton(
                if (sending) "Sending…" else "Send ping",
                onClick = ::send,
                enabled = !sending,
                icon = Icons.Rounded.Campaign,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (contacts.isEmpty) {
                    "Your friends on Hot Mess will get this. It clears at 5am."
                } else {
                    "Your friends on Hot Mess will get this, and Messages opens to text the link. It clears at 5am."
                },
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
                modifier = Modifier.fillMaxWidth().padding(bottom = Space.s2),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val NOTE_LIMIT = 140

/**
 * "Add contacts" on the send sheet: people from the phone's contacts to text the Ping's link to, as removable chips,
 * one pick at a time. The system picker hands over only the picked number, so it needs no Contacts permission.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactsPicker(contacts: PingContacts, onAdd: () -> Unit, onRemove: (PingContact) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        if (!contacts.isEmpty) {
            Text("Text the link to", style = HotMessType.caption, color = tokens.inkMuted, modifier = Modifier.padding(horizontal = Space.s1))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                contacts.contacts.forEach { contact ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(contact) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(contact.label, style = HotMessType.bodySmall, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (contact.name.isNotBlank()) {
                                    Text(
                                        contact.number,
                                        style = HotMessType.bodySmall,
                                        color = tokens.inkMuted,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = Space.s1),
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove ${contact.label}", tint = tokens.inkMuted, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("ping.contact"),
                    )
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.testTag("ping.addContacts")) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(20.dp))
            Text(
                if (contacts.isEmpty) "Add contacts" else "Add another",
                style = HotMessType.label,
                color = tokens.accentInk,
                modifier = Modifier.padding(start = Space.s1),
            )
        }
    }
}

/** The name and number of a row picked from `CommonDataKinds.Phone`, readable through the picker's grant. */
private fun Context.phoneContact(uri: Uri): PingContact? =
    try {
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0).orEmpty()
            val number = cursor.getString(1) ?: return@use null
            PingContact(name, number)
        }
    } catch (_: SecurityException) {
        null
    }

@Composable
private fun PickGroup(title: String) {
    Text(title, style = HotMessType.caption, color = tokens.inkMuted, modifier = Modifier.padding(start = Space.s1, top = Space.s2))
}

/** A place on the sheet: a card that's ticked when picked. */
@Composable
private fun PickRow(selected: Boolean, onToggle: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Card(
        Modifier
            .clip(Radius.lg)
            .border(if (selected) 2.dp else 0.dp, if (selected) tokens.accent else Color.Transparent, Radius.lg),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Checkbox, onClick = onToggle)
                .padding(horizontal = Space.s3, vertical = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            content()
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Picked" else "Not picked",
                tint = if (selected) tokens.accentInk else tokens.inkMuted,
            )
        }
    }
}

/** "My friends" or "Friends of the circle", side by side; the chosen one sits on `accent-soft`. */
@Composable
private fun ReachPicker(selection: PingReach, onSelect: (PingReach) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Radius.md).background(tokens.controlFill).padding(Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        PingReach.entries.forEach { reach ->
            val selected = reach == selection
            Box(
                Modifier
                    .weight(1f)
                    .clip(Radius.md)
                    .background(if (selected) tokens.surfaceRaised else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(reach) }
                    .padding(vertical = Space.s2),
                contentAlignment = Alignment.Center,
            ) {
                Text(reach.title, style = HotMessType.label, color = if (selected) tokens.ink else tokens.inkMuted)
            }
        }
    }
}

/** What a texted Ping link shows: the Ping as a friend sees it, or its invitation. */
private sealed interface InvitationState {
    data object Loading : InvitationState

    /** The Ping is already among the viewer's (a friend's), or they just joined it. */
    data class Visible(val ping: Ping) : InvitationState

    data class Invited(val invitation: PingInvitation) : InvitationState

    /** The link ran out ([gone]), or the invitation couldn't load. */
    data class Failed(val message: String, val gone: Boolean, val isRetryable: Boolean) : InvitationState
}

/**
 * A Ping link texted from someone's send sheet ([link] carries its token). When the Ping is already a friend's that
 * the viewer can see, it shows as on Now; otherwise the invitation (title, note, picks, when it ends) with "I'm in",
 * which joins with the link's token. Your own Ping is on Now already, so the sheet doesn't open for it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingInvitationSheet(link: AppRoute.NowPing, onDismiss: () -> Unit, onOpenTarget: (PingTarget) -> Unit) {
    val graph = LocalAppGraph.current
    val pingId = link.pingId ?: return
    val token = link.invitation ?: return
    val userId = rememberUserId()
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember(link) { mutableStateOf<InvitationState>(InvitationState.Loading) }
    val actions = rememberPingActions { ping ->
        state = InvitationState.Visible(ping)
        graph.pingUpdated(ping.id)
    }

    LaunchedEffect(link, attempt) {
        state = InvitationState.Loading
        state = coroutineScope<InvitationState> {
            val invitation = async { runCatching { graph.api.pingInvitation(graph.configuration.audienceHost, pingId, token) } }
            // Already visible to the viewer: as a friend's, or their own.
            val visible = try {
                graph.api.nowOrPings(null).let { now -> listOfNotNull(now.myPing) + now.friendPings }
                    .firstOrNull { RecordId.same(it.id, pingId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when {
                visible?.isMine == true -> {
                    invitation.cancel()
                    onDismiss()
                    InvitationState.Loading
                }
                visible != null -> {
                    invitation.cancel()
                    InvitationState.Visible(visible)
                }
                else -> invitation.await().fold(
                    onSuccess = { InvitationState.Invited(it) },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        val gone = PingInvitation.isGone(error)
                        val apiError = ApiError.of(error)
                        InvitationState.Failed(
                            if (gone) "This ping has ended, or its link has expired." else apiError.message ?: "Try again in a moment.",
                            gone = gone,
                            isRetryable = !gone && apiError.isRetryable,
                        )
                    },
                )
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.s4, vertical = Space.s2),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            when (val current = state) {
                InvitationState.Loading -> Box(Modifier.fillMaxWidth().padding(Space.s12), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = tokens.accent)
                }
                is InvitationState.Failed -> Column(
                    Modifier.fillMaxWidth().padding(Space.s4),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.s3),
                ) {
                    Text(current.message, style = HotMessType.body, color = tokens.inkMuted, textAlign = TextAlign.Center)
                    if (current.isRetryable) PrimaryButton("Try again", { attempt++ }) else SecondaryButton("OK", onDismiss)
                }
                is InvitationState.Visible -> {
                    Text("${current.ping.user.firstName}'s ping", style = HotMessType.heading, color = tokens.ink, modifier = Modifier.padding(horizontal = Space.s1))
                    FriendPingCard(current.ping, userId, actions, onOpenTarget)
                }
                is InvitationState.Invited -> InvitationCard(current.invitation, busy = actions.busy != null) { targetId ->
                    actions.join(current.invitation, targetId)
                }
            }
        }
    }
}

/** An invitation to a Ping the viewer can't otherwise see: who, their note, each pick with "I'm in", and the end. */
@Composable
private fun InvitationCard(invitation: PingInvitation, busy: Boolean, onJoin: (targetId: String?) -> Unit) {
    Column(Modifier.padding(horizontal = Space.s1), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(invitation.headline, style = HotMessType.heading, color = tokens.ink)
        invitation.noteText?.let { Text("“$it”", style = HotMessType.body, color = tokens.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        Text("Ends " + (invitation.expiresAt?.let { Formatting.clock(it) } ?: "5am"), style = HotMessType.bodySmall, color = tokens.inkMuted)
    }
    Card {
        if (invitation.targets.isEmpty()) {
            RowButton(null) {
                Text("Anywhere tonight", style = HotMessType.body, color = tokens.ink, modifier = Modifier.weight(1f))
                JoinButton(joined = false, enabled = !busy) { onJoin(null) }
            }
        } else {
            invitation.targets.forEachIndexed { index, target ->
                if (index > 0) RowDivider()
                RowButton(null) {
                    Text(target.name, style = HotMessType.subheading, color = tokens.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    JoinButton(joined = false, enabled = !busy) { onJoin(target.id) }
                }
            }
        }
    }
    Spacer(Modifier.height(Space.s2))
}
