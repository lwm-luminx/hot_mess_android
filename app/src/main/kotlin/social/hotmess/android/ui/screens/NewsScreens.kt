package social.hotmess.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.Article
import com.audiencekit.ArticleStatus
import com.audiencekit.ArticleTopic
import com.audiencekit.AudienceKitException
import com.audiencekit.Publication
import com.audiencekit.PublisherAdsCopy
import com.audiencekit.SoapboxCopy
import com.audiencekit.article
import com.audiencekit.articles
import com.audiencekit.articlesAbout
import com.audiencekit.myOpEds
import com.audiencekit.publication
import com.audiencekit.submitOpEd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.components.SectionTitle
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.LoadState
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Soapbox in Hot Mess: a locale's News, "In the news" on venue, event and person pages, the article reader, a
// publication's page and "Write an op-ed". Audiences without Soapbox get empty lists from the SDK, so every section
// draws nothing for them.

/** Articles about a page, loaded on their own; empty while loading, when there are none, or without Soapbox. */
@Composable
fun rememberNews(topic: ArticleTopic, limit: Int = 3): List<Article> {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<Article>>("news-${topic::class.simpleName}-${topic.id}")
    LaunchedEffect(topic.id) {
        loader.load(topic.id) { runCatching { graph.audienceKit.articlesAbout(topic, first = limit) }.getOrDefault(emptyList()) }
    }
    val state by loader.state.collectAsStateWithLifecycle()
    return state.valueOrNull.orEmpty()
}

/** "In the news" on a venue, event or person page, or a locale's "News": its articles as rows. */
fun LazyListScope.newsSection(title: String, articles: List<Article>, navigator: Navigator) {
    if (articles.isEmpty()) return
    item(key = "news-title") { SectionTitle(title) }
    items(articles, key = { "news-${it.id}" }) { article ->
        ArticleRow(article, onClick = rememberOpenArticle(article, navigator))
    }
}

/**
 * Now's local news (the server's articles.locale card): a few articles from the publishers covering the viewer's
 * locale, newest first. Draws nothing while loading, when there are none, or without Soapbox, so the built-in order
 * can always list it.
 */
@Composable
fun LocalNewsSection(title: String, localeId: String, navigator: Navigator) {
    val articles = rememberNews(remember(localeId) { ArticleTopic.Locale(localeId) }).take(3)
    if (articles.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("now.news"), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        SectionTitle(title)
        articles.forEach { article ->
            key(article.id) { ArticleRow(article, onClick = rememberOpenArticle(article, navigator)) }
        }
    }
}

/**
 * Opens an article: the reader, or for a teaser (a publication that isn't on Soapbox yet links out) the piece on the
 * publication's own site, where its ads and traffic are. The first time, the reader acknowledges that the publisher
 * runs its own ads ([PublisherAdsDialog]) before either opens, unless they tipped the publication to read it without
 * ads.
 */
@Composable
fun rememberOpenArticle(article: Article, navigator: Navigator): () -> Unit {
    val graph = LocalAppGraph.current
    val userId = rememberUserId()
    val uriHandler = LocalUriHandler.current
    val url = article.canonicalUrl?.takeIf { article.teaser }
    val open = remember(article.id, url) {
        {
            val opened = url != null && runCatching { uriHandler.openUri(url) }.isSuccess
            if (!opened) navigator.open(AppRoute.ArticleDetail(article.id))
        }
    }
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        PublisherAdsDialog(
            onAccept = {
                graph.publisherAds.acknowledge(userId)
                asking = false
                open()
            },
            onDecline = { asking = false },
        )
    }
    return {
        if (graph.publisherAds.isAcknowledged(userId) || article.showsWithoutAds) {
            open()
        } else {
            asking = true
        }
    }
}

/**
 * The one-time note before a reader's first Soapbox story: the publisher chooses and answers for the ads in its
 * stories so it can keep paying writers, Hot Mess takes no cut, its own sponsored spots are separate and marked
 * Sponsored, and no data goes to a publisher's advertisers.
 */
