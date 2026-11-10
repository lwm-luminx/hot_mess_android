package social.hotmess.android.ui.screens

import com.audiencekit.SoapboxCopy
import com.audiencekit.ArticleTopic
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.CardKind
import com.audiencekit.CardLayout
import com.audiencekit.LayoutScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventCard
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.PhotoCard
import social.hotmess.android.ui.components.VenueCard
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.core.AppRoute
import social.hotmess.core.Event
import social.hotmess.core.Venue
import social.hotmess.core.isIn

/** How long each venue shows on a locale's card before the next. */
private const val SHOWCASE_INTERVAL_MILLIS = 4_000L

/** A locale's page without the server's layout: its next events, then its venues. */
private val LOCALE_FALLBACK_CARDS = listOf(CardKind.EVENTS_UPCOMING, CardKind.VENUES_ALL)
private val LOCALE_CARD_KINDS =
    listOf(CardKind.EVENTS_UPCOMING, CardKind.VENUES_TONIGHT, CardKind.VENUES_ALL, CardKind.WEB, CardKind.PROMO_SLOT)

/** How many events the built-in order shows. */
private const val FALLBACK_EVENT_COUNT = 8

/**
 * A locale on the Venues tab's Everywhere view (`locales.showcase`): its name over one of its [venues]' photos, moving
 * on to the next of the few the server picked every few seconds (held on the first when animations are off). Tapping
 * it opens the locale's page.
 */
@Composable
fun LocaleShowcaseCard(name: String, venues: List<Venue>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animates = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (e: Exception) {
            true
        }
    }
    var index by remember(venues) { mutableIntStateOf(0) }
    if (animates && venues.size > 1) {
        LaunchedEffect(venues) {
            while (true) {
                delay(SHOWCASE_INTERVAL_MILLIS)
                index = (index + 1) % venues.size
            }
        }
    }
    val described = listOf(name) + venues.map { it.name }
    Box(modifier.semantics { contentDescription = described.joinToString(", ") }) {
        Crossfade(targetState = venues.getOrNull(index), animationSpec = tween(600), label = "showcase") { venue ->
            PhotoCard(
                url = venue?.bannerUrl ?: venue?.photoUrl,
                onClick = onClick,
                minHeight = Sizes.photoCardFeatured,
                topClearance = 44.dp,
                trailing = { if (venues.size > 1) ShowcaseDots(venues.size, index) },
            ) {
                Text(name, style = HotMessType.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                venue?.let {
                    Text(it.name, style = HotMessType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Which of the showcase's venues is showing. Hidden from screen readers; the card names them all. */
@Composable
private fun ShowcaseDots(count: Int, current: Int) {
    Row(
        Modifier.clearAndSetSemantics { }
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(count) { dot ->
            Box(
                Modifier.size(6.dp)
                    .background(if (dot == current) Color.White else Color.White.copy(alpha = 0.45f), CircleShape),
            )
        }
    }
}

/** What a locale's page loads: every visible venue (the server's ids pick from them) and the locale's events. */
private data class LocalePage(val venues: List<Venue>, val events: List<Event>)

/**
 * A locale's page, opened from its card on the Everywhere view: some of its events and its venues, in the order the
 * server's LOCALE layout gives. Without the layout it shows the locale's next events, then its venues.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocaleScreen(id: String, name: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<LocalePage>("locale-$id")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val fetch: suspend () -> LocalePage = {
        // The venues show without the events, so an events failure only leaves them out; leaving the screen still cancels.
        val events = try {
            graph.api.events(id).allEvents
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        LocalePage(graph.api.venues(id), events)
    }
    LaunchedEffect(id) { loader.load(id, fetch = fetch) }
    val served = rememberScreenCards(LayoutScreen.LOCALE, LOCALE_CARD_KINDS, LOCALE_FALLBACK_CARDS, forLocale = id)
    val news = rememberNews(remember(id) { ArticleTopic.Locale(id) }, limit = 5)

    ScreenScaffold(name, onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(id, refresh = true, fetch = fetch) }) { page ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(id, refresh = true, fetch = fetch) }) {
                val here = page.venues.filter { it.isIn(id) }
                val openVenue = { venue: Venue -> navigator.open(AppRoute.VenueDetail(venue.id)) }
                val cards = served.takeUnless { it.cards.isEmpty() } ?: CardLayout.fallback(LOCALE_FALLBACK_CARDS)
                Feed {
                    cards.cards.forEach { card ->
                        when (card.kind) {
                            CardKind.EVENTS_UPCOMING -> {
                                val events = if (card.isFallback) page.events.take(FALLBACK_EVENT_COUNT) else page.events.pickedBy(card) { it.id }
                                cardSection(card.id, card.title ?: "Coming up", events, key = { it.id }) { event ->
                                    EventCard(event, onClick = { navigator.open(AppRoute.EventDetail(event.id)) })
                                }
                            }
                            CardKind.VENUES_TONIGHT ->
                                cardSection(card.id, card.title, page.venues.pickedBy(card) { it.id }, key = { it.id }) { venue ->
                                    VenueCard(venue, onClick = { openVenue(venue) })
                                }
                            CardKind.VENUES_ALL -> {
                                val picked = if (card.isFallback) emptyList() else page.venues.pickedBy(card) { it.id }
                                val shown = picked.ifEmpty { here }
                                if (shown.isEmpty()) {
                                    item(key = card.id) { DetailSection(card.title ?: name) { EmptyRow("No venues in $name yet.") } }
                                } else {
                                    cardSection(card.id, card.title ?: name, shown, key = { it.id }) { venue ->
                                        VenueCard(venue, onClick = { openVenue(venue) })
                                    }
                                }
                            }
                            CardKind.WEB -> card.web?.let { content -> item(key = card.id) { WebCardView(content) } }
                            CardKind.PROMO_SLOT -> item(key = card.id) { PromoSlotView(card, cards.id, navigator) }
                        }
                    }
                    newsSection(SoapboxCopy.NEWS, news, navigator)
                }
            }
        }
    }
}
