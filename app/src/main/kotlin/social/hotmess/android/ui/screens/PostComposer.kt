package social.hotmess.android.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.ManagedListings
import social.hotmess.core.Post
import social.hotmess.core.PostAuthor
import social.hotmess.core.PostPhotoUpload
import social.hotmess.core.PostTarget
import social.hotmess.core.PostVisibility
import social.hotmess.core.PostsSubject

/** The most a post says, as Velvet and Backstage allow. */
private const val MAX_BODY = 2000

/** What the composer is for: a new post as one of [authors] on [target], or editing [post]. */
sealed interface PostComposerMode {
    data class New(val authors: List<PostAuthor>, val target: PostTarget) : PostComposerMode

    data class Edit(val post: Post) : PostComposerMode
}

/** "New post" on a page its viewer manages: who they can post as, and the composer to do it. */
class PostWriter(val authors: List<PostAuthor>, val start: () -> Unit) {
    val canWrite: Boolean get() = authors.isNotEmpty()
}

/**
 * Who the signed-in user can post as on [subject], and the composer for it, shown here; [onPosted] puts the new post in
 * the page's list. On an event, [eventName] names it and the venue and people on it decide who can post.
 */
@Composable
fun rememberPostWriter(
    subject: PostsSubject,
    eventName: String? = null,
    eventVenueId: String? = null,
    eventPeople: List<String> = emptyList(),
    onPosted: (Post) -> Unit,
): PostWriter {
    val listings by LocalAppGraph.current.listings.state.collectAsStateWithLifecycle()
    var composing by remember { mutableStateOf(false) }
    val authors = remember(listings, subject, eventVenueId, eventPeople) { listings.authors(subject, eventVenueId, eventPeople) }
    if (composing && authors.isNotEmpty()) {
        val target = if (subject is PostsSubject.Event) PostTarget.Event(subject.id, eventName.orEmpty()) else PostTarget.Page
        PostComposer(PostComposerMode.New(authors, target), onDismiss = { composing = false }, onSaved = onPosted)
    }
    return remember(authors) { PostWriter(authors) { composing = true } }
}

/** A photo picked for a new post, scaled for upload, with its description. */
private class PickedPhoto(val image: Bitmap) {
    var altText by mutableStateOf("")
}

/**
 * Writing a post, or editing one: who it's from, what it says, up to four photos with descriptions (new posts only),
 * Public or Community, pinned, and comments. Saves through the API, then hands back the post as saved.
 */
