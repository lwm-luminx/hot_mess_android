package social.hotmess.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.hotmess.core.PostsSubject
import social.hotmess.core.RecordId
import social.hotmess.core.ApiError
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import social.hotmess.android.payments.CoverCheckout
import social.hotmess.android.payments.CoverCheckoutFailure
import social.hotmess.android.payments.rememberCoverCheckout
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.ChatPeek
import social.hotmess.android.ui.components.ChatPeekParticipant
import social.hotmess.android.ui.components.ChatThreadMessage
import social.hotmess.android.ui.components.DetailInset
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventCard
import social.hotmess.android.ui.components.FriendFaces
import social.hotmess.android.ui.components.HeroFeed
import social.hotmess.android.ui.components.HeroHeader
import social.hotmess.android.ui.components.HeroScaffold
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.OnNowSection
import social.hotmess.android.ui.components.PassCard
import social.hotmess.android.ui.components.Presence
import social.hotmess.android.ui.components.SkipTheLineCard
import social.hotmess.android.ui.components.VenueCard
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.friendNames
import social.hotmess.android.ui.components.presenceOf
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.ChatLine
import social.hotmess.core.CoverAction
import social.hotmess.core.CoverOffer
import social.hotmess.core.Event
import social.hotmess.core.Formatting
import social.hotmess.core.Friend
import social.hotmess.core.FriendVenue
import social.hotmess.core.Now
import social.hotmess.core.NowLocale
import social.hotmess.core.PhotoTone
import social.hotmess.core.Ping
import social.hotmess.core.PingPick
import social.hotmess.core.PlanChangeCheck
import social.hotmess.core.RoomLineup
import social.hotmess.core.Venue
import java.time.Instant
import com.audiencekit.CardKind
import com.audiencekit.LayoutCard
import com.audiencekit.LayoutScreen

/** How often Now reloads while it's showing. */
private const val NOW_REFRESH_MS = 60_000L

/**
 * Every card Now can draw, in its built-in order: who's on, the room's chat, tonight's posts, small talk, the next
 * events, the locale's updates, venues nearby, where your friends are, then pings. Announcements and promoted cards
 * come only from the server.
 */
private val NOW_FALLBACK_CARDS = listOf(
    CardKind.NOW_STAGE, CardKind.NOW_CHAT, CardKind.POSTS_TONIGHT, CardKind.NOW_SMALL_TALK, CardKind.EVENTS_UPCOMING,
    CardKind.POSTS_LOCALE, CardKind.VENUES_NEARBY, CardKind.VENUES_FRIENDS, CardKind.PINGS,
)
private val NOW_CARD_KINDS = NOW_FALLBACK_CARDS + listOf(CardKind.WEB, CardKind.PROMO_SLOT)

