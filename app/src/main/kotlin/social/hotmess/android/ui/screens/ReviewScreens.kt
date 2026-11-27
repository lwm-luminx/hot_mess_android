package social.hotmess.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audiencekit.Article
import com.audiencekit.ArticleTopic
import com.audiencekit.Review
import com.audiencekit.reviews
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.components.SectionTitle
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens

// "Reviews" on venue, event and person pages: what's been written about it, newest first. A line quoted from a news
// article (City Weekly's guide to Salt Lake's LGBTQ bars) is credited to its publication, and tapping it opens the
// article, on the publication's site for a teaser. Draws nothing while loading, when there are none, or on an API
// without reviews.

/** How many reviews a page shows. */
const val REVIEWS_LIMIT: Int = 3

/** A page's reviews, loaded on their own; empty while loading, when there are none, or on an API without them. */
@Composable
fun rememberReviews(topic: ArticleTopic): List<Review> {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<Review>>("reviews-${topic::class.simpleName}-${topic.id}")
    LaunchedEffect(topic.id) {
        loader.load(topic.id) { runCatching { graph.audienceKit.reviews(topic) }.getOrDefault(emptyList()) }
    }
    val state by loader.state.collectAsStateWithLifecycle()
    return state.valueOrNull.orEmpty()
}

/** "Reviews": up to [limit] of them as cards. */
fun LazyListScope.reviewsSection(reviews: List<Review>, navigator: Navigator, limit: Int = REVIEWS_LIMIT) {
    if (reviews.isEmpty()) return
    item(key = "reviews-title") { SectionTitle("Reviews") }
    items(reviews.take(limit), key = { "review-${it.id}" }) { review ->
        val article = review.article
        if (article != null) {
            ReviewCard(review, article, onClick = rememberOpenArticle(article, navigator))
        } else {
            ReviewCard(review, null, onClick = null)
        }
    }
}

/** "City Weekly · Read the article ↗" (no arrow when it opens in the app). */
fun reviewCredit(article: Article): String =
    "${article.publication.name} · Read the article${if (article.teaser) " ↗" else ""}"

/** One review: its words, and for a quote, who it's from, opening the article. */
@Composable
fun ReviewCard(review: Review, article: Article?, onClick: (() -> Unit)?) {
    var modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
    if (onClick != null) modifier = modifier.clickable(role = Role.Button, onClick = onClick)
    Surface(modifier = modifier.testTag("reviews.review"), color = tokens.surfaceRaised, contentColor = tokens.ink) {
        Column(Modifier.padding(Space.s3), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(if (article == null) review.body else "“${review.body}”", style = HotMessType.body)
            if (article != null) {
                Text(reviewCredit(article), style = HotMessType.caption, color = tokens.inkMuted)
            }
        }
    }
}
