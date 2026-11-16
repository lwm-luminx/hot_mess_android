package social.hotmess.android.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.audiencekit.AudienceKitClient
import com.audiencekit.ContactHashBatch
import com.audiencekit.ContactHashing
import com.audiencekit.ContactMatch
import com.audiencekit.ContactMatchNames
import com.audiencekit.ContactMatchStatus
import com.audiencekit.ContactMatching
import com.audiencekit.ContactMatchingCopy
import com.audiencekit.ContactMatchingUnavailableException
import com.audiencekit.DeviceContact
import com.audiencekit.FriendRequest
import com.audiencekit.android.DeviceContacts
import com.audiencekit.android.contactMatchNames
import com.audiencekit.checkContacts
import com.audiencekit.contactMatching
import com.audiencekit.disableContactMatching
import com.audiencekit.respondToFriendRequest
import com.audiencekit.sendFriendRequest
import com.audiencekit.setContactMatchName
import com.audiencekit.startContactPhoneVerification
import com.audiencekit.verifyContactPhone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.hotmess.android.session.SessionStore
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.Card
import social.hotmess.android.ui.components.DangerRow
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ApiError

/**
 * Friends from contacts (the API's doc/CONTACT_FRIEND_MATCHING.md): the person's matching as the API has it and the
 * calls that change it. The hashing, the calls and the words are the SDK's; this holds what the screens show. One per
 * app, in the graph, so Now's card, the sheet and Me show the same thing.
 */
@Stable
class ContactFriends(
    context: Context,
    private val client: AudienceKitClient,
    audienceId: String,
    private val scope: CoroutineScope,
    /** What each match is saved as, on this phone only. */
    val names: ContactMatchNames = contactMatchNames(context, audienceId),
    /** The phone's region, for numbers saved without a country code. */
    private val region: () -> String? = { DeviceContacts.region(context) },
    private val readContacts: suspend () -> List<DeviceContact> = {
        withContext(Dispatchers.IO) { DeviceContacts.read(context) }
    },
) {
    private val preferences = context.getSharedPreferences(SessionStore.PREFERENCES, Context.MODE_PRIVATE)
    private val hiddenKey = "contactMatching.cardHiddenUntil.$audienceId"

    /** The person's matching; null until [load] has answered, or when the audience doesn't have it. */
    var state by mutableStateOf<ContactMatching?>(null)
        private set

    /** What the last check on this phone sent, for "We checked 457 contacts and skipped 61". */
    var lastBatch by mutableStateOf<ContactHashBatch?>(null)
        private set
    var working by mutableStateOf(false)
        private set

    /** Why the last call failed, in the API's words. */
    var error by mutableStateOf<String?>(null)

    /** Whether "Not now" on the card is still in force (30 days). */
    var cardHidden by mutableStateOf(preferences.getLong(hiddenKey, 0) > System.currentTimeMillis())
        private set

    /** Whether the find-friends sheet is showing. */
    var sheetOpen by mutableStateOf(false)

    /** Whether to show the card on Now: matching is off, a code can be texted, and the card isn't hidden. */
    val offersCard: Boolean get() = state?.canOffer == true && !cardHidden

    /** The requests waiting on the person's answer. */
    val incomingRequests: List<FriendRequest> get() = state?.incomingRequests.orEmpty()

    /** Reads the person's matching. Quietly leaves [state] as it was when the audience doesn't have it. */
    suspend fun load() {
        try {
            state = client.contactMatching()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Now and Me show nothing about contacts until it can be read.
        }
    }

    /** A link opened Now for friends: reload, and show the sheet when it asked for one. */
    fun opened(sheet: Boolean) {
        scope.launch { load() }
        if (sheet) sheetOpen = true
    }

    /** Sign-out: drops what was loaded and the saved names on this phone. Nothing changes on the API. */
    fun forget() {
        state = null
        lastBatch = null
        error = null
        sheetOpen = false
        names.removeAll()
    }

    /** "Not now" on the card: hides it for 30 days. */
    fun hideCard() {
        preferences.edit().putLong(hiddenKey, System.currentTimeMillis() + CARD_HIDDEN_MS).apply()
        cardHidden = true
    }

    /** Texts a code to [number] (as typed, in the phone's region's format or with a "+") and saves the match name. */
    suspend fun start(number: String, firstName: String, lastName: String): Boolean {
        val phone = ContactHashing.e164(number, region())
        if (phone == null) {
            error = "Enter your mobile number with its area code."
            return false
        }
        return change { client.startContactPhoneVerification(phone, firstName.trim(), lastName.trim()) }
    }

    /** Proves the number with the code from the text. */
    suspend fun verify(code: String): Boolean = change { client.verifyContactPhone(code.trim()) }

    /** Turns the phone's contacts into scrambled codes on the phone and checks them. Needs READ_CONTACTS. */
    suspend fun checkContacts(): Boolean {
        var batch: ContactHashBatch? = null
        val done = change {
            val contacts = readContacts()
            val region = region()
            val hashed = withContext(Dispatchers.Default) { ContactHashing.batch(contacts, region) }
            batch = hashed
            client.checkContacts(hashed, names).contactMatching
        }
        if (done) lastBatch = batch
        return done
    }

    /** "Add": asks a match to be friends. */
    suspend fun add(match: ContactMatch): Boolean = change { client.sendFriendRequest(match.user.id) }

    /** "Add N friends": asks everyone still showing "Add". */
    suspend fun addAll() {
        for (match in state?.addable.orEmpty()) {
            if (!add(match)) return
        }
    }

    /** "Accept" or "Not now" on a request. */
    suspend fun respond(request: FriendRequest, accept: Boolean): Boolean =
        change { client.respondToFriendRequest(request.id, accept) }

    /** Changes the name friends match the person on. */
    suspend fun setName(firstName: String, lastName: String): Boolean =
        change { client.setContactMatchName(firstName.trim(), lastName.trim()) }

    /** "Turn off and delete": the API deletes the number and every code; the phone forgets the saved names. */
    suspend fun turnOff(): Boolean {
        val done = change { client.disableContactMatching(names) }
        if (done) lastBatch = null
        return done
    }

    private suspend fun change(call: suspend () -> ContactMatching): Boolean {
        working = true
        try {
            state = call()
            error = null
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: ContactMatchingUnavailableException) {
            error = "Finding friends from contacts isn't available here yet."
            return false
        } catch (e: Exception) {
            error = ApiError.of(e).message ?: "Try again in a moment."
            return false
        } finally {
            working = false
        }
    }

    companion object {
        const val CARD_HIDDEN_MS = 30L * 24 * 60 * 60 * 1000

        /** "Jordan Lee Park" as the first and last name the form starts with: "Jordan", "Lee Park". */
        fun names(fullName: String?): Pair<String, String> {
            val parts = fullName.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return (parts.firstOrNull().orEmpty()) to parts.drop(1).joinToString(" ")
        }
    }
}