/**
 * What's happening where you are: the venue you're in, with the last few lines of its chat and the
 * friends who are there, or the venues nearby; then what's on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val coordinates by graph.location.coordinates.collectAsStateWithLifecycle()
    val loader = rememberLoader<Now>("now")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val simulated by graph.location.simulatedVenue.collectAsStateWithLifecycle()

    LaunchedEffect(coordinates) { loader.load(coordinates) { graph.api.nowOrPings(coordinates) } }

    // A Ping push arrived or was tapped: reload, keeping what's on screen meanwhile.
    val latestCoordinates by rememberUpdatedState(coordinates)
    LaunchedEffect(Unit) {
        graph.pingUpdates.collect {
            val near = latestCoordinates
            loader.load(near, refresh = true) { graph.api.nowOrPings(near) }
        }
    }

    // Standing still reports no new position, but the chat, the lineup and who's here keep changing:
    // reload quietly on coming back to the screen and every minute while it's showing. The first pass
    // finds the load above in flight and leaves it be.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val near = latestCoordinates
                loader.load(near, refresh = true, quiet = true) { graph.api.nowOrPings(near) }
                delay(NOW_REFRESH_MS)
            }
        }
    }

    val cardLayout = rememberScreenCards(LayoutScreen.NOW, NOW_CARD_KINDS, NOW_FALLBACK_CARDS)
    val userId = rememberUserId()
    val scope = rememberCoroutineScope()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var ending by rememberSaveable { mutableStateOf(false) }
    var endError by rememberSaveable { mutableStateOf<String?>(null) }
    val actions = rememberPingActions { ping -> loader.update { it.replacingPing(ping) } }
    // What the send sheet opens with picked: "Ping here", or inviting friends to where you are without a Ping yet.
    var sheetSeed by remember { mutableStateOf<PingPick?>(null) }
    fun openPingSheet(seed: PingPick? = null) {
        sheetSeed = seed
        sheetOpen = true
    }

    // Whether to ask "Did your plans change?": where Now places you, and since when. Never moves the Ping by itself.
    var planCheck by remember { mutableStateOf(PlanChangeCheck()) }
    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(state.valueOrNull) {
        val now = state.valueOrNull ?: return@LaunchedEffect
        planCheck = planCheck.observe(if (now.venues == null) now.venue?.id else null, Instant.now())
    }

    // "Yes, I'm here now": the Ping picks this venue (tonight's event here, when one is on) instead of its old picks,
    // keeping its note and reach. Whoever was in on an old pick stays in on the Ping as a whole.
    fun movePing(now: Now, ping: Ping, venue: Venue) {
        planCheck = planCheck.answer(ping, venue.id)
        val event = now.event?.takeIf { RecordId.same(it.venue?.id, venue.id) }
        moving = true
        scope.launch {
            try {
                val moved = graph.api.sendPing(
                    venueIds = if (event == null) listOf(venue.id) else emptyList(),
                    eventIds = listOfNotNull(event?.id),
                    note = ping.noteText,
                    localeId = now.locale?.id,
                    reach = ping.reach,
                )
                loader.update { it.replacingPing(moved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actions.error = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                moving = false
            }
        }
    }

    // Pair me up at the venue you're at, or your event's: reloaded with Now, while the screen shows.
    val pairUpVenue = state.valueOrNull?.venue
    val pairUp = remember(pairUpVenue?.id) { pairUpVenue?.let { PairUpController(graph.api, it.id, scope) { graph.location.reportAgain() } } }
    var pairingUp by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairUp, lifecycle) {
        val controller = pairUp ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                controller.load()
                delay(NOW_REFRESH_MS)
            }
        }
    }

    fun endPing() {
        ending = true
        scope.launch {
            try {
                graph.api.endPing()
                loader.update { it.copy(myPing = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                endError = ApiError.of(e).message ?: "Try again in a moment."
            } finally {
                ending = false
            }
        }
    }

    val checkout = rememberCoverCheckout { pass ->
        loader.load(coordinates, refresh = true) { graph.api.nowOrPings(coordinates) }
        navigator.openPass(pass.id)
    }
    val checkoutState by checkout.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    val title = state.valueOrNull?.title?.takeIf { it.isNotBlank() } ?: "Now"
    HeroScaffold(
        title = title,
        tone = heroTone,
        collapsed = state.valueOrNull == null || scrolled,
        actions = {
            // In the bar's own colour (the photo's, until it turns solid), like the icons beside it: accent pink
            // disappeared on some photos and on the bar's gradient.
            TextButton(onClick = { sheetOpen = true }, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current)) {
                Icon(Icons.Rounded.Campaign, contentDescription = null, modifier = Modifier.size(20.dp))
                Text("Ping", style = HotMessType.label, modifier = Modifier.padding(start = Space.s1))
            }
        },
    ) { topInset ->
        LoadStateView(state, onRetry = { loader.load(coordinates, refresh = false) { graph.api.nowOrPings(coordinates) } }) { now ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(coordinates, refresh = true) { graph.api.nowOrPings(coordinates) } }) {
                HeroFeed(
                    listState,
                    hero = {
                        HeroHeader(now.imageUrl, topInset, onTone = { heroTone = it }) {
                            val venue = now.venue
                            val event = now.event
                            // At an event: it leads, with its time and place (or host) underneath.
                            if (event != null) {
                                EventHeadline(event, onOpen = { navigator.open(AppRoute.EventDetail(event.id)) })
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.s3),
                                    modifier = if (venue != null) Modifier.clickable(
                                        role = Role.Button,
                                        onClickLabel = "View venue details",
                                        onClick = { navigator.open(AppRoute.VenueDetail(venue.id)) },
                                    ) else Modifier,
                                ) {
                                    Text(
                                        venue?.name ?: title,
                                        style = HotMessType.display,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                                    )
                                    if (venue != null) {
                                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                                    }
                                }
                            }
                        }
                    },
                ) {
                    simulated?.let { name ->
                        item {
                            DetailSection("Pretending to be at $name") {
                                InfoRow("Stop pretending", icon = Icons.Rounded.LocationOff, onClick = { graph.location.stopSimulating() })
                            }
                        }
                    }
                    // The cards in the server's order (the built-in one when it can't be had); a kind this build
                    // doesn't draw was never asked for.
                    cardLayout.cards.forEach { card ->
                        when (card.kind) {
                            CardKind.NOW_STAGE -> {
                                // Who's on.
                                if (now.onNow.isNotEmpty()) {
                                    item(key = "on-now") {
                                        OnNowSection(now.onNow) { navigator.open(AppRoute.PersonDetail(it.person.id)) }
                                    }
                                }
                                // At a venue: its photos, led by the camera; what you take goes on the stage's pages too.
                                now.venue?.let { venue ->
                                    item(key = "photos") { PhotosSection(PostsSubject.Venue(venue.id), navigator, cameraVenueId = venue.id) }
                                }
                            }
                            CardKind.NOW_CHAT -> {
                                now.venue?.let { venue ->
                                    item(key = "small-talk") { SmallTalk(venue, now.event, now.friends, navigator) }
                                }
                                // Pair me up, just under the chat: once you're at a venue that has it, or while you're in it.
                                pairUp?.pairUp?.takeIf { it.here || it.mine != null }?.let { offer ->
                                    item(key = "pair-up") {
                                        PairUpRow(offer, onOpen = { pairingUp = true }, onGoAgain = {
                                            pairUp?.join(null)
                                            pairingUp = true
                                        })
                                    }
                                }
                            }
                            // At an event or venue, its posts from the last 24 hours.
                            CardKind.POSTS_TONIGHT -> now.venue?.let { venue ->
                                val subject = now.event?.let { PostsSubject.Event(it.id) } ?: PostsSubject.Venue(venue.id)
                                item(key = "tonight-posts") {
                                    NowPostsSection(
                                        "Latest from tonight",
                                        subject,
                                        seeAll = AppRoute.Posts(subject, now.event?.name ?: venue.name),
                                        navigator = navigator,
                                        withinHours = 24,
                                    )
                                }
                            }
                            CardKind.NOW_SMALL_TALK -> now.locale?.takeIf { now.venue == null && it.chatOpen }?.let { locale ->
                                item(key = "locale-small-talk") { LocaleSmallTalk(locale, navigator) }
                            }
                            CardKind.EVENTS_UPCOMING -> if (now.events.isEmpty()) {
                                item(key = "events-empty") { DetailSection("Events") { EmptyRow("There's nothing coming up yet.") } }
                            } else {
                                cardSection("events", "Events", now.events.take(3), key = { it.id }) { event ->
                                    EventCard(event, onClick = { navigator.open(AppRoute.EventDetail(event.id)) })
                                }
                            }
                            // Away from venues, the locale's updates.
                            CardKind.POSTS_LOCALE -> now.locale?.takeIf { now.venue == null }?.let { locale ->
                                val name = locale.name.orEmpty()
                                item(key = "locale-posts") {
                                    NowPostsSection(
                                        if (name.isBlank()) "Updates nearby" else "Updates in $name",
                                        PostsSubject.Locale(locale.id),
                                        seeAll = AppRoute.Updates(locale.id, name),
                                        navigator = navigator,
                                    )
                                }
                            }
                            CardKind.VENUES_NEARBY -> nearby(now, card, coordinates != null, navigator)
                            // Tonight lists where friends are, when it's drawn.
                            CardKind.VENUES_FRIENDS ->
                                if (now.venue == null && cardLayout.card(CardKind.PINGS) == null) whereFriendsAre(now.friendVenues, navigator)
                            CardKind.PINGS -> {
                                coverCards(now, checkout, checkoutState, navigator)
                                val askAbout = planCheck.venueToAsk(now.myPing, Instant.now())
                                    ?.let { id -> now.venue?.takeIf { RecordId.same(it.id, id) } }
                                tonight(
                                    now,
                                    userId,
                                    actions,
                                    TonightHandlers(
                                        ending = ending,
                                        moving = moving,
                                        planChangeVenue = askAbout,
                                        onEdit = { openPingSheet() },
                                        onEnd = ::endPing,
                                        onStart = { venue -> openPingSheet(venue?.let { PingPick.VenuePick(it) }) },
                                        onMove = { ping, venue -> movePing(now, ping, venue) },
                                        onKeep = { ping, venue -> planCheck = planCheck.answer(ping, venue.id) },
                                    ),
                                    navigator,
                                )
                            }
                            CardKind.WEB -> card.web?.let { content -> item(key = card.id) { WebCardView(content) } }
                            CardKind.PROMO_SLOT -> item(key = card.id) { PromoSlotView(card, cardLayout.id, navigator) }
                        }
                    }
                    // Passes and the cover depend on this device, so they aren't cards: they go just above pings.
                    if (cardLayout.card(CardKind.PINGS) == null) coverCards(now, checkout, checkoutState, navigator)
                }
            }
        }
    }

    CoverCheckoutFailure(checkout)
    if (pairingUp && pairUp != null && pairUpVenue != null) {
        PairUpSheet(pairUp, pairUpVenue.name) {
            pairingUp = false
            scope.launch { pairUp.load() }
        }
    }
    if (sheetOpen) {
        PingSheet(
            preselect = sheetSeed,
            onDismiss = {
                sheetOpen = false
                sheetSeed = null
            },
            onSent = { ping -> loader.update { it.replacingPing(ping) } },
        )
    }
    // A texted Ping link that was opened: its invitation, or the Ping itself when it's already a friend's.
    val invitation by graph.pingInvitation.collectAsStateWithLifecycle()
    invitation?.takeIf { it.pingId != null && it.invitation != null }?.let { link ->
        PingInvitationSheet(
            link,
            onDismiss = { graph.openPingInvitation(null) },
            onOpenTarget = { target ->
                graph.openPingInvitation(null)
                target.event?.let { navigator.open(AppRoute.EventDetail(it.id)) }
                    ?: target.venue?.let { navigator.open(AppRoute.VenueDetail(it.id)) }
            },
        )
    }
    endError?.let { message ->
        AlertDialog(
            onDismissRequest = { endError = null },
            confirmButton = { TextButton(onClick = { endError = null }) { Text("OK") } },
            title = { Text("Couldn't end your ping") },
            text = { Text(message) },
        )
    }
}

/** Away from a venue: nearby venues in the server's order, or a nudge to turn on location. */
private fun LazyListScope.nearby(now: Now, card: LayoutCard, located: Boolean, navigator: Navigator) {
    val open = { venue: Venue -> navigator.open(AppRoute.VenueDetail(venue.id)) }
    if (now.venue != null) return
    val venues = now.venues?.rankedBy(card) { it.id }
    when {
        venues != null && venues.isEmpty() -> item { DetailSection("Venues near you") { EmptyRow("You aren't near any venues right now.") } }
        venues != null -> cardSection("nearby", "Venues near you", venues.take(3), key = { it.id }) { VenueCard(it, onClick = { open(it) }) }
        !located -> item { DetailSection("Venues near you") { EmptyRow("Turn on location to see what's happening near you.") } }
    }
}

