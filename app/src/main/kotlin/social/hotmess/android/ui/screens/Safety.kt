package social.hotmess.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.BlockedUser
import social.hotmess.core.LegalLinks
import social.hotmess.core.ReportReason
import social.hotmess.core.ReportSubject

/**
 * The terms of use, asked once before someone first chats or sends a Ping: no objectionable content or abuse, with
 * the full terms a tap away. Agreeing records it with the API and then runs [onAgreed].
 */
@Composable
fun TermsDialog(onAgreed: () -> Unit, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    fun agree() {
        saving = true
        failure = null
        scope.launch {
            try {
                graph.safety.acceptTerms()
                onAgreed()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "Couldn't save that. Check your connection and try again."
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you post", style = HotMessType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    "Hot Mess has no tolerance for objectionable content or abusive people. By continuing you agree to " +
                        "the terms of use: no harassment, hate, threats, sexual content or spam.",
                    style = HotMessType.body,
                    color = tokens.ink,
                )
                Text(
                    "You can report any message or person, and block anyone. Our moderation team reviews reports " +
                        "within 24 hours and removes content and people who break the rules.",
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                )
                TextButton(onClick = { context.openUrl(LegalLinks.TERMS) }, contentPadding = TextButtonPaddingNone) {
                    Text("Read the terms of use", style = HotMessType.label, color = tokens.accentInk)
                }
                failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::agree, enabled = !saving, modifier = Modifier.testTag("terms.agree")) {
                Text(if (saving) "Saving…" else "I agree")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        containerColor = tokens.surfaceRaised,
    )
}

/**
 * Reports [subject] to the audience's admins: why, and (when there's a person behind it who isn't blocked yet)
 * whether to block them too. Thanks the reporter once it's filed.
 */
@Composable
fun ReportDialog(subject: ReportSubject, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val sender = subject.sender?.takeUnless { graph.safety.state.value?.isBlocked(it.id) == true }
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var block by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    if (sent) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            title = { Text("Thanks for reporting it", style = HotMessType.heading) },
            text = { Text("Our moderation team will review it within 24 hours.", style = HotMessType.body) },
            containerColor = tokens.surfaceRaised,
        )
        return
    }

    fun send() {
        val picked = reason ?: return
        sending = true
        failure = null
        scope.launch {
            try {
                graph.safety.report(subject.copy(sender = sender), picked, block)
                sent = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Rejected) {
                failure = e.message
            } catch (_: Exception) {
                failure = "Couldn't send your report. Check your connection and try again."
            } finally {
                sending = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(subject.question, style = HotMessType.heading) },
        text = {
            Column {
                ReportReason.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == option, role = Role.RadioButton, onClick = { reason = option })
                            .padding(vertical = Space.s1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = reason == option,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = tokens.accent),
                        )
                        Text(option.label, style = HotMessType.body, color = tokens.ink, modifier = Modifier.padding(start = Space.s2))
                    }
                }
                if (sender != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = block, role = Role.Checkbox, onValueChange = { block = it })
                            .padding(top = Space.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = block, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = tokens.accent))
                        Text(
                            "Also block ${sender.name.ifBlank { "them" }}",
                            style = HotMessType.body,
                            color = tokens.ink,
                            modifier = Modifier.padding(start = Space.s2),
                        )
                    }
                }
                failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger, modifier = Modifier.padding(top = Space.s2)) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::send, enabled = reason != null && !sending, modifier = Modifier.testTag("report.submit")) {
                Text(if (sending) "Sending…" else "Report")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = tokens.surfaceRaised,
    )
}

/** Confirms blocking [person], then blocks them: they disappear at once and the moderation team is told. */
@Composable
fun BlockDialog(person: BlockedUser, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var blocking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val name = person.name.ifBlank { null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name?.let { "Block $it?" } ?: "Block them?", style = HotMessType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                Text(
                    "They'll be removed from your chat rooms and Now instantly, and our moderation team will be notified. " +
                        "They aren't told. You can unblock them from Me.",
                    style = HotMessType.body,
                    color = tokens.ink,
                )
                failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    blocking = true
                    failure = null
                    scope.launch {
                        try {
                            graph.safety.block(person)
                            onDismiss()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failure = "Couldn't block them. Please try again."
                        } finally {
                            blocking = false
                        }
                    }
                },
                enabled = !blocking,
                modifier = Modifier.testTag("block.confirm"),
            ) {
                Text(if (blocking) "Blocking…" else "Block", color = tokens.danger)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = tokens.surfaceRaised,
    )
}

/** What can be done about someone else's message or Ping: report it, or block whoever's behind it. */
@Composable
fun ReportOrBlockDialog(
    title: String,
    reportLabel: String,
    person: BlockedUser?,
    onReport: () -> Unit,
    onBlock: (BlockedUser) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = HotMessType.heading, maxLines = 2) },
        text = {
            Column {
                TextButton(onClick = onReport, modifier = Modifier.fillMaxWidth().testTag("safety.report")) {
                    Text(reportLabel, style = HotMessType.body, color = tokens.danger, modifier = Modifier.fillMaxWidth())
                }
                if (person != null) {
                    TextButton(onClick = { onBlock(person) }, modifier = Modifier.fillMaxWidth().testTag("safety.block")) {
                        Text(
                            "Block ${person.name.ifBlank { "them" }}",
                            style = HotMessType.body,
                            color = tokens.danger,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = tokens.surfaceRaised,
    )
}

private val TextButtonPaddingNone = PaddingValues(0.dp)
