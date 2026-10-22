package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.ui.Loader
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.ChatComposer
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.LoadStateView
import social.hotmess.android.ui.components.Message
import social.hotmess.android.ui.components.PostActions
import social.hotmess.android.ui.components.PostCard
import social.hotmess.android.ui.components.PostRow
import social.hotmess.android.ui.components.PostTag
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SectionTitle
import social.hotmess.android.ui.components.linkified
import social.hotmess.android.ui.rememberLoader
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.AppRoute
import social.hotmess.core.BlockedUser
import social.hotmess.core.LoadState
import social.hotmess.core.Post
import social.hotmess.core.PostComment
import social.hotmess.core.PostDetail
import social.hotmess.core.PostPoster
import social.hotmess.core.PostsFilter
import social.hotmess.core.PostsSubject
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import social.hotmess.core.recent

/** A page's posts, loaded on their own so the page shows on an API without posts. */
@Composable
fun rememberPosts(subject: PostsSubject, from: PostsFilter = PostsFilter.ALL): Loader<List<Post>> {
    val graph = LocalAppGraph.current
    val loader = rememberLoader<List<Post>>("posts-${subject.kind}-${subject.id}")
    LaunchedEffect(subject, from) { loader.load(subject to from) { graph.api.posts(subject, from) } }
    return loader
}

/**
 * The card actions every post list shares: open a route, report the post, and "Hide posts from {name}"
 * (confirmed, then [onHidden] drops their posts from the list). Shows its own dialogs.
 */
@Composable
fun rememberPostActions(navigator: Navigator, onHidden: (PostPoster) -> Unit = {}): PostActions {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val signedIn = rememberUserId() != null
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    var hiding by remember { mutableStateOf<PostPoster?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    reporting?.let { ReportDialog(it) { reporting = null } }
    hiding?.let { poster ->
        AlertDialog(
            onDismissRequest = { hiding = null },
            title = { Text("Hide posts from ${poster.name}?", style = HotMessType.heading) },
            text = { Text("You won't see their posts in Hot Mess. Their page and chat stay as they are.", style = HotMessType.body) },
            confirmButton = {
                TextButton(onClick = {
                    hiding = null
                    scope.launch {
                        try {
                            graph.api.mutePosts(poster)
                            onHidden(poster)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failure = "Couldn't hide their posts. Please try again."
                        }
                    }
                }) { Text("Hide posts") }
            },
            dismissButton = { TextButton(onClick = { hiding = null }) { Text("Cancel") } },
            containerColor = tokens.surfaceRaised,
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            containerColor = tokens.surfaceRaised,
        )
    }
    return remember(navigator, signedIn, onHidden) {
        PostActions(
            open = navigator::open,
            report = { reporting = ReportSubject(ReportTarget.POST, it.id) },
            hidePoster = { hiding = it },
            signedIn = signedIn,
        )
    }
}

/**
 * A page's posts: the venue's "Posts", an event's "From the venue and hosts", a talent's "Posts". Two cards
 * then "See all"; nothing while loading, when there are none, or when they couldn't load.
 */
fun LazyListScope.postsSection(
    title: String,
    subject: PostsSubject,
    state: LoadState<List<Post>>,
    actions: PostActions,
    limit: Int = 2,
) {
    val posts = state.valueOrNull.orEmpty()
    if (posts.isEmpty()) return
    item(key = "posts-title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            if (posts.size > limit) {
                Text(
                    "See all",
                    style = HotMessType.label,
                    color = tokens.accentInk,
                    modifier = Modifier
                        .clickable(role = Role.Button) { actions.open(AppRoute.Posts(subject, title)) }
                        .padding(horizontal = Space.s5, vertical = Space.s1)
                        .testTag("posts.seeAll"),
                )
            }
        }
    }
    items(posts.take(limit), key = { "post-${it.id}" }) { post ->
        PostCard(post, actions, showsEvent = subject !is PostsSubject.Event)
    }
}

/**
 * Now's posts as compact rows: "Latest from tonight" at an event or venue (the last 24 hours), "Updates in
 * {locale}" away from them. Two rows and "See all"; left out when there are none.
 */
