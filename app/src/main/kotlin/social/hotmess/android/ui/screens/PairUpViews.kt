package social.hotmess.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.Card
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.QrCode
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError
import social.hotmess.core.BlockedUser
import social.hotmess.core.HotMessApi
import social.hotmess.core.PairUpMatch
import social.hotmess.core.PairUpPartner
import social.hotmess.core.PairUpState
import social.hotmess.core.PairUpStatus
import social.hotmess.core.PairUpTopic
import social.hotmess.core.PassQr
import social.hotmess.core.ReportSubject
import social.hotmess.core.ReportTarget
import social.hotmess.core.VenuePairUp
import java.util.concurrent.Executors

/**
 * Pair me up at one venue: the user's state there and what they do in it. Every action answers with the new
 * state, so the sheet moves on at once; [poll] picks up what the partner did.
 */
@Stable
class PairUpController(
    private val api: HotMessApi,
    val venueId: String,
    private val scope: CoroutineScope,
    /**
     * Reports the device's position again. The API only opts in, and only pairs, people the app placed at the venue
     * in the last 15 minutes, and the phone otherwise reports only after moving ~100 m (like chat's heartbeat).
     */
    private val reportLocation: suspend () -> Unit = {},
) {
    private var reportedAt = 0L

    var pairUp by mutableStateOf<VenuePairUp?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    /** What the last scan said, when it wasn't a meeting. */
    var scanNotice by mutableStateOf<String?>(null)
    /** Set when a meeting is confirmed, for the "You met!" moment. */
    var celebrating by mutableStateOf(false)
    private var lastMetId: String? = null

    val state: PairUpState? get() = pairUp?.mine?.takeIf { it.isActive }
    val here: Boolean get() = pairUp?.here == true

    suspend fun load() {
        try {
            pairUp = api.pairUp(venueId)
            noteMeeting()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!loaded) error = message(e)
        }
        loaded = true
    }

    /** Refreshes while the sheet is open: quickly while waiting on the other person, slowly otherwise. */
    suspend fun poll() {
        while (true) {
            if (state != null && SystemClock.elapsedRealtime() - reportedAt > REPORT_EVERY_MS) report()
            load()
            val waiting = state?.let { it.status == PairUpStatus.SEARCHING || it.isPaired } == true
            delay(if (waiting) 3_000 else 15_000)
        }
    }

    fun join(lookFor: String?) = perform {
        report()
        api.joinPairUp(venueId, lookFor)
    }

    private suspend fun report() {
        reportedAt = SystemClock.elapsedRealtime()
        reportLocation()
    }
    fun accept() = state?.match?.let { match -> perform { api.acceptPairUp(match.id) } }
    fun skip(partner: Boolean) = state?.match?.let { match -> perform { api.skipPairUp(match.id, partner) } }
    fun cancel(match: PairUpMatch) = perform { api.cancelPairUp(match.id) }
    fun leave() = perform { api.leavePairUp(venueId) }
    fun share() = state?.match?.let { match -> perform { api.sharePairUp(match.id) } }

    /** A fresh code to show, or null when the API refused one. */
    suspend fun code(): String? {
        val match = state?.match ?: return null
        return try {
            api.showPairUpCode(match.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = message(e)
            null
        }
    }

    fun scan(code: String) {
        scope.launch {
            busy = true
            try {
                val scan = api.scanPairUpCode(venueId, code)
                scan.pairUp?.let { pairUp = VenuePairUp(here = here, mine = it) }
                scanNotice = scan.notice
                noteMeeting()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = message(e)
            } finally {
                busy = false
            }
        }
    }

    /** Celebrates each meeting once, whichever phone scanned. */
    private fun noteMeeting() {
        val mine = state ?: return
        val match = mine.match ?: return
        if (mine.status == PairUpStatus.MET && match.id != lastMetId) {
            lastMetId = match.id
            celebrating = true
        }
    }

    private fun perform(action: suspend () -> PairUpState?) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                pairUp = VenuePairUp(here = here, mine = action())
                noteMeeting()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = message(e)
                load()
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        /** Well inside the API's 15-minute presence window. */
        const val REPORT_EVERY_MS = 5 * 60_000L
    }

    private fun message(e: Exception): String =
        (e as? ApiError)?.message ?: "Something went wrong. Please try again."
}

