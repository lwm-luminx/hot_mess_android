package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Button
import com.audiencekit.Coordinates
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import social.hotmess.android.payments.CoverCheckout
import social.hotmess.android.payments.CoverCheckoutFailure
import social.hotmess.android.payments.rememberCoverCheckout
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.ShareLinkFailure
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.ChatPeek
import social.hotmess.android.ui.components.CoverRow
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.EmptyRow
import social.hotmess.android.ui.components.EventCard
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.HeroFeed
import social.hotmess.android.ui.components.HeroHeader
import social.hotmess.android.ui.components.HeroScaffold
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.LocalFeedFillsWidth
import social.hotmess.android.ui.components.OnNowSection
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SocialLinkRow
import social.hotmess.android.ui.components.TextRow
import social.hotmess.android.ui.components.ToneSettings
import social.hotmess.android.ui.components.VenueCard
import social.hotmess.android.ui.components.VenueMap
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.dial
import social.hotmess.android.ui.openMap
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.rememberShareLinks
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.android.ui.theme.tokens
import kotlinx.coroutines.CancellationException
import social.hotmess.core.HelpVenue
import social.hotmess.core.PostsSubject
import social.hotmess.core.AdaptiveLayout
import social.hotmess.core.AppRoute
import social.hotmess.core.ChatRoom
import social.hotmess.core.CoverOffer
import social.hotmess.core.Event
import social.hotmess.core.Formatting
import social.hotmess.core.ImageTone
import social.hotmess.core.PhotoTone
import social.hotmess.core.PingPick
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import social.hotmess.core.SocialLink
import social.hotmess.core.Venue
import social.hotmess.core.VenueOverview
import social.hotmess.core.byCity
import social.hotmess.core.isIn
import com.audiencekit.CardKind
import com.audiencekit.CardLayout
import com.audiencekit.LayoutScreen

/** Under the map: the city's venues. The server can add what's on tonight, what's nearby, announcements and promoted cards. */
private val VENUES_FALLBACK_CARDS = listOf(CardKind.VENUES_ALL)
private val VENUES_CARD_KINDS =
    listOf(CardKind.VENUES_TONIGHT, CardKind.VENUES_NEARBY, CardKind.VENUES_ALL, CardKind.WEB, CardKind.PROMO_SLOT)