/** Now's offer to find friends from contacts, with "Not now" (30 days). */
@Composable
fun FindFriendsCard(friends: ContactFriends) {
    Card {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text(ContactMatchingCopy.TITLE, style = HotMessType.subheading, modifier = Modifier.semantics { heading() })
            Text(ContactMatchingCopy.CARD_BODY, style = HotMessType.body, color = tokens.inkMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(ContactMatchingCopy.FIND_FRIENDS, onClick = { friends.sheetOpen = true })
                TextButton(onClick = friends::hideCard) { Text(ContactMatchingCopy.NOT_NOW, style = HotMessType.label) }
            }
        }
    }
}

/** One request waiting on the person's answer: "Accept" or a quiet "Not now". */
@Composable
fun FriendRequestCard(friends: ContactFriends, request: FriendRequest) {
    val scope = rememberCoroutineScope()
    Card {
        Column(Modifier.padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                Avatar(LocalAppGraph.current.audienceKit.url(request.user.avatarUrl), request.user.name)
                Column(Modifier.weight(1f)) {
                    Text("${request.user.name} wants to be friends", style = HotMessType.subheading)
                    SavedAs(friends, request.user.id)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Accept", onClick = { scope.launch { friends.respond(request, accept = true) } }, enabled = !friends.working)
                SecondaryButton(
                    ContactMatchingCopy.NOT_NOW,
                    onClick = { scope.launch { friends.respond(request, accept = false) } },
                    enabled = !friends.working,
                )
            }
            Text(ContactMatchingCopy.NOT_NOW_IS_QUIET, style = HotMessType.caption, color = tokens.inkMuted)
        }
    }
}

@Composable
private fun SavedAs(friends: ContactFriends, userId: String) {
    friends.names.name(userId)?.let { saved ->
        Text(ContactMatchingCopy.savedAs(saved), style = HotMessType.caption, color = tokens.inkMuted)
    }
}