/** The venue screen's way into Pair me up: where the venue has it and the user is there, or already in tonight. */
@Composable
fun PairUpRow(pairUp: VenuePairUp, onOpen: () -> Unit, onGoAgain: (() -> Unit)? = null) {
    val status = pairUp.mine?.status?.takeIf { it != PairUpStatus.LEFT }
    val headline = when (status) {
        PairUpStatus.SEARCHING -> "Finding someone to chat with"
        PairUpStatus.PROPOSED -> "You've got a match"
        PairUpStatus.WAITING -> "Waiting for them to say yes"
        PairUpStatus.REVEALED -> "Go find each other"
        PairUpStatus.MET -> "You met someone!"
        else -> "Quickie"
    }
    DetailSection("Meet someone") {
        Row(
            Modifier.fillMaxWidth().padding(Space.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Column(Modifier.weight(1f)) {
                Text(headline, style = HotMessType.subheading)
                Text(
                    when (status) {
                        null -> "Get paired with someone here and a topic to talk about."
                        PairUpStatus.MET -> "Up for another quickie?"
                        else -> "Open to see where you're at."
                    },
                    style = HotMessType.bodySmall,
                    color = tokens.inkMuted,
                )
            }
            // After a meeting the row starts the next quickie itself rather than reopening the finished one.
            when (status) {
                null -> PrimaryButton("Start", onClick = onOpen)
                PairUpStatus.MET -> PrimaryButton("Go again", onClick = onGoAgain ?: onOpen)
                else -> PrimaryButton("Open", onClick = onOpen)
            }
        }
    }
}

/**
 * Pair me up to chat: opt in, see a topic, accept or skip, find each other, and confirm the meeting with a QR
 * code. Nothing about the other person shows until both accept, and it disappears the moment either cancels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairUpSheet(controller: PairUpController, venueName: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    var scanning by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<ReportSubject?>(null) }
    val state = controller.state

    LaunchedEffect(controller) { controller.poll() }
    LaunchedEffect(controller.celebrating) {
        if (controller.celebrating) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s4)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            controller.scanNotice?.let { notice ->
                Card {
                    Row(Modifier.padding(Space.s4), verticalAlignment = Alignment.CenterVertically) {
                        Text(notice, style = HotMessType.body, modifier = Modifier.weight(1f))
                        TextButton(onClick = { controller.scanNotice = null }) { Text("OK") }
                    }
                }
            }
            state?.bonus?.let { bonus -> BonusCard(bonus) { controller.cancel(bonus) } }
            when {
                !controller.loaded -> Box(Modifier.fillMaxWidth().padding(Space.s12), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state == null -> Intro(controller, venueName)
                state.status == PairUpStatus.SEARCHING -> Searching(controller, state)
                state.status == PairUpStatus.PROPOSED || state.status == PairUpStatus.WAITING -> Proposed(controller, state)
                state.status == PairUpStatus.REVEALED -> Revealed(
                    controller,
                    state,
                    onScan = { scanning = true },
                    onReport = { partner ->
                        reporting = ReportSubject(ReportTarget.PERSON, partner.id, BlockedUser(partner.id, partner.name))
                    },
                )
                state.status == PairUpStatus.MET -> Met(
                    controller,
                    state,
                    onReport = { partner ->
                        reporting = ReportSubject(ReportTarget.PERSON, partner.id, BlockedUser(partner.id, partner.name))
                    },
                    onDone = onDismiss,
                )
            }
        }
    }

    if (scanning) {
        ScannerDialog(onCode = { scanned ->
            scanning = false
            controller.scan(scanned)
        }, onDismiss = { scanning = false })
    }
    reporting?.let { ReportDialog(it) { reporting = null } }
    if (controller.celebrating) {
        MetDialog(state?.metCount ?: 1) { controller.celebrating = false }
    }
    controller.error?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.error = null },
            confirmButton = { TextButton(onClick = { controller.error = null }) { Text("OK") } },
            title = { Text("Quickie") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun Intro(controller: PairUpController, venueName: String) {
    var lookFor by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Text("Meet someone at $venueName", style = HotMessType.title)
        Text(
            "We'll pair you with someone else here tonight who also tapped Quickie, and give you both something " +
                "to talk about. You only see each other once you both say yes, and either of you can cancel any time.",
            style = HotMessType.body,
            color = tokens.inkMuted,
        )
        LookForField(lookFor) { lookFor = it }
        PrimaryButton(
            "Start a quickie",
            onClick = { controller.join(lookFor) },
            modifier = Modifier.fillMaxWidth(),
            enabled = controller.here && !controller.busy,
        )
        if (!controller.here) {
            Text("Quickie opens once you're at $venueName.", style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
    }
}

@Composable
private fun LookForField(value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.take(60)) },
            label = { Text("How can they spot you? (optional)") },
            placeholder = { Text("Red jacket by the bar") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Only your match sees this, and only once you both say yes.", style = HotMessType.caption, color = tokens.inkMuted)
    }
}

@Composable
private fun Searching(controller: PairUpController, state: PairUpState) {
    var lookFor by remember(state.lookFor) { mutableStateOf(state.lookFor.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        if (state.match?.endReason != null) {
            Text("Your last match had to head off. No worries, we're finding someone new.", style = HotMessType.body)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Finding someone to chat with…", style = HotMessType.heading)
        }
        Text("Keep the app open, or we'll send a notification when someone's free.", style = HotMessType.bodySmall, color = tokens.inkMuted)
        LookForField(lookFor) { lookFor = it }
        SecondaryButton(
            "Update hint",
            onClick = { controller.join(lookFor) },
            enabled = !controller.busy && lookFor != state.lookFor.orEmpty(),
        )
        TextButton(onClick = { controller.leave() }, enabled = !controller.busy) {
            Text("Leave Quickie", color = tokens.danger)
        }
        if (state.metCount > 0) MetBadge(state.metCount)
    }
}

@Composable
private fun Proposed(controller: PairUpController, state: PairUpState) {
    val match = state.match ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        TopicCard(match.topic)
        if (match.accepted) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Waiting for them to say yes…", style = HotMessType.subheading)
            }
        } else {
            Text(
                if (match.partnerAccepted) "They're in. Say yes to see who to look for."
                else "Up for it? Once you both say yes, you'll see who to look for.",
                style = HotMessType.body,
            )
            PrimaryButton("Yes, let's chat", onClick = { controller.accept() }, modifier = Modifier.fillMaxWidth(), enabled = !controller.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
            SecondaryButton("New topic", onClick = { controller.skip(partner = false) }, modifier = Modifier.weight(1f), enabled = !controller.busy)
            SecondaryButton("Someone else", onClick = { controller.skip(partner = true) }, modifier = Modifier.weight(1f), enabled = !controller.busy)
        }
        CancelButton(controller, match)
    }
}

@Composable
private fun Revealed(
    controller: PairUpController,
    state: PairUpState,
    onScan: () -> Unit,
    onReport: (PairUpPartner) -> Unit,
) {
    val match = state.match ?: return
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        match.partner?.let { PartnerCard(it) { onReport(it) } }
        TopicCard(match.topic)
        Text("Found each other? Either of you scans the other's code.", style = HotMessType.body)
        // Both phones show their code straight away, so whoever's quicker scans: no deciding who shows and who
        // scans. A new topic clears the old code, so it's fetched again per match and topic.
        val key = "${match.id}-${match.topic.key}"
        var shown by remember { mutableStateOf<Pair<String, String>?>(null) }
        LaunchedEffect(key) { controller.code()?.let { shown = key to it } }
        CodeCard(shown?.takeIf { it.first == key }?.second)
        PrimaryButton(
            "Scan theirs",
            onClick = onScan,
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Rounded.QrCodeScanner,
            enabled = !controller.busy,
        )
        CancelButton(controller, match)
    }
}

/** The end of a quickie: share it if you both like, then go again or call it a night. Each person chooses. */
@Composable
private fun Met(controller: PairUpController, state: PairUpState, onReport: (PairUpPartner) -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = tokens.accent)
            Text("You met!", style = HotMessType.title)
        }
        state.match?.partner?.let { Text("Hope you and ${it.name} had a good chat.", style = HotMessType.body) }
        MetBadge(state.metCount)
        state.match?.let { ShareRow(controller, it) }
        PrimaryButton("Let's go again", onClick = { controller.join(null) }, modifier = Modifier.fillMaxWidth(), enabled = !controller.busy)
        SecondaryButton(
            "I'm done",
            onClick = {
                controller.leave()
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !controller.busy,
        )
        state.match?.partner?.let { partner ->
            TextButton(onClick = { onReport(partner) }, modifier = Modifier.fillMaxWidth()) {
                Text("Something not right? Report or block", style = HotMessType.bodySmall, color = tokens.inkMuted)
            }
        }
    }
}