@Composable
fun PublisherAdsDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(PublisherAdsCopy.TITLE) },
        text = { Text(PublisherAdsCopy.body(appName = "Hot Mess")) },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag("publisherAds.accept")) {
                Text(PublisherAdsCopy.ACCEPT)
            }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text(PublisherAdsCopy.DECLINE) } },
        modifier = Modifier.testTag("publisherAds.note"),
    )
}

/** A teaser's site, e.g. "qsaltlake.com", shown on its row so readers know it opens the web. */
fun linkOutHost(article: Article): String? =
    article.canonicalUrl?.takeIf { article.teaser }
        ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        ?.removePrefix("www.")

/** An article in a list: its photo, "Op-ed" on readers' pieces, its title, summary and dateline. */
@Composable
fun ArticleRow(article: Article, onClick: () -> Unit) {
    val graph = LocalAppGraph.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("news.article"),
        color = tokens.surfaceRaised,
        contentColor = tokens.ink,
    ) {
        Row(Modifier.padding(Space.s3), horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            graph.audienceKit.url(article.heroPhotoUrl ?: article.publication.photoUrl)?.let { photo ->
                RemoteImage(photo, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                if (article.isOpEd) Text(SoapboxCopy.OP_ED.uppercase(), style = HotMessType.caption, color = tokens.accentInk)
                Text(article.title, style = HotMessType.heading, maxLines = 3)
                article.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 2)
                }
                val host = linkOutHost(article)
                Text(
                    if (host != null) "${dateline(article)} · $host ↗" else dateline(article),
                    style = HotMessType.caption,
                    color = tokens.inkMuted,
                )
            }
        }
    }
}

/** "QSaltLake · Jun 1, 2027", or the name alone before it's published. */
fun dateline(article: Article): String {
    val name = if (article.isOpEd) article.byline else article.publication.name
    val date = article.publishedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return name
    return "$name · ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
}

