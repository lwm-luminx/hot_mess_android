package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import social.hotmess.android.location.LocationAccess
import social.hotmess.android.session.LocalGoogleAccounts
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.Navigator
import social.hotmess.android.ui.ScreenScaffold
import social.hotmess.android.ui.StaffApp
import social.hotmess.android.ui.openStaffApp
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.ChatBubble
import social.hotmess.android.ui.components.DangerRow
import social.hotmess.android.ui.components.DetailInset
import social.hotmess.android.ui.components.DetailRowPadding
import social.hotmess.android.ui.components.DetailSection
import social.hotmess.android.ui.components.Feed
import social.hotmess.android.ui.components.InfoRow
import social.hotmess.android.ui.components.RowDivider
import social.hotmess.android.ui.components.TinyAvatarSize
import social.hotmess.android.ui.openAppSettings
import social.hotmess.android.ui.openUrl
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.ManagedListings
import social.hotmess.core.LegalLinks
import social.hotmess.core.initialsForDisplay

private enum class Confirm { RESET, SIGN_OUT, DELETE }

/**
 * You: who you're signed in as, location access, your chat photo, chat notifications, the people you block, feedback, the terms and
 * privacy policy, about the app, signing out, and deleting your account.
 */
@Composable
fun MeScreen(navigator: Navigator) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val user by graph.session.user.collectAsStateWithLifecycle()
    val locale by graph.location.locale.collectAsStateWithLifecycle()
    val access by graph.location.access.collectAsStateWithLifecycle()
    val safety by graph.safety.state.collectAsStateWithLifecycle()
    // "Your tribes", shown once the API answers with them (hidden signed out, or on an older API).
    val tribes by graph.tribes.choices.collectAsStateWithLifecycle()
    var unblocking by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var reportingProblem by remember { mutableStateOf(false) }
    val configuration = graph.configuration
    val scope = rememberCoroutineScope()
    // Pushes about rooms they just left (me.recentChatPush); null until known, and the row stays hidden
    // when the server can't say (signed out, or an older API).
    var recentChatPush by remember { mutableStateOf<Boolean?>(null) }
    // "Use my initials instead" in chat rooms (me.chatInitials); hidden, like the push setting, until known.
    var chatInitials by remember { mutableStateOf<Boolean?>(null) }
    // Connect Google, offered while the account has no Google sign-in (me.self.signedInWithGoogle is false).
    val googleAccounts = LocalGoogleAccounts.current
    val listings by graph.listings.state.collectAsStateWithLifecycle()
    var connectingGoogle by remember { mutableStateOf(false) }
    var googleProblem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { graph.session.refreshUser() }
    LaunchedEffect(Unit) { graph.safety.refresh() }
    LaunchedEffect(Unit) { if (graph.session.isSignedIn) graph.tribes.load() }
    LaunchedEffect(Unit) {
        if (!graph.session.isSignedIn) return@LaunchedEffect
        recentChatPush = try {
            graph.api.recentChatPush()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
    LaunchedEffect(Unit) {
        if (!graph.session.isSignedIn) return@LaunchedEffect
        chatInitials = try {
            graph.api.chatInitials()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun unblock(userId: String) {
        unblocking = userId
        scope.launch {
            try {
                graph.safety.unblock(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "Couldn't unblock them. Please try again."
            } finally {
                unblocking = null
            }
        }
    }

    fun deleteAccount() {
        deleting = true
        scope.launch {
            try {
                graph.safety.deleteAccount()
                graph.session.resetLocalData()
                graph.session.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = "Couldn't delete your account. Check your connection and try again."
            } finally {
                deleting = false
            }
        }
    }

    fun setRecentChatPush(enabled: Boolean) {
        val before = recentChatPush
        recentChatPush = enabled
        scope.launch {
            recentChatPush = try {
                graph.api.setRecentChatPush(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                before
            }
        }
    }

    fun setChatInitials(enabled: Boolean) {
        val before = chatInitials
        chatInitials = enabled
        scope.launch {
            chatInitials = try {
                graph.api.setChatInitials(enabled)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                before
            }
        }
    }

    ScreenScaffold(title = "Me") {
        Feed {
            item {
                DetailSection(null) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = DetailInset, vertical = Space.s4),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s3),
                    ) {
                        val current = user
                        Avatar(current?.avatarUrl, current?.name ?: "", size = 64.dp)
                        Column(Modifier.weight(1f)) {
                            Text(current?.name ?: "Signed in", style = HotMessType.heading, color = tokens.ink)
                            locale?.name?.let { Text(it, style = HotMessType.bodySmall, color = tokens.inkMuted) }
                        }
                    }
                }
            }
            item {
                DetailSection("Cover") {
                    InfoRow(
                        "Your passes",
                        icon = Icons.Rounded.ConfirmationNumber,
                        onClick = navigator::openPasses,
                        trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    )
                }
            }
            item {
                DetailSection("Location") {
                    InfoRow(
                        "Location access",
                        when (access) {
                            LocationAccess.ALLOWED -> "On"
                            LocationAccess.DENIED -> "Off"
                            LocationAccess.NOT_REQUESTED -> "Not asked yet"
                        },
                        icon = Icons.Rounded.LocationOn,
                    )
                    if (access == LocationAccess.DENIED) {
                        RowDivider()
                        InfoRow("Open settings", onClick = context::openAppSettings, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                    }
                }
            }
            if (googleAccounts?.available == true && user?.signedInWithGoogle == false) {
                item {
                    DetailSection("Sign-in") {
                        InfoRow(
                            if (connectingGoogle) "Connecting Google…" else "Connect Google",
                            icon = Icons.Rounded.Link,
                            onClick = {
                                if (!connectingGoogle) {
                                    connectingGoogle = true
                                    scope.launch {
                                        googleProblem = googleAccounts.connect()?.takeIf { it.isNotEmpty() }
                                        connectingGoogle = false
                                    }
                                }
                            },
                        )
                    }
                }
            }
            // Managers write and manage posts in the staff apps, not here: Velvet for venues, Backstage for talent.
            if (!listings.isEmpty) {
                item {
                    DetailSection("Your pages") {
                        if (listings.venues.isNotEmpty()) {
                            InfoRow(
                                "Open ${StaffApp.VELVET.title}",
                                value = listingNames(listings.venues),
                                onClick = { context.openStaffApp(StaffApp.VELVET) },
                                trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            )
                        }
                        if (listings.venues.isNotEmpty() && listings.people.isNotEmpty()) RowDivider()
                        if (listings.people.isNotEmpty()) {
                            InfoRow(
                                "Open ${StaffApp.BACKSTAGE.title}",
                                value = listingNames(listings.people),
                                onClick = { context.openStaffApp(StaffApp.BACKSTAGE) },
                                trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            )
                        }
                    }
                }
            }
            chatInitials?.let { initials ->
                item {
                    DetailSection("Chat photo") {
                        ChatPhotoSample(user?.name.orEmpty(), if (initials) null else user?.avatarUrl)
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = DetailInset, vertical = Space.s2),
                            verticalArrangement = Arrangement.spacedBy(Space.s2),
                        ) {
                            Text(
                                "In chat, everyone sees your photo this small, on purpose. It's enough for friends to spot you, " +
                                    "and not enough for strangers to judge you.",
                                style = HotMessType.body,
                                color = tokens.ink,
                            )
                            Text(
                                "We keep only a small copy for chat rooms. Nobody in a chat can open a bigger one.",
                                style = HotMessType.bodySmall,
                                color = tokens.inkMuted,
                            )
                        }
                        RowDivider()
                        SwitchRow(
                            "Use my initials instead",
                            "Show \"${user?.name.orEmpty().initialsForDisplay().take(1).ifEmpty { "?" }}\" on a colour, no photo",
                            checked = initials,
                            icon = Icons.Rounded.Face,
                            onCheckedChange = ::setChatInitials,
                        )
                    }
                }
            }
            tribes?.let { choices ->
                item {
                    DetailSection(
                        "Tribes",
                        footer = {
                            Text(
                                "Nobody sees your list. When you and someone in a venue chat share one, just the two of you see it.",
                                style = HotMessType.bodySmall,
                                color = tokens.inkMuted,
                            )
                        },
                    ) {
                        InfoRow(
                            "Your tribes",
                            value = if (choices.picked.isEmpty()) "None yet" else "${choices.picked.size} picked",
                            icon = Icons.Rounded.Groups,
                            onClick = navigator::openTribes,
                            trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        )
                    }
                }
            }
            recentChatPush?.let { enabled ->
                item {
                    DetailSection("Notifications") {
                        SwitchRow(
                            "Rooms you just left",
                            "Get a push when people keep talking in a chat room you left in the last 30 minutes.",
                            checked = enabled,
                            icon = Icons.Rounded.Notifications,
                            onCheckedChange = ::setRecentChatPush,
                        )
                    }
                }
            }
            // "Need support?" is here whether or not the user is out; a venue's Get help only on the venue they're at.
            item {
                DetailSection("Help and safety") {
                    InfoRow("Need support?", icon = Icons.Rounded.Favorite, onClick = { navigator.openSupport(null) }, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                    RowDivider()
                    InfoRow("Getting help at a venue", icon = Icons.Rounded.Shield, onClick = navigator::openGettingHelp, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                    RowDivider()
                    InfoRow("Your safety alerts", icon = Icons.Rounded.NotificationsActive, onClick = navigator::openSafetyAlerts, trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                }
            }
            item {
                // Promoted cards the user turned off: kept on this phone, never sent.
                DetailSection("Cards") {
                    InfoRow(
                        "Cards you see",
                        icon = Icons.Rounded.ViewAgenda,
                        onClick = navigator::openCardsYouSee,
                        trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    )
                }
            }
            safety?.blockedUsers?.takeIf { it.isNotEmpty() }?.let { blocked ->
                item {
                    DetailSection("Blocked") {
                        blocked.forEachIndexed { index, person ->
                            if (index > 0) RowDivider()
                            Row(
                                Modifier.fillMaxWidth().padding(DetailRowPadding),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                            ) {
                                Avatar(person.avatarUrl, person.name, size = 32.dp)
                                Text(person.name.ifBlank { "Someone" }, style = HotMessType.body, color = tokens.ink, modifier = Modifier.weight(1f))
                                TextButton(onClick = { unblock(person.id) }, enabled = unblocking == null) {
                                    Text(if (unblocking == person.id) "Unblocking…" else "Unblock", style = HotMessType.label, color = tokens.accentInk)
                                }
                            }
                        }
                    }
                }
            }
            item {
                DetailSection("Feedback") {
                    InfoRow(
                        "Report a problem",
                        icon = Icons.Rounded.BugReport,
                        onClick = { reportingProblem = true },
                        trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    )
                    RowDivider()
                    InfoRow(
                        "Send feedback",
                        icon = Icons.Rounded.Email,
                        onClick = { context.openUrl("mailto:feedback@hotmess.social?subject=Hot%20Mess%20for%20Android%20${configuration.versionName}") },
                    )
                }
            }
            item {
                DetailSection("Legal") {
                    InfoRow("Terms of use", icon = Icons.Rounded.Description, onClick = { context.openUrl(LegalLinks.TERMS) })
                    RowDivider()
                    InfoRow("Privacy policy", icon = Icons.Rounded.PrivacyTip, onClick = { context.openUrl(LegalLinks.PRIVACY) })
                }
            }
            item {
                DetailSection("About") {
                    InfoRow("Version", "${configuration.versionName} (${configuration.versionCode})")
                    RowDivider()
                    InfoRow("Facebook", configuration.facebookEnvironment)
                    RowDivider()
                    InfoRow("Server", configuration.baseUrl.removePrefix("https://").removePrefix("http://"))
                }
            }
            item {
                DetailSection(null) {
                    DangerRow("Reset all data") { confirm = Confirm.RESET }
                    RowDivider()
                    DangerRow("Sign out") { confirm = Confirm.SIGN_OUT }
                    RowDivider()
                    DangerRow(if (deleting) "Deleting your account…" else "Delete account") {
                        if (!deleting) confirm = Confirm.DELETE
                    }
                }
            }
        }
    }

    if (reportingProblem) ReportProblemDialog(screen = "Me") { reportingProblem = false }

    googleProblem?.let { problem ->
        AlertDialog(
            onDismissRequest = { googleProblem = null },
            title = { Text("Google wasn't connected") },
            text = { Text(problem) },
            confirmButton = { TextButton(onClick = { googleProblem = null }) { Text("OK") } },
        )
    }

    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text("Something went wrong") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text("OK") } },
        )
    }

    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (action) {
                        Confirm.RESET -> "Reset all data?"
                        Confirm.SIGN_OUT -> "Sign out?"
                        Confirm.DELETE -> "Delete your account?"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        Confirm.RESET -> "This clears saved places and cached photos on this phone and signs you out."
                        Confirm.SIGN_OUT -> "You'll need to sign in with Facebook again to use Hot Mess."
                        Confirm.DELETE ->
                            "This deletes your Hot Mess account and everything we hold about you: your profile, messages, " +
                                "pings, RSVPs and passes. It can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (action) {
                        Confirm.DELETE -> deleteAccount()
                        Confirm.RESET -> {
                            graph.session.resetLocalData()
                            graph.session.signOut()
                        }
                        Confirm.SIGN_OUT -> graph.session.signOut()
                    }
                }) {
                    Text(
                        when (action) {
                            Confirm.RESET -> "Reset"
                            Confirm.SIGN_OUT -> "Sign out"
                            Confirm.DELETE -> "Delete"
                        },
                        color = tokens.danger,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/**
 * How you look in chat, at real size: your tiny avatar (or initials), your name as the room shows it to
 * strangers ("Rick M.") and a sample bubble.
 */
@Composable
private fun ChatPhotoSample(name: String, avatarUrl: String?) {
    val parts = name.split(' ').filter { it.isNotEmpty() }
    val shown = when {
        parts.isEmpty() -> "You"
        parts.size == 1 -> parts[0]
        else -> "${parts.first()} ${parts.last().first().uppercaseChar()}."
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DetailInset, vertical = Space.s3)
            .clip(Radius.bubble)
            .background(tokens.surface)
            .padding(horizontal = Space.s3, vertical = 14.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Example of your message in a chat room" },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Avatar(avatarUrl, name.ifBlank { "You" }, size = TinyAvatarSize)
        Column {
            Text(shown, style = HotMessType.caption, color = tokens.inkMuted, modifier = Modifier.padding(start = Space.s1, bottom = 2.dp))
            ChatBubble("anyone know who's on next?", own = false, last = true)
        }
    }
}

/** A setting that's on or off: a title, a muted line on what it does, and a switch; the whole row toggles it. */
@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, icon: ImageVector, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(DetailRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
    ) {
        Icon(icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = HotMessType.body, color = tokens.ink)
            Text(detail, style = HotMessType.bodySmall, color = tokens.inkMuted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = tokens.accent, checkedThumbColor = tokens.onAccent),
        )
    }
}

/** "The Wildrose", or "The Wildrose +2" for someone managing more than one. */
private fun listingNames(listings: List<ManagedListings.Listing>): String {
    val first = listings.firstOrNull()?.name.orEmpty()
    return if (listings.size > 1) "$first +${listings.size - 1}" else first
}