/**
 * The venues on a map, then as a list. Once the device's city is known it opens on that city's venues; a segmented
 * control switches to Everywhere, every venue grouped by city with this one first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenuesScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val coordinates by graph.location.coordinates.collectAsStateWithLifecycle()
    val branding by graph.brand.branding.collectAsStateWithLifecycle()
    val loader = rememberLoader<List<Venue>>("venues")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val localeId = locale?.id
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    // Opens on the device's city each time; Everywhere is every venue, by city.
    var everywhere by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(localeId) { loader.load(localeId) { graph.api.venues(localeId) } }
    val cardLayout = rememberScreenCards(LayoutScreen.VENUES, VENUES_CARD_KINDS, VENUES_FALLBACK_CARDS)

    ScreenScaffold(title = branding?.audience?.name?.let { "$it venues" } ?: "Venues") {
        LoadStateView(state, onRetry = { loader.load(localeId) { graph.api.venues(localeId) } }) { venues ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(localeId, refresh = true) { graph.api.venues(localeId) } }) {
                val name = locale?.name
                val here = venues.filter { it.isIn(localeId) }
                // Until the city is known there's only the one list: every venue.
                val shown = if (name == null || everywhere) venues else here
                Feed {
                    if (name != null) {
                        item(key = "scope") {
                            VenueScopePicker(city = name, everywhere = everywhere, onChange = { everywhere = it })
                        }
                    }
                    if (shown.any { it.coordinate != null }) {
                        item(key = "map") {
                            Box(Modifier.fillMaxWidth().height(Sizes.mapInline).clip(Radius.photo)) {
                                VenueMap(
                                    venues = shown,
                                    location = coordinates,
                                    onVenue = { navigator.open(AppRoute.VenueDetail(it)) },
                                    onMapTap = { mapExpanded = true },
                                    modifier = Modifier.fillMaxSize().clip(Radius.photo),
                                )
                                MapButton(
                                    icon = Icons.Rounded.OpenInFull,
                                    label = "Expand map",
                                    onClick = { mapExpanded = true },
                                    modifier = Modifier.align(Alignment.TopEnd),
                                )
                            }
                        }
                    }
                    val open = { venue: Venue -> navigator.open(AppRoute.VenueDetail(venue.id)) }
                    if (venues.isEmpty()) {
                        item { DetailSection("All venues") { EmptyRow("There are no venues here yet.") } }
                    } else if (name == null || everywhere) {
                        venues.byCity(localeId).forEach { group ->
                            cardSection("city-${group.city}", group.city, group.venues, key = { it.id }) { venue ->
                                VenueCard(venue, onClick = { open(venue) })
                            }
                        }
                    } else {
                        if (here.isEmpty()) {
                            item(key = "empty") { DetailSection(name) { EmptyRow("No venues in $name yet.") } }
                        }
                        // The city's cards in the server's order when it sent the city's list, else the built-in one.
                        val cards = cardLayout.takeIf { it.card(CardKind.VENUES_ALL) != null } ?: CardLayout.fallback(VENUES_FALLBACK_CARDS)
                        cards.cards.forEach { card ->
                            when (card.kind) {
                                CardKind.VENUES_ALL -> {
                                    val title = card.title.takeUnless { card.isFallback } ?: name
                                    cardSection("here", title, here.rankedBy(card) { it.id }, key = { it.id }) { venue -> VenueCard(venue, onClick = { open(venue) }) }
                                }
                                // Only the server sends these, with their own headings.
                                CardKind.VENUES_TONIGHT, CardKind.VENUES_NEARBY ->
                                    cardSection(card.id, card.title, venues.pickedBy(card) { it.id }, key = { it.id }) { venue -> VenueCard(venue, onClick = { open(venue) }) }
                                CardKind.WEB -> card.web?.let { content -> item(key = card.id) { WebCardView(content) } }
                                CardKind.PROMO_SLOT -> item(key = card.id) { PromoSlotView(card, cards.id, navigator) }
                            }
                        }
                    }
                }
            }
            if (mapExpanded) {
                FullScreenVenueMap(
                    venues = if (locale?.name == null || everywhere) venues else venues.filter { it.isIn(localeId) },
                    location = coordinates,
                    onVenue = {
                        mapExpanded = false
                        navigator.open(AppRoute.VenueDetail(it))
                    },
                    onClose = { mapExpanded = false },
                )
            }
        }
    }
}

/** Switches the venues list between [city] and Everywhere. */
@Composable
private fun VenueScopePicker(city: String, everywhere: Boolean, onChange: (Boolean) -> Unit) {
    val options = listOf(city to false, "Everywhere" to true)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, value) ->
            SegmentedButton(
                selected = everywhere == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** The venues map filling the screen. Back or the close button dismisses it. */
@Composable
private fun FullScreenVenueMap(venues: List<Venue>, location: Coordinates?, onVenue: (String) -> Unit, onClose: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            VenueMap(venues = venues, location = location, onVenue = { selectedId = it }, modifier = Modifier.fillMaxSize())
            venues.firstOrNull { it.id == selectedId }?.let { venue ->
                Button(
                    onClick = { onVenue(venue.id) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                ) {
                    Text(venue.name, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                }
            }
            MapButton(
                icon = Icons.Rounded.Close,
                label = "Close map",
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@Composable
private fun MapButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.padding(8.dp).background(tokens.surfaceRaised, CircleShape),
    ) {
        Icon(icon, contentDescription = label, tint = tokens.ink)
    }
}

/** The venue hero keeps 7:1 contrast over at least a light scrim, since its photos change under the name. */
private val VenueHeroContrast = ToneSettings(target = 7.0, scrimFloor = ImageTone.SCRIM_FLOOR)

/** One venue: its photos, what's coming up, its chat room, what's on, how to get there and its links. */
@Composable
fun VenueScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val loader = rememberLoader<VenueOverview>("venue-$id")
    val state by loader.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) { loader.load(id) { graph.api.venueOverview(id) } }
    val shareLinks = rememberShareLinks()

    val checkout = rememberCoverCheckout { pass ->
        loader.load(id, refresh = true) { graph.api.venueOverview(id) }
        navigator.openPass(pass.id)
    }
    val checkoutState by checkout.state.collectAsStateWithLifecycle()
    val userId = rememberUserId()
    var pingHere by rememberSaveable { mutableStateOf(false) }
    val pairUpScope = rememberCoroutineScope()
    val pairUp = remember(id) { PairUpController(graph.api, id, pairUpScope) { graph.location.reportAgain() } }
    var pairingUp by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(id) { pairUp.load() }
    // Whether the app places the user here, so "Get help" shows. Its own request, so an API without safety
    // alerts still shows the venue.
    var canGetHelp by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        canGetHelp = try {
            graph.api.venueSafety(id)?.canSendSafetyAlert == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    val actions = rememberPingActions { ping ->
        loader.update { overview -> overview.copy(friendPings = overview.friendPings.map { if (it.id == ping.id) ping else it }) }
        graph.pingUpdated(ping.id)
    }

    val venue = state.valueOrNull?.venue
    val postsSubject = remember(id) { PostsSubject.Venue(id) }
    val postsLoader = rememberPosts(postsSubject)
    val posts by postsLoader.state.collectAsStateWithLifecycle()
    val postActions = rememberPostActions(navigator) { poster -> postsLoader.update { list -> list.filterNot { it.poster == poster } } }
    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    reporting?.let { ReportDialog(it) { reporting = null } }
    // In a wide window the room docks beside the page while it's open to the user, as on iPad: one less screen to
    // switch to, and the peek gives way to it.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val docked = state.valueOrNull?.chatOpen == true && AdaptiveLayout.docksChat(maxWidth.value)
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
            CompositionLocalProvider(LocalFeedFillsWidth provides docked) {
            HeroScaffold(
                title = venue?.name ?: "Venue",
                tone = heroTone,
                collapsed = venue == null || scrolled,
                onBack = navigator::back,
                actions = {
                    if (venue != null) {
                        IconButton(onClick = { shareLinks.share(venue.sharePath, venue.name) }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { reporting = ReportSubject(ReportTarget.VENUE, venue.id) }) {
                            Icon(Icons.Rounded.Flag, contentDescription = "Report venue")
                        }
                    }
                },
            ) { topInset ->
                LoadStateView(state, onRetry = { loader.load(id) { graph.api.venueOverview(id) } }) { overview ->
                    val loaded = overview.venue
                    HeroFeed(
                        listState,
                        hero = {
                            // Its photos in turn, over a firmer scrim than a still hero so the name reads the same on each.
                            HeroHeader(loaded.heroPhotos, topInset, onTone = { heroTone = it }, minimum = VenueHeroContrast) {
                                Text(
                                    loaded.name,
                                    style = HotMessType.title,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.semantics { heading() },
                                )
                                val details = listOfNotNull(loaded.address?.takeIf { it.isNotBlank() }, loaded.distance?.let { Formatting.distance(it) })
                                if (details.isNotEmpty()) {
                                    Text(details.joinToString(" · "), style = HotMessType.subheading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        },
                    ) {
                        val offer = CoverOffer.of(loaded)
                        if (offer != null) {
                            item {
                                DetailSection("Cover") {
                                    CoverRow(
                                        offer,
                                        paying = checkoutState == CoverCheckout.State.Working,
                                        onPay = { checkout.pay(loaded.id, loaded.name) },
                                        onShowPass = { pass ->
                                            graph.passes.put(pass)
                                            navigator.openPass(pass.id)
                                        },
                                    )
                                }
                            }
                        }
                        // The venue's own ordering page (e.g. its Toast online ordering), opened in the browser.
                        overview.orderingUrl?.let { url ->
                            item(key = "order") {
                                DetailSection(null) {
                                    InfoRow("Order", icon = Icons.Rounded.Restaurant, onClick = { context.openUrl(url) },
                                        trailing = Icons.AutoMirrored.Rounded.OpenInNew)
                                }
                            }
                        }
                        // The next two events sit up top, above the fold; the rest wait at the bottom.
                        if (overview.comingUp.isNotEmpty()) {
                            cardSection("coming-up", "Coming up", overview.comingUp, key = { it.id }) { event ->
                                EventCard(
                                    event,
                                    onClick = { navigator.open(AppRoute.EventDetail(event.id)) },
                                    photoUrl = event.photoUrl(Event.PhotoPriority.PERSON_FIRST, venueFallback = venue),
                                )
                            }
                        }
                        if (overview.chatOpen && !docked) {
                            item {
                                ChatPeek(
                                    rememberPeekMessages(loaded.recentMessages),
                                    title = "Chat",
                                    room = loaded.name,
                                    onOpen = { navigator.openChat(loaded.id, loaded.name) },
                                    presence = remember(loaded.recentMessages) { peekPresence(loaded.recentMessages) },
                                )
                            }
                        }
                        // Only while the app places the user here: staff can only help people who are there.
                        if (canGetHelp) {
                            item(key = "get-help") {
                                GetHelpRow { val at = loaded.coordinate
                                navigator.openHelp(HelpVenue(loaded.id, loaded.name, loaded.address, at?.latitude, at?.longitude)) }
                            }
                        }
                        if (overview.onNow.isNotEmpty()) {
                            item(key = "on-now") {
                                OnNowSection(overview.onNow) { navigator.open(AppRoute.PersonDetail(it.person.id)) }
                            }
                        }
                        // Photos of the venue, and the camera once you're here: what you take goes on the stage's pages too.
                        item(key = "photos") {
                            PhotosSection(postsSubject, navigator, cameraVenueId = loaded.id.takeIf { overview.chatOpen })
                        }
                        item {
                            PingStripSection(
                                pings = overview.friendPings,
                                pickOf = { it.pickForVenue(loaded.id) },
                                userId = userId,
                                actions = actions,
                                onPingHere = { pingHere = true },
                            )
                        }
                        // Pair me up, where the venue has it: once you're here, or while you're in it tonight.
                        pairUp.pairUp?.takeIf { it.here || it.mine != null }?.let { offer ->
                            item(key = "pair-up") {
                                PairUpRow(offer, onOpen = { pairingUp = true }, onGoAgain = {
                                    pairUp.join(null)
                                    pairingUp = true
                                })
                            }
                        }
                        if (overview.friends.isNotEmpty()) {
                            item { FriendsHere(overview.friends) }
                        }
                        postsSection("Posts", postsSubject, posts, postActions)
                        item {
                            DetailSection("About") {
                                loaded.description?.takeIf { it.isNotBlank() }?.let {
                                    TextRow(it)
                                    RowDivider()
                                }
                                loaded.address?.takeIf { it.isNotBlank() }?.let { address ->
                                    val coordinate = loaded.coordinate
                                    InfoRow(
                                        address,
                                        icon = Icons.Rounded.Place,
                                        onClick = coordinate?.let { { context.openMap(it.latitude, it.longitude, loaded.name) } },
                                        trailing = coordinate?.let { Icons.AutoMirrored.Rounded.KeyboardArrowRight },
                                    )
                                    RowDivider()
                                }
                                loaded.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                                    InfoRow(phone, icon = Icons.Rounded.Phone, onClick = { context.dial(phone) })
                                    RowDivider()
                                }
                                loaded.distance?.let {
                                    InfoRow("Distance", Formatting.distance(it), icon = Icons.Rounded.NearMe)
                                    RowDivider()
                                }
                                // Its links in one order, each naming where it goes; Share when it has none.
                                val links = SocialLink.aboutLinks(loaded.socialLinks, loaded.facebookUrl)
                                if (links.isEmpty()) {
                                    InfoRow("Share", icon = Icons.Rounded.Share, onClick = { shareLinks.share(loaded.sharePath, loaded.name) })
                                }
                                links.forEachIndexed { index, link ->
                                    if (index > 0) RowDivider()
                                    SocialLinkRow(link, onClick = { link.url?.let(context::openUrl) }, namesService = true)
                                }
                            }
                        }
                        loaded.coordinate?.takeIf { graph.configuration.isTestBuild }?.let { coordinate ->
                            item {
                                PretendHere(loaded.name, coordinate.latitude, coordinate.longitude) {
                                    loader.load(id, refresh = true) { graph.api.venueOverview(id) }
                                    pairUpScope.launch { pairUp.load() }
                                }
                            }
                        }
                        if (overview.events.isEmpty()) {
                            item { DetailSection("Events") { EmptyRow("There's nothing coming up here yet.") } }
                        } else if (overview.laterEvents.isNotEmpty()) {
                            cardSection("events", "More events", overview.laterEvents, key = { it.id }) { event ->
                                EventCard(
                                    event,
                                    onClick = { navigator.open(AppRoute.EventDetail(event.id)) },
                                    photoUrl = event.photoUrl(Event.PhotoPriority.PERSON_FIRST, venueFallback = venue),
                                )
                            }
                        }
                    }
                }
            }
            }
            }
            val loaded = state.valueOrNull?.venue
            if (docked && loaded != null) {
                VerticalDivider(color = tokens.border)
                Box(Modifier.width(AdaptiveLayout.DOCKED_CHAT_DP.dp).fillMaxHeight()) {
                    VenueChatScreen(ChatRoom.venue(loaded.id), loaded.name, navigator, docked = true)
                }
            }
        }
    }

    CoverCheckoutFailure(checkout)
    ShareLinkFailure(shareLinks)
    val here = venue
    if (pingHere && here != null) {
        PingSheet(
            preselect = PingPick.VenuePick(here),
            onDismiss = { pingHere = false },
            onSent = { graph.pingUpdated(it.id) },
        )
    }
    if (pairingUp && here != null) {
        PairUpSheet(pairUp, here.name) {
            pairingUp = false
            pairUpScope.launch { pairUp.load() }
        }
    }
}

/** Test builds only: report the venue's own position, so the app and the API treat you as inside it. */
@Composable
private fun PretendHere(name: String, latitude: Double, longitude: Double, onReported: () -> Unit) {
    val location = LocalAppGraph.current.location
    val simulated by location.simulatedVenue.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Reload once the visit is reported, so the chat peek (chatOpen) catches up.
    fun after(job: Job?) {
        job ?: return
        scope.launch {
            job.join()
            onReported()
        }
    }
    DetailSection(
        "Testing",
        footer = {
            Text(
                "Test builds only. Reports this venue's location instead of yours until you stop.",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
            )
        },
    ) {
        if (simulated == name) {
            InfoRow("Stop pretending", icon = Icons.Rounded.LocationOff, onClick = { after(location.stopSimulating()) })
        } else {
            InfoRow("Pretend I'm here", icon = Icons.Rounded.MyLocation, onClick = { after(location.simulate(latitude, longitude, name)) })
        }
    }
}
