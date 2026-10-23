package social.hotmess.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.components.PostTag
import social.hotmess.android.ui.components.RemoteImage
import social.hotmess.android.ui.components.SectionTitle
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.PhotoColors
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.AppRoute
import social.hotmess.core.EventPhoto
import social.hotmess.core.PostsSubject
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import java.io.ByteArrayOutputStream

/**
 * A page's photos: photographers' and the ones members took with the app's camera, newest first, a
 * photographer's tagged Pro. At the venue the user is at ([cameraVenueId]) it leads with "Take a photo":
 * camera only, posted to the venue, the event under way and whoever is on stage. Loads on its own; draws
 * nothing while loading, when there are none and no camera, or on an API without photos.
 */
@Composable
fun PhotosSection(
    subject: PostsSubject,
    navigator: Navigator,
    cameraVenueId: String? = null,
    title: String = "Photos",
) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var photos by remember(subject) { mutableStateOf<List<EventPhoto>>(emptyList()) }
    var capturing by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<Bitmap?>(null) }
    var viewing by remember { mutableStateOf<EventPhoto?>(null) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }

    LaunchedEffect(subject) {
        photos = try {
            graph.api.photos(subject)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            photos
        }
    }

    if (capturing) {
        CameraCaptureDialog(
            onCaptured = { capturing = false; captured = it },
            onDismiss = { capturing = false },
        )
    }
    val venueId = cameraVenueId
    captured?.let { image ->
        if (venueId != null) {
            CrowdPhotoReviewDialog(image, venueId, onPosted = { photos = listOf(it) + photos }) { captured = null }
        }
    }
    viewing?.let { photo ->
        PhotoViewerDialog(
            photo,
            onPerson = { id -> viewing = null; navigator.open(AppRoute.PersonDetail(id)) },
            onReport = { viewing = null; reporting = ReportSubject(ReportTarget.PHOTO, photo.id) },
            onRemove = {
                viewing = null
                photos = photos.filterNot { it.id == photo.id }
                scope.launch { runCatching { graph.api.removePhoto(photo.id) } }
            },
        ) { viewing = null }
    }
    reporting?.let { ReportDialog(it) { reporting = null } }

    if (photos.isEmpty() && cameraVenueId == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            if (cameraVenueId != null) {
                Row(
                    Modifier
                        .clickable(role = Role.Button) { capturing = true }
                        .padding(horizontal = Space.s5, vertical = Space.s1)
                        .testTag("photos.take"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s1),
                ) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(18.dp))
                    Text("Take a photo", style = HotMessType.label, color = tokens.accentInk)
                }
            }
        }
        if (photos.isEmpty()) {
            Text(
                "Snap the stage. Your photo goes on the venue's and the event's pages, and the performers' too.",
                style = HotMessType.bodySmall,
                color = tokens.inkMuted,
                modifier = Modifier.padding(horizontal = Space.s5),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.s4),
            ) {
                items(photos, key = { it.id }) { photo ->
                    PhotoThumbnail(photo) { viewing = photo }
                }
            }
        }
    }
}

