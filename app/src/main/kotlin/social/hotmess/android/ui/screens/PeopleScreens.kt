package social.hotmess.android.ui.screens

import com.audiencekit.SoapboxCopy
import com.audiencekit.ArticleTopic
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.hotmess.android.R
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.CardRows
import social.hotmess.android.ui.components.DetailInset
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
import social.hotmess.android.ui.components.PersonCard
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SocialLinkRow
import social.hotmess.android.ui.components.TrackRow
import social.hotmess.android.ui.components.cardSection
import social.hotmess.android.ui.components.rememberHeroCollapsed
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.core.PostsSubject
import social.hotmess.core.AppRoute
import social.hotmess.core.Event
import social.hotmess.core.Gig
import social.hotmess.core.Person
import social.hotmess.core.PeopleListing
import social.hotmess.core.PersonDetail
import social.hotmess.core.PhotoTone
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import social.hotmess.core.SocialLink
import com.audiencekit.CardKind
import com.audiencekit.CardLayout
import com.audiencekit.LayoutScreen
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import com.audiencekit.LayoutCard

private const val PEOPLE_PLAYING_THIS_WEEK = "people.playingThisWeek"
private const val PEOPLE_BASED_HERE = "people.basedHere"

/** The local groups, in the built-in order; the server can rank them and put promoted cards between them. */
private val PEOPLE_LOCAL_CARDS = listOf(PEOPLE_PLAYING_THIS_WEEK, PEOPLE_BASED_HERE)
private val PEOPLE_CARD_KINDS = PEOPLE_LOCAL_CARDS + listOf(CardKind.PEOPLE_EVERYONE, CardKind.WEB, CardKind.PROMO_SLOT)

/**
 * The DJs, hosts and performers. Once the device's city is known it opens on who's local there, as iOS does: people
 * playing it this week, then people based there; Everyone is the whole list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<Person>>("people")
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    // Opens on the device's city each time.
    var everyone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { loader.load(Unit) { graph.api.people() } }
    val cardLayout = rememberScreenCards(LayoutScreen.PEOPLE, PEOPLE_CARD_KINDS, PEOPLE_LOCAL_CARDS + CardKind.PEOPLE_EVERYONE)

    ScreenScaffold(title = "People") {
        LoadStateView(state, onRetry = { loader.load(Unit) { graph.api.people() } }) { people ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(Unit, refresh = true) { graph.api.people() } }) {
                if (people.isEmpty()) {
                    Message(Icons.Rounded.People, "No one here yet", "DJs, hosts and performers will show up here.")
                } else {
                    val listing = remember(people) { PeopleListing(people) }
                    val city = locale
                    val open = { person: Person -> navigator.open(AppRoute.PersonDetail(person.id)) }
                    Feed {
                        if (city != null) {
                            item(key = "scope") {
                                VenueScopePicker(city.name, everyone, onChange = { everyone = it }, allLabel = "Everyone")
                            }
                        }
                        if (city == null || everyone) {
                            // Everyone, in the server's order, with any announcement or promoted card around it.
                            val cards = cardLayout.takeIf { it.card(CardKind.PEOPLE_EVERYONE) != null }
                                ?: CardLayout.fallback(listOf(CardKind.PEOPLE_EVERYONE))
                            cards.cards.forEach { card ->
                                when (card.kind) {
                                    CardKind.PEOPLE_EVERYONE ->
                                        cardSection("people", null, people.rankedBy(card) { it.id }, key = { it.id }) { person ->
                                            PersonCard(person, onClick = { open(person) }, friends = person.friendsOut)
                                        }
                                    CardKind.WEB -> card.web?.let { content -> item(key = card.id) { WebCardView(content) } }
                                    CardKind.PROMO_SLOT -> item(key = card.id) { PromoSlotView(card, cards.id, navigator) }
                                }
                            }
                        } else {
                            val local = listing.local(city.id)
                            if (local.isEmpty) {
                                item(key = "no-one-local") {
                                    DetailSection(null) {
                                        EmptyRow("No one based in ${city.name} yet.")
                                        RowDivider()
                                        InfoRow("Show everyone", onClick = { everyone = true })
                                    }
                                }
                            } else {
                                localCards(cardLayout).forEach { card ->
                                    when (card.kind) {
                                        PEOPLE_PLAYING_THIS_WEEK -> {
                                            val ranked = local.playing.rankedBy(card) { it.person.id }
                                            cardSection("playing", "Playing this week", ranked, key = { it.person.id }) { playing ->
                                                PersonCard(
                                                    playing.person,
                                                    onClick = { open(playing.person) },
                                                    detail = gigLine(playing.gig),
                                                    friends = playing.person.friendsOut,
                                                )
                                            }
                                        }
                                        PEOPLE_BASED_HERE -> {
                                            val ranked = local.based.rankedBy(card) { it.id }
                                            cardSection("based", "Based here", ranked, key = { it.id }) { person ->
                                                PersonCard(person, onClick = { open(person) }, friends = person.friendsOut)
                                            }
                                        }
                                        CardKind.WEB -> card.web?.let { content -> item(key = card.id) { WebCardView(content) } }
                                        CardKind.PROMO_SLOT -> item(key = card.id) { PromoSlotView(card, cardLayout.id, navigator) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The local groups in the server's order (promoted cards between them) when it sent them, else the built-in order. */