/** At a venue with a cover tonight: your pass, or the way to skip the line. */
private fun LazyListScope.coverCards(
    now: Now,
    checkout: CoverCheckout,
    checkoutState: CoverCheckout.State,
    navigator: Navigator,
) {
    val venue = now.venue ?: return
    val offer = CoverOffer.of(venue)
    val pass = offer?.pass
    when {
        pass != null -> item(key = "pass") {
            val graph = LocalAppGraph.current
            PassCard(pass, venue.name) {
                graph.passes.put(pass)
                navigator.openPass(pass.id)
            }
        }
        offer != null && offer.action == CoverAction.PAY -> item(key = "cover") {
            SkipTheLineCard(
                offer,
                paying = checkoutState == CoverCheckout.State.Working,
                onPay = { checkout.pay(venue.id, venue.name) },
            )
        }
    }
}

/** What Tonight's buttons do, and what's in flight. */
private class TonightHandlers(
    val ending: Boolean,
    val moving: Boolean,
    /** The venue to ask "Did your plans change?" about, if any. */
    val planChangeVenue: Venue?,
    val onEdit: () -> Unit,
    val onEnd: () -> Unit,
    /** Start a Ping, picking the venue you're at when there is one. */
    val onStart: (Venue?) -> Unit,
    val onMove: (Ping, Venue) -> Unit,
    val onKeep: (Ping, Venue) -> Unit,
)

