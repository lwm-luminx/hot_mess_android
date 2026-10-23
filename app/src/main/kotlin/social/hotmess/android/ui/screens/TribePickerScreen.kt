package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.DetailInset
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.PickCounter
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.TribeChip
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.Tribe
import social.hotmess.core.TribeCategory
import social.hotmess.core.TribeChoices

/** Chips a category shows before "N more". */
private const val COLLAPSED_COUNT = 8

/**
 * "Your tribes", from Me: the curated list by category, with search, "7 of 31 picked" and the cap. Nobody sees the
 * list; when the user and someone in a venue chat picked the same tribe, just the two of them see it. "Done" saves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TribePickerScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val choices by graph.tribes.choices.collectAsStateWithLifecycle()
    val supported by graph.tribes.isSupported.collectAsStateWithLifecycle()
    // The picks being edited, saved on Done; null until the list loads.
    var picked by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // The cap notice, after a tap on a held-back chip.
    var hitCap by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        graph.tribes.load(force = true)
        if (picked == null) picked = graph.tribes.choices.value?.picked?.toList()
    }

    fun save() {
        val loaded = choices ?: return
        val picks = picked?.toSet() ?: return
        if (picks == loaded.picked) return navigator.back()
        saving = true
        saveError = null
        scope.launch {
            try {
                graph.tribes.save(picks)
                navigator.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = ApiError.of(e).message
            } finally {
                saving = false
            }
        }
    }

    ScreenScaffold(
        title = "Your tribes",
        onBack = navigator::back,
        actions = {
            TextButton(onClick = ::save, enabled = choices != null && picked != null && !saving) {
                Text(if (saving) "Saving…" else "Done", style = HotMessType.label, color = tokens.accentInk)
            }
        },
    ) {
        val loaded = choices
        val picks = picked?.toSet()
        when {
            loaded != null && picks != null -> {
                val editing = loaded.copy(picked = picks)
                fun toggle(tribe: Tribe) {
                    val next = editing.toggled(tribe)
                    hitCap = next == null
                    if (next != null) picked = next.toList()
                }
                Feed {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                            Text(
                                "Pick what you're into. Nobody sees your list. When you and someone in a venue chat share one, " +
                                    "just the two of you see it.",
                                style = HotMessType.body,
                                color = tokens.inkMuted,
                            )
                            PickCounter(picks.size, loaded.cap)
                            if (editing.isAtCap && (hitCap || picks.size > loaded.cap)) {
                                Text(
                                    "That's the most anyone can pick. Remove one to add another.",
                                    style = HotMessType.label,
                                    color = tokens.warning,
                                )
                            }
                            saveError?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
                            OutlinedTextField(
                                value = search,
                                onValueChange = { search = it },
                                placeholder = { Text("Search ${editing.all.size} tribes") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = tokens.inkMuted) },
                                singleLine = true,
                                shape = Radius.md,
                                textStyle = HotMessType.body,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = tokens.focus,
                                    unfocusedBorderColor = tokens.border,
                                    cursorColor = tokens.accent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (search.isBlank()) {
                        if (editing.pickedTribes.isNotEmpty()) {
                            item {
                                DetailSection("Your picks") {
                                    ChipRow {
                                        editing.pickedTribes.forEach { tribe ->
                                            TribeChip(tribe.name, isOn = true, onClick = { toggle(tribe) }, showsRemove = true)
                                        }
                                    }
                                }
                            }
                        }
                        loaded.categories.forEach { category ->
                            item(key = category.key) {
                                CategorySection(category, editing, category.key in expanded, { expanded = expanded + category.key }, ::toggle)
                            }
                        }
                    } else {
                        item { SearchResults(editing, search, ::toggle) }
                    }
                    item {
                        DetailSection(
                            null,
                            footer = {
                                Text(
                                    "Not seeing yours? The list is kept by Hot Mess so it stays kind and safe. Tribes only ever " +
                                        "show up in venue chats, and only to someone who shares one with you. We never use them for " +
                                        "ads, never sell them, and never use them to hide anyone or anywhere from you.",
                                    style = HotMessType.bodySmall,
                                    color = tokens.inkMuted,
                                )
                            },
                        ) {
                            InfoRow("Suggest a tribe", onClick = { context.openUrl("mailto:feedback@hotmess.social?subject=Tribe%20suggestion") })
                        }
                    }
                }
            }
            supported == false -> Message(Icons.Rounded.Groups, "Tribes aren't available yet", "Try again later.")
            loaded == null && supported == null -> Box(Modifier.fillMaxSize().background(tokens.surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.accent)
            }
            else -> Message(Icons.Rounded.ErrorOutline, "Something went wrong", ApiError.GENERIC_MESSAGE)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = DetailInset, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) { content() }
}

@Composable
private fun CategorySection(category: TribeCategory, editing: TribeChoices, expanded: Boolean, onExpand: () -> Unit, onToggle: (Tribe) -> Unit) {
    val shown = if (expanded) category.tribes else category.tribes.take(COLLAPSED_COUNT)
    val hidden = category.tribes.size - shown.size
    DetailSection(category.name) {
        ChipRow {
            shown.forEach { tribe ->
                val on = tribe.id in editing.picked
                TribeChip(tribe.name, isOn = on, onClick = { onToggle(tribe) }, heldBack = !on && editing.isAtCap)
            }
            if (hidden > 0) {
                TextButton(onClick = onExpand, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text("$hidden more", style = HotMessType.label, color = tokens.accentInk)
                }
            }
        }
    }
}

@Composable
private fun SearchResults(editing: TribeChoices, search: String, onToggle: (Tribe) -> Unit) {
    val results = editing.matching(search)
    DetailSection(null) {
        if (results.isEmpty()) {
            Text(
                "No tribes match \"${search.trim()}\".",
                style = HotMessType.body,
                color = tokens.inkMuted,
                modifier = Modifier.padding(horizontal = DetailInset, vertical = Space.s3),
            )
        }
        results.forEachIndexed { index, result ->
            if (index > 0) RowDivider()
            val on = result.tribe.id in editing.picked
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DetailInset, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(result.tribe.name, style = HotMessType.label, color = tokens.ink)
                    Text(result.category, style = HotMessType.caption, color = tokens.inkMuted)
                }
                TribeChip(if (on) "Picked" else "Pick", isOn = on, onClick = { onToggle(result.tribe) }, heldBack = !on && editing.isAtCap)
            }
        }
    }
}
