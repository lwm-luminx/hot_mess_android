package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.Appearance
import social.hotmess.core.AppearanceRole

/** Who's on at a venue right now, as Velvet's lineup shows them; tapping one opens their page. Draws nothing when nobody is on. */
@Composable
fun OnNowSection(appearances: List<Appearance>, onOpen: (Appearance) -> Unit) {
    if (appearances.isEmpty()) return
    DetailSection("On now") {
        CardRows(appearances) { appearance -> AppearanceRow(appearance) { onOpen(appearance) } }
    }
}

/** One person on now: avatar, name and role on the left; their set and the On now badge on the right. */
@Composable
fun AppearanceRow(appearance: Appearance, onClick: () -> Unit) {
    val role = appearance.role
    val hours = appearance.hours()
    val description = listOfNotNull(appearance.person.name, role?.label, hours, "on now").joinToString(", ")
    RowButton(onClick, Modifier.clearAndSetSemantics { contentDescription = description }) {
        Avatar(appearance.person.avatarUrl, appearance.person.name, size = 44.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                appearance.person.name,
                style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold),
                color = tokens.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (role != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s1)) {
                    Icon(role.icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(16.dp))
                    Text(role.label, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
                }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (hours != null) {
                Text(hours, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
            }
            OnNowBadge()
        }
    }
}

/** The green "On now" pill with a waveform, as Velvet draws it: `success` on `success-soft`. */
@Composable
fun OnNowBadge() {
    Row(
        Modifier.background(tokens.successSoft, Radius.pill).padding(horizontal = Space.s2, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = tokens.success, modifier = Modifier.size(14.dp))
        Text("On now", style = HotMessType.caption.copy(fontWeight = FontWeight.SemiBold), color = tokens.success, maxLines = 1)
    }
}

/** The icon beside a role, matching Velvet's SF Symbols (headphones, theatre masks, mic, sparkles). */
private val AppearanceRole.icon: ImageVector
    get() = when (this) {
        AppearanceRole.DJ -> Icons.Rounded.Headphones
        AppearanceRole.PERFORMER -> Icons.Rounded.TheaterComedy
        AppearanceRole.MC -> Icons.Rounded.Mic
        AppearanceRole.HOST -> Icons.Rounded.AutoAwesome
    }
