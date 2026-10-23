package social.hotmess.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.SharedTribe
import social.hotmess.core.bothLine

// Design system "Tribes and tags" (api/doc/design-system/TribesAndTags.md): the both glyph, the BothMark on an
// avatar, the BothLine after a name, and the picker's TribeChip and PickCounter. A match shows only between the two
// people who share a tribe, and only in chat; nothing here ever filters.

/** The both glyph, two overlapping circles ("you both"), drawn in [color] to fill a square of [size] at [center]. */
private fun DrawScope.bothGlyph(center: Offset, size: Float, color: Color) {
    val ring = size * 0.62f
    val line = (size * 0.13f).coerceAtLeast(1.dp.toPx())
    val shift = (size - ring) / 2f
    drawCircle(color, radius = ring / 2f - line / 2f, center = center.copy(x = center.x - shift), style = Stroke(line))
    drawCircle(color, radius = ring / 2f - line / 2f, center = center.copy(x = center.x + shift), style = Stroke(line))
}

/** The both glyph on its own, as the BothLine leads with it. */
@Composable
fun BothGlyph(size: Dp = 11.dp, color: Color = tokens.accentInk, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { bothGlyph(center, this.size.minDimension, color) }
}

/** The badge for an avatar of [diameter]: 13 on chat's tiny avatars, 18 on 40, 22 on the sheet's 64. */
fun bothBadgeSize(diameter: Dp): Dp = when {
    diameter < 36.dp -> 13.dp
    diameter < 56.dp -> 18.dp
    else -> 22.dp
}

/**
 * The BothMark, for someone who shares a tribe with the viewer: a 2dp `accent` ring outside a 2dp gap and, with
 * [badge], the both glyph on an `accent` disc at the top right, ringed in [surface]. Drawn outside the avatar's
 * bounds, so it never moves it; the presence dot keeps the bottom right. Nothing when [isOn] is false.
 */
fun Modifier.bothMark(
    isOn: Boolean,
    accent: Color,
    onAccent: Color,
    surface: Color,
    badge: Boolean = true,
): Modifier = if (!isOn) {
    this
} else {
    drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        val radius = size.minDimension / 2f
        drawCircle(accent, radius = radius + 2.dp.toPx() + stroke / 2f, style = Stroke(stroke))
        if (badge) {
            val badgeSize = bothBadgeSize(size.minDimension.toDp()).toPx()
            // At 45° on the avatar's edge: the top right.
            val corner = radius * 0.7071f
            val at = Offset(center.x + corner, center.y - corner)
            drawCircle(surface, radius = badgeSize / 2f + 2.dp.toPx(), center = at)
            drawCircle(accent, radius = badgeSize / 2f, center = at)
            bothGlyph(at, badgeSize * 0.62f, onAccent)
        }
    }
}

/** The BothLine after a sender's name: the glyph and "You're both bears" (or "… +1") in `accent-ink`. */
@Composable
fun BothLine(tribes: List<SharedTribe>, modifier: Modifier = Modifier) {
    val line = tribes.bothLine ?: return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        BothGlyph()
        Text(line, style = HotMessType.caption, color = tokens.accentInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A tribe in the picker: off, on (`accent-soft`, with an × in "Your picks" when [showsRemove]), or [heldBack] at
 * the cap (dimmed; tapping it still runs [onClick], which says why).
 */
@Composable
fun TribeChip(
    name: String,
    isOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    heldBack: Boolean = false,
    showsRemove: Boolean = false,
) {
    Row(
        modifier
            .alpha(if (heldBack) 0.45f else 1f)
            .heightIn(min = 40.dp)
            .background(if (isOn) tokens.accentSoft else tokens.surfaceRaised, Radius.pill)
            .border(1.dp, if (isOn) tokens.accent else tokens.border, Radius.pill)
            .clickable(role = Role.Checkbox, onClickLabel = if (showsRemove) "Remove from your picks" else null, onClick = onClick)
            .semantics { selected = isOn }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = HotMessType.label, color = if (isOn) tokens.accentInk else tokens.ink, maxLines = 1)
        if (showsRemove) Icon(Icons.Rounded.Close, contentDescription = null, tint = tokens.accentInk, modifier = Modifier.size(14.dp))
    }
}

/** "7 of 31 picked" beside a meter filled with `accent`. */
@Composable
fun PickCounter(picked: Int, cap: Int, modifier: Modifier = Modifier) {
    val label = "$picked of $cap picked"
    Row(
        modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = HotMessType.label, color = tokens.ink)
        LinearProgressIndicator(
            progress = { picked.coerceAtMost(cap).toFloat() / cap.coerceAtLeast(1) },
            color = tokens.accent,
            trackColor = tokens.controlFill,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview
@Composable
private fun TribesPreview() = HotMessTheme {
    val tribes = listOf(SharedTribe("1", "Bear", "You're both bears"), SharedTribe("2", "Board games", "You both play board games"))
    Column(Modifier.background(tokens.surface).padding(Space.s4), verticalArrangement = Arrangement.spacedBy(Space.s4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s5)) {
            for (size in listOf(24.dp, 40.dp, 64.dp)) {
                Avatar(
                    null,
                    "Jo K",
                    size = size,
                    modifier = Modifier.bothMark(true, tokens.accent, tokens.onAccent, tokens.surface),
                )
            }
        }
        BothLine(tribes)
        BothLine(tribes.take(1))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            TribeChip("Bear", isOn = true, onClick = {}, showsRemove = true)
            TribeChip("Karaoke regular", isOn = false, onClick = {})
            TribeChip("Board games", isOn = false, onClick = {}, heldBack = true)
        }
        PickCounter(7, 31)
    }
}
