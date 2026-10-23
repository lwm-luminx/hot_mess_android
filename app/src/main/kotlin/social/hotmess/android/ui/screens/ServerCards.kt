package social.hotmess.android.ui.screens

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import com.audiencekit.AudienceKitClient
import com.audiencekit.CardCategory
import com.audiencekit.CardContent
import com.audiencekit.CardFilter
import com.audiencekit.CardLayout
import com.audiencekit.Coordinates
import com.audiencekit.LayoutCard
import com.audiencekit.LayoutClient
import com.audiencekit.LayoutScreen
import com.audiencekit.PromoCard
import com.audiencekit.PromoViewRecorder
import com.audiencekit.android.CardPreferences
import com.audiencekit.cardCategories
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
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
import social.hotmess.core.AppRoute
import android.os.SystemClock

/**
 * What a screen's cards are drawn from: the server's ranked layout (`Audience.layout`) when it can be had, else the
 * screen's built-in order. The cards only order the screen and point at records by id; the content still comes from
 * the queries the screens already make.
 */
class ScreenCards(
    client: AudienceKitClient,
    screen: LayoutScreen,
    kinds: List<String>,
    private val fallback: List<String>,
    appVersion: String,
) {
    private val layoutClient = LayoutClient(client, screen, kinds, fallback, appVersion)
    private val state = MutableStateFlow(CardLayout.fallback(fallback))
    private var loadedAt = 0L

    val layout: StateFlow<CardLayout> = state.asStateFlow()

    /** Asks again only once `refreshAfter` has passed, unless [force]. */
    suspend fun load(coordinates: Coordinates?, localeId: String?, force: Boolean = false) {
        val current = state.value
        val age = SystemClock.elapsedRealtime() - loadedAt
        if (!force && loadedAt > 0 && age < current.refreshAfter * 1_000L) return
        val fetched = layoutClient.load(coordinates, localeId)
        // A failed fetch keeps the server's last order rather than jumping back to the built-in one.
        if (fetched.isFallback && !current.isFallback) return
        state.value = fetched
        loadedAt = if (fetched.isFallback) 0L else SystemClock.elapsedRealtime()
    }
}

/**
 * A screen's cards, loaded for where the device is and asked for again as the server says (`refreshAfter`) while the
 * screen shows.
 */
@Composable
fun rememberScreenCards(screen: LayoutScreen, kinds: List<String>, fallback: List<String>): CardLayout {
    val graph = LocalAppGraph.current
    val coordinates by graph.location.coordinates.collectAsStateWithLifecycle()
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val cards = remember(screen) {
        ScreenCards(graph.audienceKit, screen, kinds, fallback, graph.configuration.versionName)
    }
    LaunchedEffect(coordinates, locale?.id) { cards.load(coordinates, locale?.id, force = true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(cards.layout.value.refreshAfter * 1_000L)
                cards.load(graph.location.coordinates.value, graph.location.locale.value?.id)
            }
        }
    }
    val layout by cards.layout.collectAsStateWithLifecycle()
    return layout
}

/**
 * The promoted-card categories the viewer turned off, kept on this device (and restored by Auto Backup) and never sent
 * anywhere, with the audience's published categories to name them.
 */
class CardSettings(context: Context, private val client: AudienceKitClient, scope: CoroutineScope) {
    private val preferences = CardPreferences(context)
    private val loaded = MutableStateFlow<List<CardCategory>?>(null)

    val hidden: StateFlow<Set<String>> = preferences.hidden

    /** Null until they load; the filter honours every hidden category until then. */
    val categories: StateFlow<List<CardCategory>?> = loaded.asStateFlow()

    /** Says which promoted cards were seen, for the fair rotation. */
    val views = PromoViewRecorder(client, scope)

    fun filter(hidden: Set<String>, categories: List<CardCategory>?): CardFilter = CardFilter(hidden, categories)

    fun setHidden(key: String, hide: Boolean) = preferences.setHidden(key, hide)

    suspend fun loadCategories() {
        try {
            loaded.value = client.cardCategories()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }
}

/**
 * The records a card points at, in the server's order, from what the screen loaded. A built-in card, or one whose ids
 * match nothing here, gets the screen's own list.
 */
fun <T> List<T>.rankedBy(card: LayoutCard?, id: (T) -> String): List<T> =
    if (card == null || card.isFallback) this else pickedBy(card, id).ifEmpty { this }

/** Only the records the card points at, in its order. */
fun <T> List<T>.pickedBy(card: LayoutCard, id: (T) -> String): List<T> {
    val byId = associateBy { id(it).lowercase() }
    return (card.venueIds + card.personIds + card.eventIds).mapNotNull { byId[it.lowercase()] }
}

/** The audience's own announcement (a `web` card): a picture, words and a link. Never hidden by Cards you see. */
@Composable
fun WebCardView(content: CardContent) {
    CardBody(content, badge = null)
}

/**
 * A promo slot: the first candidate whose category the viewer hasn't turned off, or nothing. Its badge and category
 * name are the server's words. It tells the fair rotation it was seen once it's been on screen a second.
 */
@Composable
fun PromoSlotView(card: LayoutCard, layoutId: String?, navigator: Navigator) {
    val settings = LocalAppGraph.current.cards
    val hidden by settings.hidden.collectAsStateWithLifecycle()
    val categories by settings.categories.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (settings.categories.value == null) settings.loadCategories() }
    val promo = card.promo(settings.filter(hidden, categories)) ?: return
    val category = categories?.firstOrNull { it.key == promo.category }
    val categoryName = category?.label ?: promo.category
    var explaining by remember { mutableStateOf(false) }

