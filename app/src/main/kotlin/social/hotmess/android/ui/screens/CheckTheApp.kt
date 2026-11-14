package social.hotmess.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.AppCheck
import kotlinx.coroutines.launch
import social.hotmess.android.AppGraph
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RowButton
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens

/**
 * Me › Check the app: runs what Hot Mess loads, as the signed-in person, so a screen that comes up empty can be traced
 * to the call behind it (the SDK's `AppCheck`, with the same steps as hot_mess_ios's CheckTheAppScreen). Only reads: it
 * never reports a location or changes anything. "Copy report" includes the recent failed GraphQL requests.
 */
@Composable
fun CheckTheAppScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val check = remember { AppCheck() }
    val state by check.state.collectAsStateWithLifecycle()
    val configuration = graph.configuration
    val title = "Hot Mess for Android ${configuration.versionName} (${configuration.versionCode}), ${configuration.baseUrl}"
    val failures = remember(state) { graph.operations.failures() }

    ScreenScaffold(title = "Check the app", onBack = navigator::back) {
        Feed {
            item {
                DetailSection(null) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        PrimaryButton(
                            when {
                                state.isRunning -> "Checking…"
                                state.steps.isEmpty() -> "Run the check"
                                else -> "Run it again"
                            },
                            onClick = { scope.launch { check.run { runHotMessChecks(graph) } } },
                            enabled = !state.isRunning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.steps.isNotEmpty() && !state.isRunning) {
                            SecondaryButton(
                                "Copy report",
                                onClick = { context.copyReport(check.report(title, graph.operations)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text("Runs each thing the app loads, as you. Nothing is changed.", style = HotMessType.bodySmall, color = tokens.inkMuted)
                    }
                }
            }
            if (state.steps.isNotEmpty()) {
                item(key = "results") { DetailSection("Results") { CardRows(state.steps) { StepRow(it) } } }
            }
            if (failures.isNotEmpty()) {
                item(key = "failures") {
                    DetailSection("Recent failed requests") {
                        CardRows(failures) { event ->
                            RowButton(onClick = null) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(event.name ?: "Unnamed", style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink)
                                    Text(event.error.orEmpty(), style = HotMessType.bodySmall, color = tokens.inkMuted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: AppCheck.Step) {
    val (icon, tint) = when (step.outcome) {
        AppCheck.Outcome.OK -> Icons.Rounded.CheckCircle to tokens.success
        AppCheck.Outcome.EMPTY -> Icons.Rounded.RadioButtonUnchecked to tokens.warning
        AppCheck.Outcome.FAILED -> Icons.Rounded.Error to tokens.danger
        AppCheck.Outcome.SKIPPED -> Icons.Rounded.Block to tokens.inkMuted
    }
    RowButton(onClick = null, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, contentDescription = step.outcome.mark, tint = tint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.name, style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink)
            Text(step.detail, style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
        Text("${step.milliseconds} ms", style = HotMessType.caption, color = tokens.inkMuted.copy(alpha = 0.7f))
    }
}

/** The steps, in the same order and words as iOS: sign-in, version, locale, venues, events, pings, safety. */
private suspend fun AppCheck.runHotMessChecks(graph: AppGraph) {
    val api = graph.api
    step("Signed in") { api.me()?.let { "As ${it.name.substringBefore(' ')}." } }
    step("App version is supported") {
        val versions = api.clientVersions() ?: return@step null
        "Oldest supported ${versions.minimumVersion ?: "unknown"}, newest ${versions.latestVersion ?: "unknown"}."
    }
    val coordinates = graph.location.coordinates.value
    var localeId: String? = null
    if (coordinates != null) {
        step("Your locale") { api.closestLocale(coordinates)?.also { localeId = it.id }?.name }
        step("Venues nearby") { api.venueFences(coordinates).takeIf { it.isNotEmpty() }?.let { "${it.size} venues." } }
    } else {
        skip("Your locale", because = "No position yet (Location is off or hasn't found you).")
    }
    val locale = localeId
    if (locale != null) {
        step("Upcoming events") { api.events(locale).allEvents.takeIf { it.isNotEmpty() }?.let { "${it.size} upcoming." } }
    } else {
        skip("Upcoming events", because = "No locale to look in.")
    }
    step("Pings") {
        val mine = api.myPing()
        val friends = api.friendPings()
        "${if (mine == null) "No ping of yours" else "Your ping is on"}, ${friends.size} from friends."
    }
    step("Safety") {
        api.safety()
        "Loaded."
    }
}

private fun Context.copyReport(report: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Hot Mess check", report))
    Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
}
