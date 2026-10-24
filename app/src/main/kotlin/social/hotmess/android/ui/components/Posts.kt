package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import social.hotmess.android.ui.share
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.Formatting
import social.hotmess.core.Post
import social.hotmess.core.PostEvent
import social.hotmess.core.PostPhoto
import social.hotmess.core.PostPoster
import social.hotmess.core.PostVisibility

/** What a card's `…` menu and links ask the screen to do. */
class PostActions(
    val open: (AppRoute) -> Unit,
    val report: (Post) -> Unit = {},
    val hidePoster: (PostPoster) -> Unit = {},
    /** Whether to offer hiding and reporting: only someone signed in can. */
    val signedIn: Boolean = true,
)

/** The rounded square a place's face is drawn in, so places never look like people. */
private val PlaceFace = RoundedCornerShape(10.dp)

private val PostTagText = HotMessType.caption.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.02.em)

/**
 * A post as a full card (design system "Post card"): byline, metadata, body, photos, the event chip and
 * the comments footer, on `surface-raised`. [showsPin] is off where pins don't float (the updates feed),
 * [showsEvent] on the event's own page; [expanded] shows the whole text, on the post screen.
 */
@Composable
fun PostCard(
    post: Post,
    actions: PostActions,
    modifier: Modifier = Modifier,
    showsPin: Boolean = true,
    showsEvent: Boolean = true,
    expanded: Boolean = false,
) {
    val context = LocalContext.current
    var showsAll by rememberSaveable(post.id) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = Radius.photo,
        color = tokens.surfaceRaised,
        contentColor = tokens.ink,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            PostByline(post, showsPin, showsEvent, onOpen = actions.open) {
                PostOptions(post, actions)
            }
            if (post.body.isNotBlank()) {
                val limit = if (expanded || showsAll) Int.MAX_VALUE else BODY_LINES
                Text(linkified(post.body), style = HotMessType.body, maxLines = limit, overflow = TextOverflow.Ellipsis)
                if (limit != Int.MAX_VALUE && isLong(post.body, BODY_LINES)) {
                    Text(
                        "More",
                        style = HotMessType.label,
                        color = tokens.inkMuted,
                        modifier = Modifier.clickable(role = Role.Button) { showsAll = true },
                    )
                }
            }
            val photos = post.shownPhotos
            if (photos.isNotEmpty()) {
                PostPhotoGrid(photos, Modifier.bleed(Space.s4))
            }
            post.event?.takeIf { showsEvent }?.let { event ->
                PostEventChip(event) { actions.open(AppRoute.EventDetail(it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s5), verticalAlignment = Alignment.CenterVertically) {
                if (!expanded) {
                    FooterAction(commentsTitle(post), Icons.Rounded.ChatBubbleOutline) { actions.open(AppRoute.PostDetail(post.id)) }
                }
                FooterAction("Share", Icons.Rounded.Share) { context.share(post.shareUrl, post.body.take(SHARE_TITLE)) }
            }
        }
    }
}

private const val BODY_LINES = 6

/** Lays this out [gutter] wider on each side, out to the card's edges. */
private fun Modifier.bleed(gutter: Dp): Modifier = layout { measurable, constraints ->
    val extra = (gutter * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}
private const val SHARE_TITLE = 80

/** Whether the text likely runs past [lines] on a phone: more line breaks than that, or about 45 characters a line. */
internal fun isLong(text: String, lines: Int): Boolean = text.lines().size > lines || text.length > lines * 45

private fun commentsTitle(post: Post): String = when (post.commentCount) {
    0 -> if (post.commentsOpen) "Comment" else "Comments off"
    1 -> "1 comment"
    else -> "${post.commentCount} comments"
}

@Composable
private fun FooterAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(18.dp))
        Text(title, style = HotMessType.label, color = tokens.inkMuted)
    }
}

/** Guests get Share, Hide posts from {name} and Report; Share alone signed out. */
@Composable
private fun PostOptions(post: Post, actions: PostActions) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Rounded.MoreHoriz, contentDescription = "Post options", tint = tokens.inkMuted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Share post") }, onClick = {
                open = false
                context.share(post.shareUrl, post.body.take(SHARE_TITLE))
            })
            if (actions.signedIn) {
                post.poster?.let { poster ->
                    DropdownMenuItem(text = { Text("Hide posts from ${poster.name}") }, onClick = {
                        open = false
                        actions.hidePoster(poster)
                    })
                }
                DropdownMenuItem(text = { Text("Report post…") }, onClick = {
                    open = false
                    actions.report(post)
                })
            }
        }
    }
}