/** Nothing names them in the venue's chat unless both say so. */
@Composable
private fun ShareRow(controller: PairUpController, match: PairUpMatch) {
    when {
        match.isShared -> Text("Shared in the venue chat", style = HotMessType.subheading)
        match.shared -> Text("Waiting for them to share too", style = HotMessType.body, color = tokens.inkMuted)
        else -> Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            SecondaryButton(
                "Share in the venue chat",
                onClick = { controller.share() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !controller.busy,
            )
            Text(
                if (match.partnerShared) {
                    "They'd like to share. If you do too, the chat gets your first names and topic."
                } else {
                    "If you both share, the chat gets your first names and what you talked about."
                },
                style = HotMessType.caption,
                color = tokens.inkMuted,
            )
        }
    }
}

@Composable
private fun CancelButton(controller: PairUpController, match: PairUpMatch) {
    TextButton(onClick = { controller.cancel(match) }, enabled = !controller.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Cancel", color = tokens.danger)
    }
}

@Composable
private fun TopicCard(topic: PairUpTopic) {
    Column(
        Modifier.fillMaxWidth().background(tokens.accentSoft, Radius.lg).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Text("YOUR TOPIC", style = HotMessType.caption, color = tokens.inkMuted)
        Text(topic.text, style = HotMessType.heading)
    }
}