private fun localCards(layout: CardLayout): List<LayoutCard> {
    val served = layout.cards.filter { it.kind != CardKind.PEOPLE_EVERYONE }
    return if (served.any { it.kind in PEOPLE_LOCAL_CARDS }) served else CardLayout.fallback(PEOPLE_LOCAL_CARDS).cards
}

/** "Tonight · Neighbours", or "Fri · Neighbours" later in the week. */
private fun gigLine(gig: Gig): String {
    val zone = ZoneId.systemDefault()
    val date = gig.date.atZone(zone).toLocalDate()
    val day = if (date == LocalDate.now(zone)) "Tonight" else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val venue = gig.venueName?.takeIf { it.isNotBlank() } ?: return day
    return "$day · $venue"
}

/** One person: their cover and photo, where else to find them, their members and groups, their events and tracks. */
@Composable
fun PersonScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val loader = rememberLoader<PersonDetail>("person-$id")
    val state by loader.state.collectAsStateWithLifecycle()

    LaunchedEffect(id) { loader.load(id) { graph.api.person(id) } }
    val postsSubject = remember(id) { PostsSubject.Person(id) }
    val news = rememberNews(remember(id) { ArticleTopic.Person(id) })
    val postsLoader = rememberPosts(postsSubject)
    val posts by postsLoader.state.collectAsStateWithLifecycle()
    val postActions = rememberPostActions(navigator) { poster -> postsLoader.update { list -> list.filterNot { it.poster == poster } } }

    val current = state.valueOrNull
    val listState = rememberLazyListState()
    val scrolled by rememberHeroCollapsed(listState)
    var heroTone by remember { mutableStateOf(PhotoTone.PLACEHOLDER) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    reporting?.let { ReportDialog(it) { reporting = null } }
    HeroScaffold(
        title = current?.name ?: "Person",
        tone = heroTone,
        collapsed = current == null || scrolled,
        onBack = navigator::back,
        actions = {
            if (current != null) {
                IconButton(onClick = { reporting = ReportSubject(ReportTarget.PERSON, id) }) {
                    Icon(Icons.Rounded.Flag, contentDescription = "Report")
                }
            }
        },
    ) { topInset ->
        LoadStateView(state, onRetry = { loader.load(id) { graph.api.person(id) } }) { detail ->
            val open = { person: Person -> navigator.open(AppRoute.PersonDetail(person.id)) }
            HeroFeed(listState, hero = { Hero(detail, topInset) { heroTone = it } }) {
                if (detail.tipLinks.isNotEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                            detail.tipLinks.forEach { link ->
                                PrimaryButton(
                                    "Tip on ${link.tipApp}",
                                    onClick = { link.url?.let(context::openUrl) },
                                    modifier = Modifier.fillMaxWidth(),
                                    icon = Icons.Rounded.Paid,
                                )
                            }
                        }
                    }
                }
                val links = detail.profileLinks.filter { it.url != null }
                val facebook = detail.person.facebookUrl.takeIf { links.none { it.network == SocialLink.Network.FACEBOOK } }
                if (links.isNotEmpty() || facebook != null) {
                    item {
                        DetailSection("Elsewhere") {
                            CardRows(links) { link -> SocialLinkRow(link) { link.url?.let(context::openUrl) } }
                            if (facebook != null) {
                                if (links.isNotEmpty()) RowDivider()
                                InfoRow("Open in Facebook", icon = Icons.AutoMirrored.Rounded.OpenInNew, onClick = { context.openUrl(facebook) })
                            }
                        }
                    }
                }
                cardSection("members", "Members", detail.members, key = { it.id }) { PersonCard(it, onClick = { open(it) }) }
                cardSection("groups", "Part of", detail.groups, key = { it.id }) { PersonCard(it, onClick = { open(it) }) }
                if (detail.events.isEmpty()) {
                    item { DetailSection("Events") { EmptyRow("Nothing coming up yet.") } }
                } else {
                    cardSection("events", "Events", detail.events, key = { it.id }) { event ->
                        EventCard(
                            event,
                            onClick = { navigator.open(AppRoute.EventDetail(event.id)) },
                            photoUrl = event.photoUrl(Event.PhotoPriority.VENUE_FIRST, personFallback = detail.person),
                        )
                    }
                }
                postsSection("Posts", postsSubject, posts, postActions)
                newsSection(SoapboxCopy.IN_THE_NEWS, news, navigator)
                item(key = "photos") { PhotosSection(postsSubject, navigator, title = "On stage") }
                if (detail.tracks.isNotEmpty()) {
                    item {
                        DetailSection(
                            "Tracks",
                            footer = {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Image(
                                        painterResource(R.drawable.powered_by_soundcloud),
                                        contentDescription = "Powered by SoundCloud",
                                        modifier = Modifier.height(24.dp),
                                    )
                                }
                            },
                        ) {
                            // The artwork (64) plus the 12 gap, past the row's 20 inset.
                            CardRows(detail.tracks, divider = DetailInset + 76.dp) { track ->
                                TrackRow(track, track.providerUrl?.let { url -> { context.openUrl(url) } })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The cover photo, with their picture and name over it. */
@Composable
private fun Hero(detail: PersonDetail, topInset: Dp, onTone: (PhotoTone) -> Unit) {
    HeroHeader(detail.bannerUrl, topInset, onTone = onTone) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            Avatar(
                detail.avatarUrl,
                detail.name,
                size = 76.dp,
                modifier = Modifier.border(3.dp, LocalContentColor.current, CircleShape),
            )
            Text(
                detail.name,
                style = HotMessType.title,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
    }
}
