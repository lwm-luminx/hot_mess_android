package social.hotmess.android.ui.screens

import com.audiencekit.SoapboxCopy
import com.audiencekit.ArticleTopic
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.payments.CoverCheckout
import social.hotmess.android.payments.CoverCheckoutFailure
import social.hotmess.android.payments.rememberCoverCheckout
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.ShareLinkFailure
import social.hotmess.android.ui.addToCalendar
import social.hotmess.android.ui.components.rememberManages
import social.hotmess.android.ui.components.ManagedListingNotice
import social.hotmess.android.ui.components.ManagedKind
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.CityPickerSheet
import social.hotmess.android.ui.components.LocationNeeded
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
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.NightCalendarView
import social.hotmess.android.ui.components.PersonCard
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.RsvpPicker
import social.hotmess.android.ui.components.TextRow
import social.hotmess.android.ui.components.VenueCard
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.rememberShareLinks
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.PostsSubject
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.CalendarEntry
import social.hotmess.core.CoverOffer
import social.hotmess.core.Event
import social.hotmess.core.EventListing
import social.hotmess.core.Formatting
import social.hotmess.core.KaraokeNight
import social.hotmess.core.Lineups
import social.hotmess.core.Person
import social.hotmess.core.NightCalendar
import social.hotmess.core.PhotoTone
import social.hotmess.core.PingChoices
import social.hotmess.core.PingPick
import social.hotmess.core.Rsvp
import social.hotmess.core.Venue
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * What's on in the city you're in: a four-week calendar of how busy each night is, then a couple of
 * featured nights and everything upcoming, or just the night picked on the calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val loader = rememberLoader<EventListing>("events")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { graph.location.refreshLocale() }

    val current = locale
    val cityChosen by graph.location.cityChosen.collectAsStateWithLifecycle()
    var choosingCity by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = current?.name?.let { "Events in $it" } ?: "Events",
        actions = {
            // A city picked by hand can be changed here; the device's own city follows the device.
            if (current != null && cityChosen) {
                IconButton(onClick = { choosingCity = true }) { Icon(Icons.Rounded.LocationCity, contentDescription = "Choose a city") }
            }
        },
    ) {
        if (choosingCity) CityPickerSheet(onDismiss = { choosingCity = false })
        if (current == null) {
            LocationNeeded(title = "Where are you?", text = "Hot Mess shows the events in your city.")
            return@ScreenScaffold
        }
        var selectedNight by remember(current.id) { mutableStateOf<LocalDate?>(null) }
        LaunchedEffect(current.id) { loader.load(current.id) { graph.api.events(current.id) } }

        LoadStateView(state, onRetry = { loader.load(current.id) { graph.api.events(current.id) } }) { listing ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(current.id, refresh = true) { graph.api.events(current.id) } }) {
                if (listing.isEmpty) {
                    Message(Icons.Rounded.CalendarMonth, "Nothing on yet", "There are no upcoming events in ${current.name}. Check back soon.")
                } else {
                    val nights = remember(listing) {
                        NightCalendar.of(listing.allEvents, firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek)
                    }
                    val night = nights.nights.firstOrNull { it.date == selectedNight }
                    val open = { event: Event -> navigator.open(AppRoute.EventDetail(event.id)) }
                    Feed {
                        item(key = "calendar") {
                            DetailSection { NightCalendarView(nights, selectedNight) { selectedNight = it } }
                        }
                        if (night != null) {
                            val title = night.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
                            if (night.events.isEmpty()) {
                                item(key = "night-${night.date}") {
                                    DetailSection(title) { EmptyRow("Nothing on this night yet.") }
                                }
                            } else {
                                cardSection("night-${night.date}", title, night.events, key = { it.id }) { event ->
                                    EventCard(event, onClick = { open(event) })
                                }
                            }
                        } else {
                            listing.sections.filter { it.events.isNotEmpty() }.forEach { section ->
                                cardSection(section.id, section.title, section.events, key = { it.id }) { event ->
                                    EventCard(event, onClick = { open(event) }, featured = if (section.isFeatured) true else null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One event: its cover, your RSVP, when and where, and who's playing. */
@Composable
fun EventScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoader<Event>("event-$id")
    val state by loader.state.collectAsStateWithLifecycle()
    var rsvpError by remember { mutableStateOf<String?>(null) }
    var calendarMissing by remember { mutableStateOf(false) }
    val shareLinks = rememberShareLinks()

    LaunchedEffect(id) { loader.load(id) { graph.api.event(id) } }
    val postsSubject = remember(id) { PostsSubject.Event(id) }
    val news = rememberNews(remember(id) { ArticleTopic.Event(id) })
    val postsLoader = rememberPosts(postsSubject)
    val posts by postsLoader.state.collectAsStateWithLifecycle()
    val postActions = rememberPostActions(navigator) { poster -> postsLoader.update { list -> list.filterNot { it.poster == poster } } }

    val checkout = rememberCoverCheckout { pass ->
        loader.load(id, refresh = true) { graph.api.event(id) }
        navigator.openPass(pass.id)
    }
    val checkoutState by checkout.state.collectAsStateWithLifecycle()
    val payingCoatCheck by checkout.coatCheck.collectAsStateWithLifecycle()
    val userId = rememberUserId()
    var pingHere by rememberSaveable { mutableStateOf(false) }
    val actions = rememberPingActions { ping ->
        loader.update { event -> event.replacingFriendPing(ping) }
        graph.pingUpdated(ping.id)
    }

    fun choose(rsvp: Rsvp) {
        val previous = state.valueOrNull?.rsvp ?: return
        if (rsvp == previous) return
        loader.update { it.withRsvp(rsvp) }
        scope.launch {
            try {
                graph.api.setRsvp(rsvp, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loader.update { it.withRsvp(previous) }
                rsvpError = ApiError.of(e).message ?: "Try again in a moment."
            }
        }
    }

    val event = state.valueOrNull
    // On a karaoke night's night, the venue's karaoke itself: who's singing, Sign up to sing and song requests.
    val tonightVenueId = event?.takeIf { it.karaoke != null && PingChoices.isTonight(it) }?.venue?.id
    val tonightModel = tonightVenueId?.let { rememberTonightLineup(it) }
    val tonightLineup = tonightModel?.lineup?.collectAsStateWithLifecycle()?.value
    val tonightLoaded = tonightModel?.loaded?.collectAsStateWithLifecycle()?.value ?: true
    val viewerName = graph.session.user.collectAsStateWithLifecycle().value?.name
    // Velvet, on this event, when it's at a venue the signed-in person manages.
    val managesVenue = rememberManages(ManagedKind.VENUE, event?.venue?.id.orEmpty())
    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    HeroScaffold(
        title = event?.name ?: "Event",
        tone = heroTone,
        collapsed = event == null || scrolled,
        onBack = navigator::back,
        actions = {
            if (event != null) {
                IconButton(onClick = { if (!context.addToCalendar(CalendarEntry.from(event))) calendarMissing = true }) {
                    Icon(Icons.Rounded.EditCalendar, contentDescription = "Add to calendar")
                }
                IconButton(onClick = { shareLinks.share(event.sharePath, event.name) }) {
                    Icon(Icons.Rounded.Share, contentDescription = "Share")
                }
            }
        },
    ) { topInset ->
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.event(id) } }) { loaded ->
            HeroFeed(
                listState,
                hero = {
                    HeroHeader(loaded.bannerUrl, topInset, onTone = { heroTone = it }) {
                        Text(
                            "${Formatting.monthAbbreviation(loaded.startAt)} ${Formatting.dayOfMonth(loaded.startAt)} · ${Formatting.shortTime(loaded.startAt)}".uppercase(),
                            style = HotMessType.caption,
                        )
                        Text(
                            loaded.name,
                            style = HotMessType.title,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                        loaded.venue?.name?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = HotMessType.subheading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
            ) {
                val managedVenueId = loaded.venue?.id
                if (managesVenue && managedVenueId != null) {
                    item(key = "managed") { ManagedListingNotice(ManagedKind.VENUE, managedVenueId, eventId = loaded.id) }
                }
                item {
                    // Pings are tonight only, so only tonight's events can be picked.
                    PingStripSection(
                        pings = loaded.friendPings,
                        pickOf = { it.pickForEvent(loaded.id) },
                        userId = userId,
                        actions = actions,
                        onPingHere = if (PingChoices.isTonight(loaded)) ({ pingHere = true }) else null,
                    )
                }
                if (loaded.hosts.isNotEmpty()) {
                    item(key = "hosted-by") { HostedByLine(loaded.hosts) { navigator.open(AppRoute.PersonDetail(it)) } }
                }
                item { DetailSection("Your RSVP") { RsvpPicker(loaded.rsvp, ::choose) } }
                CoverOffer.of(loaded)?.let { offer ->
                    item {
                        DetailSection("Cover") {
                            CoverRow(
                                offer,
                                paying = checkoutState == CoverCheckout.State.Working && !payingCoatCheck,
                                onPay = { loaded.venue?.let { checkout.pay(it.id, it.name) } },
                                onShowPass = { pass ->
                                    graph.passes.put(pass)
                                    navigator.openPass(pass.id)
                                },
                            )
                        }
                    }
                }
                val karaoke = loaded.karaoke
                if (tonightModel != null && tonightLineup != null && tonightLineup.showsKaraoke) {
                    item(key = "karaoke-tonight") { TonightKaraoke(tonightModel, tonightLineup, viewerName) }
                } else if (karaoke != null && tonightLoaded) {
                    item(key = "karaoke") {
                        EventKaraokeSection(karaoke, loaded.venue) { navigator.open(AppRoute.VenueDetail(it)) }
                    }
                }
                postsSection("From the venue and hosts", postsSubject, posts, postActions)
                newsSection(SoapboxCopy.IN_THE_NEWS, news, navigator)
                item(key = "photos") { PhotosSection(postsSubject, navigator) }
                item {
                    DetailSection("When") {
                        InfoRow("Starts", Formatting.dateTime(loaded.startAt))
                        loaded.endAt?.let {
                            RowDivider()
                            InfoRow("Ends", Formatting.dateTime(it))
                        }
                        loaded.facebookUrl?.let { url ->
                            RowDivider()
                            InfoRow("Open in Facebook", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(url) })
                        }
                    }
                }
                val venue = loaded.venue
                if (venue != null) {
                    cardSection("venue", "Venue", listOf(venue), key = { it.id }) {
                        VenueCard(it, onClick = { navigator.open(AppRoute.VenueDetail(it.id)) })
                    }
                } else {
                    item { DetailSection("Venue") { EmptyRow(Formatting.TO_BE_ANNOUNCED) } }
                }
                cardSection("presented-by", "Presented by", loaded.organizers, key = { it.id }) { organizer ->
                    PersonCard(organizer, onClick = { navigator.open(AppRoute.PersonDetail(organizer.id)) })
                }
                cardSection("lineup", "Lineup", loaded.people, key = { it.id }) { person ->
                    PersonCard(person, onClick = { navigator.open(AppRoute.PersonDetail(person.id)) })
                }
                // Something wrong or cancelled? A person checks it first.
                if (graph.session.isSignedIn) {
                    item(key = "suggest-fix") { SuggestFixRow { navigator.openSuggestFix(FixTarget(isVenue = false, loaded.id, loaded.name)) } }
                }
            }
        }
    }

    CoverCheckoutFailure(checkout)
    ShareLinkFailure(shareLinks)
    if (pingHere && event != null) {
        PingSheet(
            preselect = PingPick.EventPick(event),
            onDismiss = { pingHere = false },
            onSent = { graph.pingUpdated(it.id) },
        )
    }

    if (calendarMissing) {
        AlertDialog(
            onDismissRequest = { calendarMissing = false },
            confirmButton = { TextButton(onClick = { calendarMissing = false }) { Text("OK") } },
            title = { Text("No calendar app") },
            text = { Text("Install a calendar app, such as Google Calendar, to add events to it.") },
        )
    }
    rsvpError?.let { message ->
        AlertDialog(
            onDismissRequest = { rsvpError = null },
            confirmButton = { TextButton(onClick = { rsvpError = null }) { Text("OK") } },
            title = { Text("Couldn't save RSVP") },
            text = { Text(message) },
        )
    }
}

/** "Hosted by Sasha and Bea" with their faces; one host opens their page. */
@Composable
private fun HostedByLine(hosts: List<Person>, onOpen: (String) -> Unit) {
    val single = hosts.singleOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (single != null) Modifier.clickable(onClickLabel = "See ${single.name}'s page") { onOpen(single.id) } else Modifier)
            .padding(vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            hosts.take(3).forEach { Avatar(it.avatarUrl, it.name, size = 28.dp) }
        }
        Text(
            "Hosted by ${Lineups.andList(hosts.map { it.name })}",
            style = HotMessType.subheading,
            color = tokens.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A karaoke night before (or without) tonight's lineup: the partner's session to join, or for AudienceKit's own
 * karaoke, where to sign up.
 */
@Composable
internal fun EventKaraokeSection(karaoke: KaraokeNight, venue: Venue?, onOpenVenue: (String) -> Unit) {
    val context = LocalContext.current
    var showingCode by remember { mutableStateOf(false) }
    DetailSection("Karaoke") {
        val link = karaoke.joinLink
        val code = karaoke.sessionCode?.takeIf { it.isNotBlank() }
        when {
            link != null -> {
                InfoRow(
                    karaoke.providerName?.takeIf { it.isNotBlank() }?.let { "Join $it session" } ?: "Join karaoke session",
                    icon = Icons.Rounded.Mic,
                    onClick = { context.openUrl(link) },
                    trailing = Icons.AutoMirrored.Rounded.OpenInNew,
                )
                if (code != null) {
                    RowDivider()
                    if (showingCode) InfoRow("Join code", code) else InfoRow("Show join code", onClick = { showingCode = true })
                }
            }
            karaoke.isAudienceKit -> {
                InfoRow("Karaoke night", icon = Icons.Rounded.Mic)
                TextRow(venue?.let { "Sign up to sing on ${it.name}'s page, or in its chat once you're there." }
                    ?: "Sign up to sing on the venue's page, or in its chat once you're there.")
                venue?.let {
                    RowDivider()
                    InfoRow("Sign up at ${it.name}", onClick = { onOpenVenue(it.id) })
                }
            }
            else -> {
                InfoRow("Karaoke night", icon = Icons.Rounded.Mic)
                if (code != null) InfoRow("Join code", code) else TextRow("Ask the host how to join the karaoke session.")
            }
        }
    }
}