@Composable
fun PostComposer(mode: PostComposerMode, onDismiss: () -> Unit, onSaved: (Post) -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editing = (mode as? PostComposerMode.Edit)?.post
    val authors = (mode as? PostComposerMode.New)?.authors.orEmpty()
    var author by remember { mutableStateOf(authors.firstOrNull()) }
    var body by remember { mutableStateOf(editing?.body.orEmpty()) }
    var visibility by remember { mutableStateOf(editing?.visibility ?: PostVisibility.COMMUNITY) }
    var pinned by remember { mutableStateOf(editing?.pinned ?: false) }
    var commentsOpen by remember { mutableStateOf(editing?.commentsOpen ?: true) }
    val photos = remember { mutableStateListOf<PickedPhoto>() }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ManagedListings.MAX_PHOTOS),
    ) { uris ->
        scope.launch {
            val room = ManagedListings.MAX_PHOTOS - photos.size
            val picked = withContext(Dispatchers.IO) { uris.take(room).mapNotNull { context.uploadBitmap(it) } }
            photos.addAll(picked.map(::PickedPhoto))
        }
    }

    val canSave = !saving && (body.isNotBlank() || photos.isNotEmpty()) && (editing != null || author != null)

    fun save() {
        if (!canSave) return
        saving = true
        failure = null
        scope.launch {
            try {
                val saved = if (editing != null) {
                    graph.api.updatePost(editing.id, body = body, pinned = pinned, commentsOpen = commentsOpen, visibility = visibility)
                } else {
                    val target = (mode as PostComposerMode.New).target
                    val uploads = withContext(Dispatchers.Default) { photos.map { PostPhotoUpload(it.image.uploadJpeg(), it.altText) } }
                    graph.api.createPost(checkNotNull(author), target, body, uploads, pinned, commentsOpen, visibility)
                }
                onSaved(saved)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiError.Rejected) {
                failure = e.message
            } catch (_: Exception) {
                failure = "Couldn't save the post. Check your connection and try again."
            } finally {
                saving = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = tokens.surface, contentColor = tokens.ink) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Text(
                        if (editing != null) "Edit post" else "New post",
                        style = HotMessType.heading,
                        modifier = Modifier.weight(1f).padding(start = Space.s2),
                    )
                    if (saving) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = Space.s2))
                    } else {
                        TextButton(onClick = ::save, enabled = canSave, modifier = Modifier.testTag("post.save")) {
                            Text(if (editing != null) "Save" else "Post")
                        }
                    }
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Space.s4),
                    verticalArrangement = Arrangement.spacedBy(Space.s4),
                ) {
                    if (authors.size > 1) {
                        ComposerSection("Post as") {
                            authors.forEach { option ->
                                ChoiceRow(option.name, selected = option == author) { author = option }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it.take(MAX_BODY) },
                        label = { Text("What's happening?") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("post.body"),
                        supportingText = { Text("${body.length}/$MAX_BODY") },
                    )
                    if (editing == null) {
                        photos.forEachIndexed { index, photo ->
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Top) {
                                Image(
                                    photo.image.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clip(Radius.sm),
                                )
                                OutlinedTextField(
                                    value = photo.altText,
                                    onValueChange = { photo.altText = it.take(300) },
                                    label = { Text("Describe it for screen readers") },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                )
                                IconButton(onClick = { photos.removeAt(index) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove photo")
                                }
                            }
                        }
                        if (photos.size < ManagedListings.MAX_PHOTOS) {
                            TextButton(onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                                Text(
                                    if (photos.isEmpty()) "Add photos" else "Add another (${photos.size}/${ManagedListings.MAX_PHOTOS})",
                                    modifier = Modifier.padding(start = Space.s2),
                                )
                            }
                        }
                    }
                    ComposerSection("Who sees it") {
                        PostVisibility.entries.forEach { option ->
                            ChoiceRow(
                                option.title,
                                option.summary,
                                icon = if (option == PostVisibility.PUBLIC) Icons.Rounded.Public else Icons.Rounded.Groups,
                                selected = option == visibility,
                            ) { visibility = option }
                        }
                    }
                    SwitchRow("Pin to the top", pinned) { pinned = it }
                    SwitchRow("Allow comments", commentsOpen) { commentsOpen = it }
                    Text(
                        whereItShows(mode, author ?: editing?.author),
                        style = HotMessType.bodySmall,
                        color = tokens.inkMuted,
                    )
                    failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
                }
            }
        }
    }
}

/** "Shows on The Wildrose's page and Drag Bingo." */
private fun whereItShows(mode: PostComposerMode, author: PostAuthor?): String {
    val page = author?.let { "${it.name}'s page" } ?: "their page"
    val event = when (mode) {
        is PostComposerMode.New -> (mode.target as? PostTarget.Event)?.name
        is PostComposerMode.Edit -> mode.post.event?.name
    }
    return if (event == null) "Shows on $page." else "Shows on ${event.ifBlank { "the event" }} and $page."
}

@Composable
private fun ComposerSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        Text(title, style = HotMessType.label, color = tokens.inkMuted)
        content()
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String? = null, icon: ImageVector? = null, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        RadioButton(selected = selected, onClick = null)
        icon?.let { Icon(it, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = HotMessType.body)
            detail?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = HotMessType.body, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** The picked photo, upright and at most 2048 pixels on its longer side; null when it can't be read. */
private fun Context.uploadBitmap(uri: Uri, longEdge: Int = 2048): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val size = info.size
        val scale = minOf(1f, longEdge.toFloat() / maxOf(size.width, size.height))
        if (scale < 1f) decoder.setTargetSize((size.width * scale).toInt(), (size.height * scale).toInt())
    }
}.getOrNull()