/** A Soapbox article: its photo, title, byline and disclosure, then its body. A teaser links to the publication. */
@Composable
fun ArticleScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<Article>("article-$id")
    val fetch: suspend () -> Article = { graph.audienceKit.article(id) ?: throw AudienceKitException.NotFound() }
    LaunchedEffect(id) { loader.load(id, fetch = fetch) }
    val state by loader.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    // Reached without the note (a push or a link): ask first, and back out on "Not now".
    val userId = rememberUserId()
    // Asked once the story loads: a reader who tipped its publication to read it without ads has nothing to acknowledge.
    var askedAboutAds by rememberSaveable { mutableStateOf(false) }
    var askingAboutAds by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state) {
        val loaded = (state as? LoadState.Loaded)?.value ?: return@LaunchedEffect
        if (!askedAboutAds) {
            askedAboutAds = true
            askingAboutAds = !graph.publisherAds.isAcknowledged(userId) && !loaded.showsWithoutAds
        }
    }
    if (askingAboutAds) {
        PublisherAdsDialog(
            onAccept = {
                graph.publisherAds.acknowledge(userId)
                askingAboutAds = false
            },
            onDecline = {
                askingAboutAds = false
                navigator.back()
            },
        )
    }

    ScreenScaffold((state as? LoadState.Loaded)?.value?.publication?.name.orEmpty(), onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(id, refresh = true, fetch = fetch) }) { article ->
            Feed {
                graph.audienceKit.url(article.heroPhotoUrl)?.let { photo ->
                    item(key = "hero") { RemoteImage(photo, Modifier.fillMaxWidth().heightIn(min = 200.dp).clip(RoundedCornerShape(16.dp))) }
                }
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                        if (article.isOpEd) Text(SoapboxCopy.OP_ED.uppercase(), style = HotMessType.caption, color = tokens.accentInk)
                        Text(article.title, style = HotMessType.title)
                        if (!article.body.isNullOrBlank()) {
                            article.summary?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = HotMessType.subheading, color = tokens.inkMuted)
                            }
                        }
                        Text(
                            dateline(article) + if (article.edited) " · ${SoapboxCopy.EDITED}" else "",
                            style = HotMessType.label,
                            color = tokens.accentInk,
                            modifier = Modifier
                                .clickable(role = Role.Button) { navigator.open(AppRoute.PublicationDetail(article.publication.id)) }
                                .testTag("article.publication"),
                        )
                    }
                }
                article.disclosure?.takeIf { it.isNotBlank() }?.let { disclosure ->
                    item(key = "disclosure") {
                        Surface(color = tokens.surfaceRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("article.disclosure")) {
                            Text(disclosure, style = HotMessType.bodySmall, color = tokens.inkMuted, modifier = Modifier.padding(Space.s3))
                        }
                    }
                }
                if (article.showsWithoutAds) {
                    item(key = "adFree") { AdFreeThanks(article.publication.name) }
                } else if (!article.teaser) {
                    item(key = "adFreeTip") {
                        AdFreeTipButton(article.publication.id, article.publication.name, article.publication.adFreeOffered) {
                            loader.load(id, refresh = true, quiet = true, fetch = fetch)
                        }
                    }
                }
                item(key = "body") {
                    Text(articleText(article.body ?: article.summary.orEmpty()), style = HotMessType.body)
                }
                article.canonicalUrl?.let { url ->
                    item(key = "readMore") { SecondaryButton(SoapboxCopy.READ_MORE, onClick = { uriHandler.openUri(url) }) }
                }
                val subjects = article.venues.orEmpty().map { it to AppRoute.VenueDetail(it.id) } +
                    article.events.orEmpty().map { it to AppRoute.EventDetail(it.id) } +
                    article.people.orEmpty().map { it to AppRoute.PersonDetail(it.id) }
                if (subjects.isNotEmpty()) {
                    item(key = "subjects") {
                        DetailSection("In this article") {
                            subjects.forEach { (subject, route) ->
                                Text(
                                    subject.name.orEmpty(),
                                    style = HotMessType.body,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(role = Role.Button) { navigator.open(route) }
                                        .padding(horizontal = Space.s5, vertical = Space.s3),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The article's Markdown as the reader sees it: paragraphs kept, `#` headings and `**`/`*`/`_` emphasis marks
 * dropped, links shown as their text.
 */
fun articleText(markdown: String): String = markdown
    .lines()
    .joinToString("\n") { it.replace(Regex("^#{1,6}\\s+"), "") }
    .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
    .replace(Regex("(\\*\\*|__|\\*|_)(\\S[^*_]*?)\\1"), "$2")

/** A Soapbox publication: what it covers, its articles, the reader's op-eds to it, and "Write an op-ed". */
@Composable
fun PublicationScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<PublicationPage>("publication-$id")
    val fetch: suspend () -> PublicationPage = {
        val publication = graph.audienceKit.publication(id) ?: throw AudienceKitException.NotFound()
        PublicationPage(
            publication,
            runCatching { graph.audienceKit.articles(publicationId = id) }.getOrDefault(emptyList()),
            runCatching { graph.audienceKit.myOpEds() }.getOrDefault(emptyList()).filter { it.publication.id == id },
        )
    }
    LaunchedEffect(id) { loader.load(id, fetch = fetch) }
    val state by loader.state.collectAsStateWithLifecycle()
    var writing by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    ScreenScaffold((state as? LoadState.Loaded)?.value?.publication?.name.orEmpty(), onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(id, refresh = true, fetch = fetch) }) { page ->
            val publication = page.publication
            if (writing) {
                OpEdComposer(publication, onDismiss = { writing = false }) { loader.load(id, refresh = true, quiet = true, fetch = fetch) }
            }
            Feed {
                item(key = "header") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Top) {
                        graph.audienceKit.url(publication.photoUrl)?.let { RemoteImage(it, Modifier.size(64.dp).clip(CircleShape)) }
                        Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                            Text(publication.name, style = HotMessType.title)
                            publication.about?.takeIf { it.isNotBlank() }?.let { Text(it, style = HotMessType.body, color = tokens.inkMuted) }
                            publication.websiteUrl?.let { url ->
                                Text(
                                    url.removePrefix("https://").removePrefix("http://").trimEnd('/'),
                                    style = HotMessType.label,
                                    color = tokens.accentInk,
                                    modifier = Modifier.clickable(role = Role.Button) { uriHandler.openUri(url) },
                                )
                            }
                        }
                    }
                }
                if (publication.viewerAdFree == true) {
                    item(key = "adFree") { AdFreeThanks(publication.name, publication.viewerAdFreeUntil) }
                } else {
                    item(key = "adFreeTip") {
                        AdFreeTipButton(publication.id, publication.name, publication.adFreeOffered) {
                            loader.load(id, refresh = true, quiet = true, fetch = fetch)
                        }
                    }
                }
                if (publication.acceptsOpEds) {
                    item(key = "write") {
                        PrimaryButton(SoapboxCopy.WRITE_OP_ED, onClick = { writing = true }, modifier = Modifier.fillMaxWidth().testTag("publication.writeOpEd"))
                    }
                }
                if (page.myOpEds.isNotEmpty()) {
                    item(key = "myOpEds") {
                        DetailSection("Your op-eds") {
                            page.myOpEds.forEach { opEd ->
                                Column(Modifier.padding(horizontal = Space.s5, vertical = Space.s3)) {
                                    Text(opEd.title, style = HotMessType.body)
                                    opEd.status?.let { Text(SoapboxCopy.status(it), style = HotMessType.caption, color = tokens.inkMuted) }
                                    if (opEd.status == ArticleStatus.DECLINED) {
                                        opEd.reviewNote?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = HotMessType.caption, color = tokens.inkMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (page.articles.isEmpty()) {
                    item(key = "empty") { Text("Nothing published yet.", style = HotMessType.body, color = tokens.inkMuted) }
                } else {
                    items(page.articles, key = { "article-${it.id}" }) { article ->
                        ArticleRow(article, onClick = rememberOpenArticle(article, navigator))
                    }
                }
            }
        }
    }
}

/** What a publication's page shows. */
data class PublicationPage(val publication: Publication, val articles: List<Article>, val myOpEds: List<Article>)

/** "Write an op-ed": a title, an optional summary and the piece, sent under the reader's name. */
@Composable
fun OpEdComposer(publication: Publication, onDismiss: () -> Unit, onSent: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    if (sent) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(SoapboxCopy.OP_ED_SENT, style = HotMessType.body) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (body.isBlank()) onDismiss() },
        title = { Text(SoapboxCopy.WRITE_OP_ED, style = HotMessType.heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                publication.opEdGuidelines?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = HotMessType.caption, color = tokens.inkMuted)
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("opEd.title"))
                OutlinedTextField(summary, { summary = it }, label = { Text("Summary (optional)") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    body,
                    { body = it },
                    label = { Text("Your op-ed") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("opEd.body"),
                )
                Text(
                    "It goes out under your name. ${publication.name} may edit it before it runs.",
                    style = HotMessType.caption,
                    color = tokens.inkMuted,
                )
                failure?.let { Text(it, style = HotMessType.caption, color = tokens.accentInk) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && title.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.testTag("opEd.send"),
                onClick = {
                    sending = true
                    failure = null
                    scope.launch {
                        try {
                            graph.audienceKit.submitOpEd(publication.id, title.trim(), body, summary.trim().ifBlank { null })
                            sent = true
                            onSent()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The API says why: three waiting already, or the publication stopped taking op-eds.
                            failure = e.message ?: "Couldn't send it. Please try again."
                        } finally {
                            sending = false
                        }
                    }
                },
            ) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