@Composable
private fun PhotoThumbnail(photo: EventPhoto, onClick: () -> Unit) {
    val label = listOfNotNull(photo.altText ?: photo.peopleNames ?: "Photo", photo.credit).joinToString(". ")
    Box(
        Modifier
            .size(132.dp)
            .clip(Radius.lg)
            .background(PhotoColors.placeholder)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        RemoteImage(photo.url, Modifier.fillMaxSize())
        if (photo.isProfessional) {
            PostTag("Pro", isVenue = true, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}

/** A photo full size: who took it, who's on stage in it, and Remove (theirs) or Report. */
@Composable
private fun PhotoViewerDialog(
    photo: EventPhoto,
    onPerson: (String) -> Unit,
    onReport: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingRemove by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = tokens.surface, contentColor = tokens.ink) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
                    Row(Modifier.weight(1f)) {}
                    if (photo.viewerCanRemove) {
                        TextButton(onClick = { confirmingRemove = true }) { Text("Remove", color = tokens.danger) }
                    } else {
                        TextButton(onClick = onReport) { Text("Report") }
                    }
                }
                RemoteImage(
                    photo.url,
                    Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(horizontal = Space.s4).clip(Radius.photo),
                    contentScale = ContentScale.Fit,
                )
                Column(Modifier.padding(Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                        PostTag(if (photo.isProfessional) "Pro" else "Crowd", isVenue = photo.isProfessional)
                        photo.credit?.let { Text(it, style = HotMessType.label) }
                    }
                    photo.event?.let { Text("At ${it.name}", style = HotMessType.bodySmall, color = tokens.inkMuted) }
                    photo.caption?.let { Text(it, style = HotMessType.body) }
                    if (photo.people.isNotEmpty()) {
                        Text("On stage", style = HotMessType.caption, color = tokens.inkMuted, modifier = Modifier.padding(top = Space.s2))
                        photo.people.forEach { person ->
                            Text(
                                person.name,
                                style = HotMessType.label,
                                color = tokens.accentInk,
                                modifier = Modifier.clickable(role = Role.Button) { onPerson(person.id) }.padding(vertical = Space.s1),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove this photo?") },
            text = { Text("It comes off every page it's on.") },
            confirmButton = { TextButton(onClick = { confirmingRemove = false; onRemove() }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") } },
            containerColor = tokens.surfaceRaised,
        )
    }
}

/** The photo just taken, before it goes up: Post or Discard. Says where it goes, as the API tags the stage. */
@Composable
private fun CrowdPhotoReviewDialog(image: Bitmap, venueId: String, onPosted: (EventPhoto) -> Unit, onDismiss: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!posting) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = tokens.surface, contentColor = tokens.ink) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !posting) { Text("Discard") }
                    Text("New photo", style = HotMessType.heading, modifier = Modifier.weight(1f).padding(start = Space.s2))
                    if (posting) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = Space.s2))
                    } else {
                        TextButton(
                            modifier = Modifier.testTag("photos.post"),
                            onClick = {
                                posting = true
                                failure = null
                                scope.launch {
                                    try {
                                        val jpeg = withContext(Dispatchers.Default) { image.uploadJpeg() }
                                        onPosted(graph.api.takePhoto(venueId, jpeg, description))
                                        onDismiss()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        failure = e.message ?: "Couldn't post the photo. Please try again."
                                    } finally {
                                        posting = false
                                    }
                                }
                            },
                        ) { Text("Post") }
                    }
                }
                Image(
                    image.asImageBitmap(),
                    contentDescription = "The photo you took",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.s4).clip(Radius.photo),
                )
                Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    Text(
                        "It goes on the venue's page, tonight's event and the pages of whoever's on stage, credited to you by your first name and initial.",
                        style = HotMessType.bodySmall,
                        color = tokens.inkMuted,
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(300) },
                        label = { Text("Describe it for screen readers (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    failure?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
                }
            }
        }
    }
}

/**
 * The camera, and only the camera (CameraX): no photo library and no video, so a photo on a venue's page
 * was taken there, then. Asks for camera access first.
 */
@Composable
private fun CameraCaptureDialog(onCaptured: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var shooting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            when {
                granted -> CapturePreview(capture)
                asked -> Text(
                    "Allow camera access for Hot Mess in Settings to take photos.",
                    style = HotMessType.body,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(Space.s6),
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(Space.s2)) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = androidx.compose.ui.graphics.Color.White)
            }
            if (granted) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = Space.s8)
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White)
                        .clickable(role = Role.Button, enabled = !shooting) {
                            shooting = true
                            capture.takePicture(
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageCapturedCallback() {
                                    override fun onCaptureSuccess(image: ImageProxy) {
                                        val bitmap = image.use { it.toUprightBitmap() }
                                        shooting = false
                                        onCaptured(bitmap)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        shooting = false
                                    }
                                },
                            )
                        }
                        .semantics { contentDescription = "Take photo" }
                        .testTag("photos.shutter"),
                )
            }
        }
    }
}

@Composable
private fun CapturePreview(capture: ImageCapture) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) { onDispose { ProcessCameraProvider.getInstance(context).get().unbind(capture) } }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val view = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            }, ContextCompat.getMainExecutor(viewContext))
            view
        },
        onRelease = { ProcessCameraProvider.getInstance(context).get().unbindAll() },
    )
}

/** The captured frame as a bitmap, turned upright. */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return decoded
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

/** A JPEG at most [longEdge] pixels on its longer side, for uploading over a venue's weak signal. */
internal fun Bitmap.uploadJpeg(longEdge: Int = 2048, quality: Int = 80): ByteArray {
    val scale = minOf(1f, longEdge.toFloat() / maxOf(width, height))
    val scaled = if (scale < 1f) Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true) else this
    return ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }
}
