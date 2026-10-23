package social.hotmess.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import social.hotmess.android.ui.components.Avatar
import social.hotmess.android.ui.components.Presence
import social.hotmess.android.ui.components.PrimaryButton
import social.hotmess.android.ui.components.SecondaryButton
import social.hotmess.android.ui.components.TinyAvatarSize
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.FriendState

/**
 * Someone tapped in a chat room: their user id, the name the room shows them by ("First L.", or a friend's
 * full name), their chat photo, and whether they're the viewer's friend.
 */
data class ChatProfile(val userId: String, val name: String, val avatarUrl: String?, val friend: Boolean)

/**
 * The sheet tapping someone in chat opens: their tiny chat avatar (the presence dot while they're in the
 * room, a friend's ring), their name and "In chat now", a note that photos stay small for everyone, "Ping"
 * and "Add friend" ("Requested" or "Friends" once that's so), then "Mute in this chat" (this device only),
 * "Report" and "Block". There's deliberately no larger photo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatProfileSheet(
    profile: ChatProfile,
    presence: Presence?,
    friendState: FriendState,
    addingFriend: Boolean,
    friendError: String?,
    muted: Boolean,
    onPing: () -> Unit,
    onAddFriend: () -> Unit,
    onToggleMute: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        ChatProfileContent(
            profile, presence, friendState, addingFriend, friendError, muted,
            onPing, onAddFriend, onToggleMute, onReport, onBlock,
        )
    }
}

@Composable
private fun ChatProfileContent(
    profile: ChatProfile,
    presence: Presence?,
    friendState: FriendState,
    addingFriend: Boolean,
    friendError: String?,
    muted: Boolean,
    onPing: () -> Unit,
    onAddFriend: () -> Unit,
    onToggleMute: () -> Unit,
    onReport: () -> Unit,
    onBlock: () -> Unit,
) {
    val online = presence == Presence.ONLINE
    Column(
        Modifier.fillMaxWidth().padding(start = Space.s4, end = Space.s4, bottom = Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Row(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(Space.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ring = tokens.accent
            Avatar(
                profile.avatarUrl,
                profile.name,
                size = TinyAvatarSize,
                // As in the room: a friend's 1.5dp `accent` ring outside a 2dp gap.
                modifier = if (profile.friend) {
                    Modifier.drawBehind {
                        val stroke = 1.5.dp.toPx()
                        drawCircle(ring, radius = size.minDimension / 2f + 2.dp.toPx() + stroke / 2f, style = Stroke(width = stroke))
                    }
                } else {
                    Modifier
                },
                presence = if (online) Presence.ONLINE else null,
                presenceRing = tokens.surface,
            )
            Column {
                Text(
                    profile.name,
                    style = HotMessType.heading,
                    color = tokens.ink,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        heading()
                        if (profile.friend) contentDescription = "${profile.name}, friend"
                    },
                )
                // "· Here since 8:40pm" joins this once the API says when someone came in.
                if (online) Text("In chat now", style = HotMessType.bodySmall, color = tokens.inkMuted)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(tokens.surfaceSunken)
                .padding(horizontal = Space.s3, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.s2),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(18.dp))
            Text(
                "Photos stay small in chat, for everyone. The best way to see who's who is to say hi in person.",
                style = HotMessType.bodySmall,
                color = tokens.ink,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                PrimaryButton("Ping", onPing, Modifier.weight(1f).heightIn(min = 44.dp))
                SecondaryButton(
                    when {
                        friendState == FriendState.FRIENDS -> "Friends"
                        friendState == FriendState.REQUESTED -> "Requested"
                        addingFriend -> "Adding…"
                        else -> "Add friend"
                    },
                    onAddFriend,
                    Modifier.weight(1f).heightIn(min = 44.dp),
                    enabled = friendState == FriendState.NONE && !addingFriend,
                )
            }
            if (friendError != null) Text(friendError, style = HotMessType.bodySmall, color = tokens.danger)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // TextButtons keep a 48dp touch target.
            SheetTextButton(if (muted) "Unmute in this chat" else "Mute in this chat", tokens.accentInk, onToggleMute)
            SheetTextButton("Report", tokens.danger, onReport)
            SheetTextButton("Block", tokens.danger, onBlock)
        }
    }
}

@Composable
private fun SheetTextButton(text: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(text, style = HotMessType.label, color = color, maxLines = 1)
    }
}

@Preview(name = "Chat profile", widthDp = 360)
@Composable
private fun ChatProfilePreview() {
    HotMessTheme(darkTheme = false) {
        Column(Modifier.background(tokens.surface).padding(top = Space.s4)) {
            ChatProfileContent(
                ChatProfile("u-1", "Jo P.", null, friend = false),
                Presence.ONLINE, FriendState.NONE, addingFriend = false, friendError = null, muted = false,
                {}, {}, {}, {}, {},
            )
        }
    }
}

@Preview(name = "Chat profile, friend, dark", widthDp = 360)
@Composable
private fun ChatProfileFriendPreview() {
    HotMessTheme(darkTheme = true) {
        Column(Modifier.background(tokens.surface).padding(top = Space.s4)) {
            ChatProfileContent(
                ChatProfile("u-2", "Aurora Bell", null, friend = true),
                null, FriendState.FRIENDS, addingFriend = false, friendError = null, muted = true,
                {}, {}, {}, {}, {},
            )
        }
    }
}