@Composable
fun NowPostsSection(title: String, subject: PostsSubject, seeAll: AppRoute, navigator: Navigator, withinHours: Long? = null) {
    val loader = rememberPosts(subject)
    val state by loader.state.collectAsStateWithLifecycle()
    val all = state.valueOrNull.orEmpty()
    val posts = (withinHours?.let { all.recent(it) } ?: all).take(2)
    if (posts.isEmpty()) return
    DetailSection(title) {
        posts.forEach { post ->
            PostRow(post) { navigator.open(AppRoute.PostDetail(post.id)) }
            RowDivider()
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { navigator.open(seeAll) }
                .padding(horizontal = Space.s5, vertical = Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("See all", style = HotMessType.label, color = tokens.ink, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = tokens.inkMuted)
        }
    }
}

/** Every post on a page, from its section's "See all". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostsScreen(subject: PostsSubject, title: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val loader = rememberPosts(subject)
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val actions = rememberPostActions(navigator) { poster -> loader.update { posts -> posts.filterNot { it.poster == poster } } }
    ScreenScaffold(title, onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(subject, refresh = true) { graph.api.posts(subject) } }) { posts ->
            PullToRefreshBox(refreshing, onRefresh = { loader.load(subject, refresh = true) { graph.api.posts(subject) } }) {
                Feed {
                    items(posts, key = { it.id }) { post ->
                        PostCard(post, actions, showsPin = subject.floatsPins, showsEvent = subject !is PostsSubject.Event)
                    }
                }
            }
        }
    }
}

/**
 * "Updates in {locale}", from Now's "See all": the locale's posts from the last 14 days, newest first,
 * filtered to All, Venues, Talent or the events you're going to. Pins don't float here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(localeId: String, localeName: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val subject = remember(localeId) { PostsSubject.Locale(localeId) }
    val signedIn = rememberUserId() != null
    var filter by rememberSaveable { mutableStateOf(PostsFilter.ALL) }
    val loader = rememberPosts(subject, filter)
    val state by loader.state.collectAsStateWithLifecycle()
    val refreshing by loader.isRefreshing.collectAsStateWithLifecycle()
    val actions = rememberPostActions(navigator) { poster -> loader.update { posts -> posts.filterNot { it.poster == poster } } }
    // Going needs someone signed in to have RSVP'd.
    val filters = PostsFilter.entries.filter { signedIn || it != PostsFilter.GOING }

    ScreenScaffold(if (localeName.isBlank()) "Updates" else "Updates in $localeName", onBack = navigator::back) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.s4, vertical = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                filters.forEach { option ->
                    val chosen = option == filter
                    Text(
                        option.title,
                        style = HotMessType.label,
                        color = if (chosen) tokens.accentInk else tokens.ink,
                        modifier = Modifier
                            .clip(Radius.pill)
                            .background(if (chosen) tokens.accentSoft else tokens.controlFill)
                            .clickable(role = Role.Tab) { filter = option }
                            .semantics { selected = chosen }
                            .heightIn(min = 34.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("updates.filter.${option.name.lowercase()}"),
                    )
                }
            }
            Box(Modifier.weight(1f)) {
                LoadStateView(state, onRetry = { loader.load(subject to filter, refresh = true) { graph.api.posts(subject, filter) } }) { posts ->
                    PullToRefreshBox(refreshing, onRefresh = { loader.load(subject to filter, refresh = true) { graph.api.posts(subject, filter) } }) {
                        if (posts.isEmpty()) {
                            Message(Icons.Rounded.Campaign, "No updates yet", emptyText(filter, localeName))
                        } else {
                            Feed {
                                items(posts, key = { it.id }) { post -> PostCard(post, actions, showsPin = false) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun emptyText(filter: PostsFilter, localeName: String): String = when (filter) {
    PostsFilter.ALL -> "Posts from venues and talent in $localeName show here."
    PostsFilter.VENUES -> "No venue posts in the last two weeks."
    PostsFilter.TALENT -> "No talent posts in the last two weeks."
    PostsFilter.GOING -> "Posts on events you're going to show here."
}

/** The comments shown before "Show N earlier comments". */
private const val NEWEST_COMMENTS = 3

/** A post and its comments, oldest first, with the composer at the bottom. */
@Composable
fun PostScreen(id: String, navigator: Navigator) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoader<PostDetail?>("post-$id")
    val state by loader.state.collectAsStateWithLifecycle()
    val safety by graph.safety.state.collectAsStateWithLifecycle()
    val userId = rememberUserId()
    val actions = rememberPostActions(navigator)
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showsEarlier by rememberSaveable { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    var blocking by remember { mutableStateOf<BlockedUser?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) { loader.load(id) { graph.api.post(id) } }

    reporting?.let { ReportDialog(it) { reporting = null } }
    blocking?.let { person ->
        AlertDialog(
            onDismissRequest = { blocking = null },
            title = { Text("Block ${person.name.ifBlank { "them" }}?", style = HotMessType.heading) },
            text = { Text("They'll be removed from your feed instantly and our moderation team will be notified.", style = HotMessType.body) },
            confirmButton = {
                TextButton(onClick = {
                    blocking = null
                    scope.launch {
                        try {
                            graph.safety.block(person)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failure = "Couldn't block them. Please try again."
                        }
                    }
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { blocking = null }) { Text("Cancel") } },
            containerColor = tokens.surfaceRaised,
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            containerColor = tokens.surfaceRaised,
        )
    }

    fun send(post: Post) {
        val body = draft.trim()
        if (body.isEmpty() || sending) return
        sending = true
        scope.launch {
            try {
                val comment = graph.api.commentOnPost(post.id, body)
                draft = ""
                loader.update { detail -> detail?.copy(post = detail.post.copy(commentCount = detail.post.commentCount + 1), comments = detail.comments + comment) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Rejected) {
                failure = e.message
            } catch (_: Exception) {
                failure = "Couldn't send your comment. Check your connection and try again."
            } finally {
                sending = false
            }
        }
    }

    fun remove(comment: PostComment) {
        scope.launch {
            try {
                graph.api.removePostComment(comment.id)
                loader.update { detail ->
                    detail?.copy(
                        post = detail.post.copy(commentCount = (detail.post.commentCount - 1).coerceAtLeast(0)),
                        comments = detail.comments.filterNot { it.id == comment.id },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "Couldn't delete the comment. Please try again."
            }
        }
    }

    ScreenScaffold(state.valueOrNull?.post?.posterName ?: "Post", onBack = navigator::back) {
        LoadStateView(state, onRetry = { loader.load(id, refresh = true) { graph.api.post(id) } }) { detail ->
            if (detail == null) {
                Message(Icons.Rounded.Campaign, "Post removed", "This post isn't here any more.")
                return@LoadStateView
            }
            val post = detail.post
            val comments = detail.comments.filterNot { safety?.isBlocked(it.authorId) == true }
            val hidden = if (showsEarlier) 0 else (comments.size - NEWEST_COMMENTS).coerceAtLeast(0)
            Column(Modifier.fillMaxSize().imePadding()) {
                Feed(Modifier.weight(1f)) {
                    item(key = "post") { PostCard(post, actions, expanded = true) }
                    item(key = "comments-title") {
                        SectionTitle(if (comments.size == 1) "1 comment" else "${comments.size} comments")
                    }
                    if (hidden > 0) {
                        item(key = "earlier") {
                            Text(
                                "Show $hidden earlier comments",
                                style = HotMessType.label,
                                color = tokens.accentInk,
                                modifier = Modifier.clickable(role = Role.Button) { showsEarlier = true }.padding(horizontal = Space.s5),
                            )
                        }
                    }
                    items(comments.drop(hidden), key = { it.id }) { comment ->
                        CommentRow(
                            comment,
                            name = graph.api.friends.displayName(comment.authorId, comment.authorName) ?: comment.authorName,
                            isFriend = graph.api.friends.fullName(comment.authorId) != null,
                            isOwn = userId != null && social.hotmess.core.RecordId.same(userId, comment.authorId),
                            signedIn = userId != null,
                            onReport = {
                                reporting = ReportSubject(ReportTarget.POST_COMMENT, comment.id, BlockedUser(comment.authorId, comment.authorName, comment.authorAvatarUrl))
                            },
                            onBlock = { blocking = BlockedUser(comment.authorId, comment.authorName, comment.authorAvatarUrl) },
                            onDelete = { remove(comment) },
                        )
                    }
                }
                when {
                    !post.commentsOpen -> ComposerNote("Comments are off on this post.")
                    userId == null -> ComposerNote("Sign in to comment.")
                    else -> ChatComposer(
                        value = draft,
                        onValueChange = { draft = it },
                        onSend = { send(post) },
                        sendEnabled = draft.isNotBlank() && !sending,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerNote(text: String) {
    Text(
        text,
        style = HotMessType.bodySmall,
        color = tokens.inkMuted,
        modifier = Modifier.fillMaxWidth().background(tokens.surfaceRaised).padding(Space.s4),
    )
}

/** One comment: face (a friend's ringed), name, "Friend", time, text, and its options. */
@Composable
private fun CommentRow(
    comment: PostComment,
    name: String,
    isFriend: Boolean,
    isOwn: Boolean,
    signedIn: Boolean,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.s1).testTag("post.comment"),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.Top,
    ) {
        Avatar(
            comment.authorAvatarUrl,
            name,
            size = 32.dp,
            modifier = if (isFriend) Modifier.border(2.dp, tokens.accent, CircleShape) else Modifier,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = HotMessType.subheading, maxLines = 1)
                if (isFriend) PostTag("Friend", isVenue = false)
                Text(Post.relativeTime(comment.createdAt), style = HotMessType.bodySmall, color = tokens.inkMuted)
            }
            Text(linkified(comment.body), style = HotMessType.body)
        }
        if (signedIn) {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "Comment options", tint = tokens.inkMuted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (isOwn || comment.viewerCanRemove) {
                        DropdownMenuItem(text = { Text("Delete comment") }, onClick = {
                            menu = false
                            onDelete()
                        })
                    }
                    if (!isOwn) {
                        DropdownMenuItem(text = { Text("Report comment…") }, onClick = {
                            menu = false
                            onReport()
                        })
                        DropdownMenuItem(text = { Text("Block $name") }, onClick = {
                            menu = false
                            onBlock()
                        })
                    }
                }
            }
        }
    }
}