/**
 * Tonight, in one card: "Did your plans change?" once you've stayed at a venue your Ping doesn't name, your Ping (or
 * a way to start one where you are), friends here with a way to invite more, then friends out elsewhere: their Pings
 * and, away from venues, where they are.
 */
private fun LazyListScope.tonight(
    now: Now,
    userId: String?,
    actions: PingActions,
    handlers: TonightHandlers,
    navigator: Navigator,
) {
    val here = now.venue?.takeIf { now.venues == null }
    val myPing = now.myPing
    item(key = "tonight-title") {
        Text(
            "Tonight",
            style = HotMessType.heading,
            color = tokens.ink,
            modifier = Modifier.padding(horizontal = Space.s1).semantics { heading() },
        )
    }
    val askAbout = handlers.planChangeVenue
    if (askAbout != null && myPing != null) {
        item(key = "plan-change") {
            PlanChangePrompt(
                askAbout.name,
                myPing.placesSummary,
                busy = handlers.moving,
                onMove = { handlers.onMove(myPing, askAbout) },
                onKeep = { handlers.onKeep(myPing, askAbout) },
            )
        }
    }
    if (myPing != null) {
        item(key = "my-ping") { MyPingCard(myPing, onEdit = handlers.onEdit, onEnd = handlers.onEnd, ending = handlers.ending) }
    } else {
        item(key = "start-ping") { StartPingRow(here?.name) { handlers.onStart(here) } }
    }
    val friendsHere = if (here == null) emptyList() else now.friends.filterNot { RecordId.same(it.id, userId) }
    if (here != null && friendsHere.isNotEmpty()) {
        // With a Ping, the sheet edits it and can text more people; without one, it starts one here.
        item(key = "friends-here") {
            FriendsHereRow(friendsHere) { if (myPing != null) handlers.onEdit() else handlers.onStart(here) }
        }
    }
    now.friendPings.forEach { ping ->
        item(key = "ping-${ping.id}") {
            FriendPingCard(ping, userId, actions) { target ->
                target.event?.let { navigator.open(AppRoute.EventDetail(it.id)) }
                    ?: target.venue?.let { navigator.open(AppRoute.VenueDetail(it.id)) }
            }
        }
    }
    if (now.venue == null) {
        now.friendVenues.forEach { entry ->
            item(key = "tonight-venue-${entry.venue.id}") {
                VenueCard(
                    entry.venue,
                    onClick = { navigator.open(AppRoute.VenueDetail(entry.venue.id)) },
                    detail = friendNames(entry.friends),
                    pill = if (entry.friendCount == 1) "1 friend" else "${entry.friendCount} friends",
                    corner = { FriendFaces(entry.friends) },
                )
            }
        }
    }
}