/** Who to look for: a deliberately tiny picture, a name and their hint. */
@Composable
private fun PartnerCard(partner: PairUpPartner, onReport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        Avatar(partner.avatarUrl, partner.name, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text("Look for ${partner.name}", style = HotMessType.heading)
            partner.lookFor?.takeIf { it.isNotBlank() }?.let { Text("“$it”", style = HotMessType.body) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Report or block…") }, onClick = {
                    menu = false
                    onReport()
                })
            }
        }
    }
}

@Composable
private fun BonusCard(match: PairUpMatch, onDone: () -> Unit) {
    Card {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(match.partner?.let { "A topic for you and ${it.name}" } ?: "A topic for you two", style = HotMessType.subheading)
            TopicCard(match.topic)
            SecondaryButton("Done", onClick = onDone)
        }
    }
}

/** The night's badge: how many conversations they had (the same person twice is two conversations). */
@Composable
private fun MetBadge(count: Int) {
    Row(
        Modifier.background(tokens.accentSoft, Radius.pill).padding(horizontal = Space.s3, vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Icon(Icons.Rounded.WorkspacePremium, contentDescription = null, tint = tokens.accentInk)
        Text(
            if (count == 1) "You've had a conversation tonight" else "You've had $count conversations tonight",
            style = HotMessType.subheading,
        )
    }
}

@Composable
private fun MetDialog(count: Int, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = tokens.accent) },
        title = { Text("You met!", style = HotMessType.display) },
        text = { MetBadge(count) },
        confirmButton = { TextButton(onClick = onDone) { Text("Nice") } },
    )
}

/** The code to show: big, bright, and only for their partner. */
/** The code to show, on white so it scans in a dark room. It works once, and only for their match. */
@Composable
private fun CodeCard(code: String?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        val codeSize = Modifier.widthIn(max = 260.dp).fillMaxWidth()
        if (code != null) {
            QrCode(code, codeSize)
        } else {
            Box(codeSize.aspectRatio(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        Text("Your code. It works once, and only for your match.", style = HotMessType.bodySmall, color = tokens.inkMuted)
    }
}

/** Scans a partner's code with CameraX, reading each frame's luminance with the core module's ZXing reader. */
@Composable
private fun ScannerDialog(onCode: (String) -> Unit, onDismiss: () -> Unit) {
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

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(tokens.surface)) {
            when {
                granted -> CameraPreview(onCode)
                asked -> Text(
                    "Allow camera access for Hot Mess in Settings to scan their code.",
                    style = HotMessType.body,
                    modifier = Modifier.align(Alignment.Center).padding(Space.s6),
                )
            }
            Row(
                Modifier.fillMaxWidth().background(tokens.surface).padding(Space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                Text("Scan their code", style = HotMessType.heading, modifier = Modifier.weight(1f).padding(start = Space.s2))
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val view = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    image.use {
                        if (delivered) return@use
                        val plane = it.planes[0]
                        val bytes = ByteArray(plane.buffer.remaining()).also { array -> plane.buffer.get(array) }
                        val text = PassQr.decode(bytes, it.width, it.height, plane.rowStride)
                        if (text != null) {
                            delivered = true
                            ContextCompat.getMainExecutor(context).execute { onCode(text) }
                        }
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(viewContext))
            view
        },
        onRelease = { ProcessCameraProvider.getInstance(context).get().unbindAll() },
    )
}