    DisposableEffect(promo.id, layoutId) {
        settings.views.appeared(promo.id, layoutId)
        onDispose { settings.views.disappeared(promo.id) }
    }
    CardBody(
        promo.content,
        badge = promo.label,
        route = route(promo),
        navigator = navigator,
        menu = { close ->
            if (category?.hideable != false) {
                DropdownMenuItem(text = { Text("Don't show me $categoryName") }, onClick = {
                    close()
                    settings.setHidden(promo.category, true)
                })
            }
            DropdownMenuItem(text = { Text("Why am I seeing this?") }, onClick = {
                close()
                explaining = true
            })
        },
    )
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("OK") } },
            title = { Text("Why am I seeing this?") },
            text = {
                Text(
                    "${promo.sponsorName} placed this $categoryName card. Cards like it take turns fairly, picked for " +
                        "this screen and place, never for who you are. What you turn off in Me > Cards you see stays " +
                        "on your phone.",
                )
            },
        )
    }
}

/** A promoted venue or event opens its page. */
private fun route(promo: PromoCard): AppRoute? =
    promo.venueId?.let { AppRoute.VenueDetail(it) } ?: promo.eventId?.let { AppRoute.EventDetail(it) }

/** What web and promoted cards share: picture, badge, headline, body and a button that opens the link in a Custom Tab. */
@Composable
private fun CardBody(
    content: CardContent,
    badge: String?,
    route: AppRoute? = null,
    navigator: Navigator? = null,
    menu: (@Composable (close: () -> Unit) -> Unit)? = null,
) {
    val context = LocalContext.current
    val image = content.imageUrl?.takeIf { it.startsWith("https://") }
    val link = content.linkUrl?.takeIf { it.startsWith("https://") }
    Column(Modifier.fillMaxWidth().clip(Radius.photo).background(tokens.surfaceRaised)) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = content.imageAlt,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(132.dp),
            )
        }
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(badge?.uppercase().orEmpty(), style = HotMessType.label, color = tokens.inkMuted, modifier = Modifier.weight(1f))
                if (menu != null) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Card options", tint = tokens.inkMuted)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
                    }
                }
            }
            Text(content.headline, style = HotMessType.heading, color = tokens.ink)
            content.body?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
            when {
                route != null && navigator != null -> TextButton(onClick = { navigator.open(route) }) {
                    Text(content.linkLabel ?: "Open", style = HotMessType.label, color = tokens.accentInk)
                }
                link != null -> TextButton(onClick = { context.openInCustomTab(link) }) {
                    Text(content.linkLabel ?: "Learn more", style = HotMessType.label, color = tokens.accentInk)
                }
            }
        }
    }
}

private fun Context.openInCustomTab(url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

/** Me > Cards you see: turn categories of promoted cards off and on. The choices stay on this phone, never sent. */
@Composable
fun CardsYouSeeScreen(navigator: Navigator) {
    val settings = LocalAppGraph.current.cards
    val hidden by settings.hidden.collectAsStateWithLifecycle()
    val categories by settings.categories.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settings.loadCategories() }

    ScreenScaffold(title = "Cards you see", onBack = navigator::back) {
        Feed {
            val loaded = categories
            if (loaded == null) {
                item { DetailSection(null) { EmptyRow("Loading…") } }
            } else {
                item {
                    DetailSection("Promoted cards") {
                        loaded.filter { it.hideable }.forEachIndexed { index, category ->
                            if (index > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = Space.s5, vertical = Space.s3),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(category.label, style = HotMessType.body, color = tokens.ink)
                                    if (category.description.isNotBlank()) {
                                        Text(category.description, style = HotMessType.bodySmall, color = tokens.inkMuted)
                                    }
                                }
                                Switch(
                                    checked = category.key !in hidden,
                                    onCheckedChange = { settings.setHidden(category.key, !it) },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Promoted cards you turn off stay hidden on this phone. Your choices are never sent to Hot Mess, " +
                            "so they can't be shared or sold.",
                        style = HotMessType.bodySmall,
                        color = tokens.inkMuted,
                        modifier = Modifier.padding(horizontal = Space.s5),
                    )
                }
                val fixed = loaded.filterNot { it.hideable }
                if (fixed.isNotEmpty()) {
                    item {
                        DetailSection("Always shown") {
                            fixed.forEachIndexed { index, category ->
                                if (index > 0) RowDivider()
                                InfoRow(category.label)
                            }
                        }
                    }
                }
            }
        }
    }
}