/** The event the user is at, heading Now: where it stands, its name, and when and where it is. */
@Composable
private fun EventHeadline(event: Event, onOpen: () -> Unit) {
    val now = Instant.now()
    val end = event.endAt
    // Now finds an event from an hour before it starts to an hour after it ends.
    val stage = when {
        event.startAt.isAfter(now) -> "Starting soon"
        end != null && end.isBefore(now) -> "Just ended"
        else -> "Happening now"
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s1),
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = "View event details", onClick = onOpen),
    ) {
        Text(stage.uppercase(), style = HotMessType.label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(
                event.name,
                style = HotMessType.display,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).semantics { heading() },
            )
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
        Text(Formatting.eventSubtitle(event), style = HotMessType.subheading, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The last few lines of the venue's chat, and the way into the room. At an [event] the room is the event's:
 * named for it, since its host may not be a listed venue. Its faces (NowHosts.md) are the venue, the lineup
 * people in chat now, then, a gap apart, friends; the line under the title says the same in words.
 */
@Composable
private fun SmallTalk(venue: Venue, event: Event?, friends: List<Friend>, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val user by graph.session.user.collectAsStateWithLifecycle()
    val roomName = event?.name ?: venue.name
    val others = friends.filterNot { it.id.equals(user?.id, ignoreCase = true) }.distinctBy { it.id.lowercase() }
    // Now doesn't hear the room's lineup presence (only the room does), so nobody on the lineup is known to be in
    // chat here yet; the line falls back to the venue and the count.
    val lineupInChat = emptyList<ChatPeekParticipant>()
    val participants = buildList {
        add(ChatPeekParticipant("venue-${venue.id}", venue.name, venue.photoUrl, isPlace = true))
        addAll(lineupInChat)
        others.forEachIndexed { index, friend ->
            add(ChatPeekParticipant(friend.id, friend.name, friend.avatarUrl, presenceOf(friend.presence), gapBefore = index == 0))
        }
    }
    // The friend the line names: one in chat now, else any here.
    val friend = (others.firstOrNull { presenceOf(it.presence) == Presence.ONLINE } ?: others.firstOrNull())?.name
    ChatPeek(
        rememberPeekMessages(venue.recentMessages),
        title = "Small talk",
        room = roomName,
        onOpen = { navigator.openChat(venue.id, roomName) },
        presence = remember(venue.recentMessages) { peekPresence(venue.recentMessages) },
        participants = participants,
        // Now isn't told how many are in the room, so the line leaves the count off.
        subtitle = RoomLineup.peekLine(venue.name, lineupInChat.size, friend, count = null),
    )
}

/** Away from venues: the last few lines of the locale's chat, for everyone out in it, and the way in. */
@Composable
private fun LocaleSmallTalk(locale: NowLocale, navigator: Navigator) {
    val name = locale.name ?: "your city"
    ChatPeek(
        rememberPeekMessages(locale.recentMessages),
        title = "Small talk in $name",
        room = name,
        onOpen = { navigator.openLocaleChat(locale.id, name) },
        presence = remember(locale.recentMessages) { peekPresence(locale.recentMessages) },
    )
}

/**
 * A venue's recent chat lines as ChatPeek draws them: the viewer's own on the right, friends by their
 * full names, a post as the venue with its rounded-square photo, a prompt with the audience's mark,
 * rich messages in words (their summary), and specials gone once they've ended.
 */
@Composable
internal fun rememberPeekMessages(lines: List<ChatLine>): List<ChatThreadMessage> {
    val graph = LocalAppGraph.current
    val user by graph.session.user.collectAsStateWithLifecycle()
    val viewerId = user?.id
    return remember(lines, viewerId) {
        val now = Instant.now()
        lines.filter { it.isShowing(now) }.map { line ->
            ChatThreadMessage(
                id = line.id,
                senderId = when {
                    line.isPostedAsAudience -> "audience"
                    line.postedAsPerson -> "person:${line.name}"
                    else -> line.userId.ifEmpty { line.id }.lowercase()
                },
                senderName = if (line.isFromListing) line.name else graph.api.friends.displayName(line.userId, line.name),
                avatarUrl = if (line.isPostedAsAudience) null else line.avatarUrl,
                text = line.message,
                sentAt = line.sentAt,
                own = !line.isPostedAsAudience && viewerId != null && line.userId.equals(viewerId, ignoreCase = true),
                role = if (line.isPostedAsAudience) null else line.role,
                asPlace = line.postedAsVenue,
                asAudience = line.isPostedAsAudience,
                asListing = line.postedAsPerson,
                friend = !line.isFromListing && graph.api.friends.fullName(line.userId) != null,
                // A preview doesn't carry the prompt, so an answer is labelled "Prompt".
                promptLabel = line.promptId?.let { "Prompt" },
            )
        }
    }
}

/** The presence dots for a peek's senders, by the same ids [rememberPeekMessages] groups them by. */
internal fun peekPresence(lines: List<ChatLine>): Map<String, Presence> =
    lines.filter { it.userId.isNotEmpty() && !it.isFromListing }
        .mapNotNull { line -> presenceOf(line.presence)?.let { line.userId.lowercase() to it } }
        .toMap()

/** Friends who are at the venue too, by their full names; tapping one opens Messenger. Now and the venue screen show it. */
@Composable
internal fun FriendsHere(friends: List<Friend>) {
    val context = LocalContext.current
    DetailSection("Friends here") {
        if (friends.isEmpty()) {
            EmptyRow("None of your friends are here yet.")
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = DetailInset, vertical = Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s4),
                verticalAlignment = Alignment.Top,
            ) {
                friends.forEach { friend ->
                    val messenger = friend.messengerUrl
                    Column(
                        Modifier
                            .width(72.dp)
                            .then(if (messenger != null) Modifier.clickable(role = Role.Button) { context.openUrl(messenger) } else Modifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Avatar(friend.avatarUrl, friend.name, size = Sizes.avatarLg, presence = presenceOf(friend.presence))
                        // Friends see each other's full names; two lines fit most, and longer ones
                        // truncate at the end.
                        Text(
                            friend.name,
                            style = HotMessType.bodySmall,
                            color = tokens.ink,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Away from venues: the venues where friends have been lately, as venue cards with their faces top
 * left, how many top right, and their full names (truncated to fit) under the venue's name.
 */
private fun LazyListScope.whereFriendsAre(entries: List<FriendVenue>, navigator: Navigator) {
    cardSection("friend-venues", "Where your friends are", entries, key = { it.venue.id }) { entry ->
        VenueCard(
            entry.venue,
            onClick = { navigator.open(AppRoute.VenueDetail(entry.venue.id)) },
            detail = friendNames(entry.friends),
            pill = if (entry.friendCount == 1) "1 friend" else "${entry.friendCount} friends",
            corner = { FriendFaces(entry.friends) },
        )
    }
}