/** The find-friends sheet: the steps to turn matching on, or the matches once it is. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindFriendsSheet(friends: ContactFriends, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var loaded by remember { mutableStateOf(friends.state != null) }
    LaunchedEffect(friends) {
        friends.load()
        loaded = true
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s4)
                .padding(bottom = Space.s4)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            val state = friends.state
            when {
                !loaded -> Box(Modifier.fillMaxWidth().padding(Space.s12), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state == null -> {
                    SheetTitle(ContactMatchingCopy.TITLE)
                    Text("Finding friends from contacts isn't available here yet.", style = HotMessType.body, color = tokens.inkMuted)
                    SecondaryButton("Close", onClick = onDismiss)
                }
                state.enabled -> Matches(friends, state, onDismiss)
                else -> TurnOn(friends, state)
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = HotMessType.heading, modifier = Modifier.semantics { heading() })
}

@Composable
private fun ErrorText(friends: ContactFriends) {
    friends.error?.let { Text(it, style = HotMessType.bodySmall, color = tokens.danger) }
}

/** What is shared, then the number and name, the code, and the contacts check. */
@Composable
private fun TurnOn(friends: ContactFriends, state: ContactMatching) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val suggested = remember { ContactFriends.names(graph.session.user.value?.name) }
    var explained by rememberSaveable { mutableStateOf(false) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var firstName by rememberSaveable { mutableStateOf(state.firstName ?: suggested.first) }
    var lastName by rememberSaveable { mutableStateOf(state.lastName ?: suggested.second) }
    var number by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var denied by rememberSaveable { mutableStateOf(false) }
    val askForContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) scope.launch { friends.checkContacts() }
    }

    when {
        // The number is proven: read the contacts and check them.
        state.phoneVerified -> {
            SheetTitle(if (friends.working) ContactMatchingCopy.CHECKING else "Check your contacts")
            Text(ContactMatchingCopy.ONLY_CODES_LEAVE, style = HotMessType.body, color = tokens.inkMuted)
            Text(
                "Your phone turns each contact's name and number into a scrambled code. We compare the codes and " +
                    "never see the names or numbers.",
                style = HotMessType.body,
                color = tokens.inkMuted,
            )
            if (denied) {
                Text(
                    "Hot Mess can't read your contacts. Allow Contacts for Hot Mess in your phone's Settings, then try again.",
                    style = HotMessType.bodySmall,
                    color = tokens.danger,
                )
            }
            ErrorText(friends)
            if (friends.working) {
                Box(Modifier.fillMaxWidth().padding(Space.s4), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                PrimaryButton("Check my contacts", modifier = Modifier.fillMaxWidth(), onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) scope.launch { friends.checkContacts() } else askForContacts.launch(Manifest.permission.READ_CONTACTS)
                })
            }
        }
        // A code was texted: enter it.
        codeSent -> {
            SheetTitle("Enter the code")
            Text("We texted a six-digit code to your number.", style = HotMessType.body, color = tokens.inkMuted)
            OutlinedTextField(
                value = code,
                onValueChange = { typed -> code = typed.filter(Char::isDigit).take(6) },
                label = { Text("Code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(friends)
            PrimaryButton(
                "Continue",
                modifier = Modifier.fillMaxWidth(),
                enabled = code.length == 6 && !friends.working,
                onClick = { scope.launch { friends.verify(code) } },
            )
            TextButton(onClick = {
                friends.error = null
                code = ""
                codeSent = false
            }) { Text("Use a different number", style = HotMessType.label) }
        }
        // The number, and the name friends have the person saved under.
        explained -> {
            SheetTitle("Your number and name")
            Text(
                "Use the name your friends have you saved under. That's how they find you.",
                style = HotMessType.body,
                color = tokens.inkMuted,
            )
            OutlinedTextField(
                value = firstName,
                onValueChange = { firstName = it.take(60) },
                label = { Text("First name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = lastName,
                onValueChange = { lastName = it.take(60) },
                label = { Text("Last name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.take(24) },
                label = { Text("Mobile number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(ContactMatchingCopy.NUMBER_PRIVATE, style = HotMessType.caption, color = tokens.inkMuted)
            Text(ContactMatchingCopy.RATES_MAY_APPLY, style = HotMessType.caption, color = tokens.inkMuted)
            ErrorText(friends)
            PrimaryButton(
                "Text me a code",
                modifier = Modifier.fillMaxWidth(),
                enabled = firstName.isNotBlank() && lastName.isNotBlank() && number.isNotBlank() && !friends.working,
                onClick = {
                    scope.launch {
                        if (friends.start(number, firstName, lastName)) {
                            code = ""
                            codeSent = true
                        }
                    }
                },
            )
        }
        else -> {
            SheetTitle(ContactMatchingCopy.TITLE)
            Text(ContactMatchingCopy.CARD_BODY, style = HotMessType.body, color = tokens.inkMuted)
            Text(
                "You and a friend see each other only when you both have each other's number saved, and you both " +
                    "turned this on.",
                style = HotMessType.body,
                color = tokens.inkMuted,
            )
            Text(ContactMatchingCopy.ONLY_CODES_LEAVE, style = HotMessType.body, color = tokens.inkMuted)
            PrimaryButton("Continue", modifier = Modifier.fillMaxWidth(), onClick = { explained = true })
        }
    }
}

/** Everyone found, each with where things stand: "Add", "Requested", "Accept" or "Friends". */
@Composable
private fun Matches(friends: ContactFriends, state: ContactMatching, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    if (state.matches.isEmpty()) {
        SheetTitle(ContactMatchingCopy.NO_FRIENDS_YET)
        Text(ContactMatchingCopy.NO_FRIENDS_BODY, style = HotMessType.body, color = tokens.inkMuted)
    } else {
        SheetTitle(ContactMatchingCopy.friendsHere(state.matches.size))
    }
    friends.lastBatch?.let { batch ->
        Text(ContactMatchingCopy.checked(batch), style = HotMessType.caption, color = tokens.inkMuted)
    }
    ErrorText(friends)
    if (state.matches.isNotEmpty()) {
        DetailSection(null) {
            state.matches.forEachIndexed { index, match ->
                if (index > 0) RowDivider()
                MatchRow(friends, match)
            }
        }
    }
    val addable = state.addable.size
    if (addable > 1) {
        PrimaryButton(
            ContactMatchingCopy.addFriends(addable),
            modifier = Modifier.fillMaxWidth(),
            enabled = !friends.working,
            onClick = { scope.launch { friends.addAll() } },
        )
    }
    SecondaryButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MatchRow(friends: ContactFriends, match: ContactMatch) {
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.s4, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(LocalAppGraph.current.audienceKit.url(match.user.avatarUrl), match.user.name, size = 40.dp)
        Column(Modifier.weight(1f)) {
            Text(match.user.name, style = HotMessType.body, color = tokens.ink)
            SavedAs(friends, match.user.id)
        }
        when (match.status) {
            ContactMatchStatus.NONE ->
                SecondaryButton("Add", onClick = { scope.launch { friends.add(match) } }, enabled = !friends.working)
            ContactMatchStatus.REQUESTED -> Text("Requested", style = HotMessType.label, color = tokens.inkMuted)
            ContactMatchStatus.INCOMING -> SecondaryButton(
                "Accept",
                onClick = { match.request?.let { request -> scope.launch { friends.respond(request, accept = true) } } },
                enabled = !friends.working && match.request != null,
            )
            ContactMatchStatus.FRIENDS -> Text("Friends", style = HotMessType.label, color = tokens.inkMuted)
        }
    }
}

/**
 * Me › Contacts: turn matching on, see matches and check again, change the match name, or turn it off and delete.
 * Draws nothing until the API has answered ([ContactFriends.load]), or while a code can't be texted and matching is off.
 */
@Composable
fun ContactsSection(friends: ContactFriends) {
    val scope = rememberCoroutineScope()
    var confirmingOff by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    val state = friends.state ?: return
    if (!state.enabled && !state.canOffer) return

    DetailSection(
        "Contacts",
        footer = { Text(ContactMatchingCopy.NUMBER_PRIVATE, style = HotMessType.bodySmall, color = tokens.inkMuted) },
    ) {
        InfoRow(
            if (state.enabled) "Friends from your contacts" else ContactMatchingCopy.TITLE,
            value = if (state.enabled) ContactMatchingCopy.friendsHere(state.matches.size) else null,
            icon = Icons.Rounded.Contacts,
            onClick = { friends.sheetOpen = true },
            trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        )
        if (state.enabled) {
            RowDivider()
            InfoRow(
                "Name friends find you by",
                value = listOfNotNull(state.firstName, state.lastName).joinToString(" ").ifBlank { null },
                onClick = { editingName = true },
            )
            RowDivider()
            DangerRow("Turn off and delete") { confirmingOff = true }
        }
    }

    if (confirmingOff) {
        AlertDialog(
            onDismissRequest = { confirmingOff = false },
            title = { Text("Turn off finding friends?") },
            text = {
                Text(
                    "We delete your number, your name for matching and every scrambled code. Friends you already " +
                        "made stay.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingOff = false
                    scope.launch { friends.turnOff() }
                }) { Text("Turn off and delete", color = tokens.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmingOff = false }) { Text("Cancel") } },
        )
    }
    if (editingName) {
        var firstName by rememberSaveable { mutableStateOf(state.firstName.orEmpty()) }
        var lastName by rememberSaveable { mutableStateOf(state.lastName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Name friends find you by") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    OutlinedTextField(firstName, { firstName = it.take(60) }, label = { Text("First name") }, singleLine = true)
                    OutlinedTextField(lastName, { lastName = it.take(60) }, label = { Text("Last name") }, singleLine = true)
                    ErrorText(friends)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = firstName.isNotBlank() && lastName.isNotBlank() && !friends.working,
                    onClick = { scope.launch { if (friends.setName(firstName, lastName)) editingName = false } },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }
}