/** The poster's face, name, tag, the metadata line and, on a troupe's post, who wrote it. */
@Composable
fun PostByline(
    post: Post,
    showsPin: Boolean = true,
    showsEvent: Boolean = true,
    onOpen: (AppRoute) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    val openPoster = post.posterRoute?.let { route -> Modifier.clickable(role = Role.Button) { onOpen(route) } } ?: Modifier
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Top) {
        Avatar(post.posterAvatarUrl, post.posterName, size = 40.dp, shape = if (post.isVenuePost) PlaceFace else CircleShape, modifier = openPoster)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    post.posterName,
                    style = HotMessType.subheading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = openPoster.weight(1f, fill = false).semantics { heading() },
                )
                post.roleLabel?.let { PostTag(it, isVenue = post.isVenuePost) }
                if (post.pinned && showsPin) {
                    Icon(Icons.Rounded.PushPin, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(14.dp))
                }
                if (post.visibility == PostVisibility.PUBLIC) {
                    Icon(Icons.Rounded.Public, contentDescription = "Public", tint = tokens.inkMuted, modifier = Modifier.size(14.dp))
                }
            }
            Text(post.metadata(showsPin = showsPin, showsEvent = showsEvent), style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
            post.actingPersonName?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1) }
        }
        trailing()
    }
}

/** "Venue" in the accent; a talent's part on the event (or "Friend") in `accent-soft`, as chat's RoleTag draws them. */
@Composable
fun PostTag(label: String, isVenue: Boolean, modifier: Modifier = Modifier) {
    Text(
        label,
        style = PostTagText,
        color = if (isVenue) tokens.onAccent else tokens.accentInk,
        maxLines = 1,
        modifier = modifier.clip(Radius.pill).background(if (isVenue) tokens.accent else tokens.accentSoft).padding(horizontal = 6.dp),
    )
}

/** Plain text with its web links tappable; no other formatting. */
@Composable
fun linkified(text: String): AnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = tokens.accentInk))
    return remember(text, linkStyle) {
        buildAnnotatedString {
            var last = 0
            WEB_LINK.findAll(text).forEach { match ->
                append(text.substring(last, match.range.first))
                withLink(LinkAnnotation.Url(match.value, linkStyle)) { append(match.value) }
                last = match.range.last + 1
            }
            append(text.substring(last))
        }
    }
}

private val WEB_LINK = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]]""")

/** Photos edge to edge: one at 4:3, two side by side, three or four in a 2×2 grid. */
@Composable
fun PostPhotoGrid(photos: List<PostPhoto>, modifier: Modifier = Modifier) {
    val shown = photos.take(4)
    val gap = 2.dp
    when (shown.size) {
        1 -> PhotoTile(shown[0], modifier.fillMaxWidth().aspectRatio(4f / 3f))
        2 -> Row(modifier.fillMaxWidth().aspectRatio(2f), horizontalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { PhotoTile(it, Modifier.weight(1f).fillMaxSize()) }
        }
        else -> Column(modifier.fillMaxWidth().aspectRatio(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
            shown.chunked(2).forEach { pair ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    pair.forEach { PhotoTile(it, Modifier.weight(1f).fillMaxSize()) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(photo: PostPhoto, modifier: Modifier) {
    RemoteImage(photo.url, modifier.semantics { contentDescription = photo.altText ?: "Photo" })
}

/** The event a post is on: date tile, name and time, to the event. */
@Composable
fun PostEventChip(event: PostEvent, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.controlFill)
            .clickable(role = Role.Button) { onOpen(event.id) }
            .padding(Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        event.startAt?.let { start ->
            Column(
                Modifier.size(40.dp).clip(Radius.md).background(tokens.surfaceRaised),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(Formatting.monthAbbreviation(start).uppercase(), style = PostTagText, color = tokens.accentInk)
                Text(Formatting.dayOfMonth(start), style = HotMessType.subheading)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(event.name, style = HotMessType.subheading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            event.startAt?.let { Text(Formatting.dateTime(it), style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tokens.inkMuted)
    }
}

/** A post as a compact row for Now: face, name, tag, time, two lines and a thumbnail. */
@Composable
fun PostRow(post: Post, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s5, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(post.posterAvatarUrl, post.posterName, size = 32.dp, shape = if (post.isVenuePost) PlaceFace else CircleShape)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(post.posterName, style = HotMessType.subheading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                post.roleLabel?.let { PostTag(it, isVenue = post.isVenuePost) }
                Text(Post.relativeTime(post.createdAt), style = HotMessType.bodySmall, color = tokens.inkMuted)
            }
            Text(post.body, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        post.shownPhotos.firstOrNull()?.let { photo ->
            RemoteImage(photo.url, Modifier.size(48.dp).clip(Radius.sm))
        }
    }
}
