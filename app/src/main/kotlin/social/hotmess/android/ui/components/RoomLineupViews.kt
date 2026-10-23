package social.hotmess.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.hotmess.android.ui.theme.HotMessType
import social.hotmess.android.ui.theme.Radius
import social.hotmess.android.ui.theme.Sizes
import social.hotmess.android.ui.theme.Space
import social.hotmess.android.ui.theme.tokens
import social.hotmess.core.LineupFace
import social.hotmess.core.LineupPresence
import social.hotmess.core.RecordId
import social.hotmess.core.RoomLineup
import social.hotmess.core.RoomPerson

/*
 * The night's lineup in a chat room (design system NowHosts.md): the venue's face and one face per lineup
 * entry in HereNowStrip, and the "In this room" sheet. They borrow the lineup's words and badges (OnNow.kt).
 */

/** The place a room is in, as its first face: the venue, with its name and photo. A locale's room has none. */
data class ChatPlace(val id: String, val name: String, val photoUrl: String? = null)

/** A lineup face's presence as the dot draws it: offline draws nothing, as for anyone not in the room. */
internal fun LineupPresence?.dot(): Presence? = when (this) {
    LineupPresence.ONLINE -> Presence.ONLINE
    LineupPresence.PUSH -> Presence.PUSH
    LineupPresence.OFFLINE, null -> null
}

/** How a lineup entry reads aloud, as LineupAccessibility has it: "Aurora Borealis, Host, 9 PM to close, on now, in chat now". */
private fun LineupFace.description(): String = listOfNotNull(
    entry.name,
    entry.role?.label,
    entry.hours()?.replace(" – ", " to "),
    badge?.label?.lowercase(),
    presence?.words?.lowercase(),
).joinToString(", ")

/** Done acts fade to half, as the lineup strip draws them. */
private const val DoneAlpha = 0.5f

/** Width of a face in the strip, with room for a short name under it. */
private val StripFaceWidth: Dp = 72.dp

/** The strip's lineup avatars: 40, as the lineup strip draws them. */
private val StripAvatarSize: Dp = 40.dp

/**
 * A lineup entry's avatar: [size], or a duet's first two people at three-quarters of it, overlapping by 12 under
 * one name (the lineup's Avatars). The presence dot sits on the last face.
 */
@Composable
internal fun LineupAvatar(face: LineupFace, size: Dp, ring: androidx.compose.ui.graphics.Color) {
    val people = face.entry.people
    if (people.size < 2) {
        val person = people.firstOrNull()
        Avatar(person?.avatarUrl, person?.name ?: face.entry.name, size = size, presence = face.presence.dot(), presenceRing = ring)
        return
    }
    val small = size * 0.8f
    Box(Modifier.width(small * 2 - 12.dp).height(size)) {
        Avatar(people[0].avatarUrl, people[0].name, size = small, modifier = Modifier.align(Alignment.TopStart))
        Avatar(
            people[1].avatarUrl,
            people[1].name,
            size = small,
            modifier = Modifier.align(Alignment.BottomEnd),
            presence = face.presence.dot(),
            presenceRing = ring,
        )
    }
}

/** A role word with its small icon, in `ink-muted`, as the lineup labels people ("Host", "DJ"). */
@Composable
private fun RoleWord(icon: ImageVector?, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tokens.inkMuted, modifier = Modifier.size(12.dp))
        Text(label, style = HotMessType.caption, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A face's short name: 12/14 semibold. */
@Composable
private fun ShortName(name: String) {
    Text(
        name,
        style = HotMessType.caption.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
        color = tokens.ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The venue's face, leading the strip: a rounded-square photo, its name, and "Venue". */
@Composable
internal fun PlaceFace(place: ChatPlace, onOpen: () -> Unit) {
    Column(
        Modifier
            .width(StripFaceWidth)
            .clearAndSetSemantics {
                contentDescription = "${place.name}, venue"
                role = Role.Button
                onClick { onOpen(); true }
            }
            .clickable(role = Role.Button, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Avatar(place.photoUrl, place.name, size = StripAvatarSize, shape = RoundedCornerShape(8.dp))
        ShortName(place.name)
        RoleWord(Icons.Rounded.Storefront, "Venue")
    }
}

/**
 * One lineup entry in the strip: its avatar, short name, role word, then its badge. A done act is at 50% with
 * "Done". Tapping it opens their page.
 */
@Composable
internal fun LineupStripFace(face: LineupFace, onOpen: () -> Unit) {
    val description = face.description()
    Column(
        Modifier
            .width(StripFaceWidth)
            .alpha(if (face.isDone) DoneAlpha else 1f)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { onOpen(); true }
            }
            .clickable(role = Role.Button, onClick = onOpen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LineupAvatar(face, StripAvatarSize, tokens.surfaceRaised)
        ShortName(face.entry.name)
        face.entry.role?.let { RoleWord(it.icon, it.label) }
        face.badge?.let { LineupStatusBadge(it) }
    }
}

/** The `control-fill` chip folding the rest of the lineup: "+5 on the lineup". */
@Composable
internal fun LineupMoreChip(folded: Int, onOpen: () -> Unit) {
    Text(
        RoomLineup.moreLabel(folded),
        style = HotMessType.label,
        color = tokens.ink,
        maxLines = 1,
        modifier = Modifier
            .background(tokens.controlFill, Radius.pill)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Space.s3, vertical = Space.s2),
    )
}

/**
 * Everyone in a room (design system NowHosts.md, "In this room"): [title] "In this room" over [subtitle]
 * ("Drag Bingo · The Wildrose") and a Done button; "Tonight's lineup", the [place]'s row then each of [lineup]
 * with its role, set time for a hard start, presence in words when the room says, and its badge; then
 * "Here now · N" and "Here recently" from [people]. Lineup rows open the listing ([onOpenLineup]); a guest
 * other than the viewer opens [onGuest], for reporting or blocking them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InThisRoomSheet(
    subtitle: String,
    place: ChatPlace?,
    lineup: List<LineupFace>,
    people: List<RoomPerson>,
    viewerId: String?,
    onOpenPlace: () -> Unit,
    onOpenLineup: (LineupFace) -> Unit,
    onGuest: (RoomPerson) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val (here, recently) = people.partition { !it.isRecent }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = tokens.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.s4)
                .navigationBarsPadding()
                .padding(bottom = Space.s4),
            verticalArrangement = Arrangement.spacedBy(Space.s4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                Column(Modifier.weight(1f)) {
                    Text("In this room", style = HotMessType.title, color = tokens.ink, modifier = Modifier.semantics { heading() })
                    Text(subtitle, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SecondaryButton("Done", onClick = onDismiss)
            }
            if (place != null || lineup.isNotEmpty()) {
                DetailSection("Tonight's lineup") {
                    place?.let { PlaceRow(it, onOpenPlace) }
                    lineup.forEachIndexed { index, face ->
                        if (index > 0 || place != null) RowDivider(DetailInset)
                        LineupRow(face) { onOpenLineup(face) }
                    }
                }
            }
            if (here.isNotEmpty()) {
                DetailSection("Here now · ${here.size}") {
                    CardRows(here) { person -> GuestRow(person, viewerId, onGuest) }
                }
            }
            if (recently.isNotEmpty()) {
                DetailSection("Here recently") {
                    CardRows(recently) { person -> GuestRow(person, viewerId, onGuest) }
                }
            }
        }
    }
}

/** The venue's row: its photo, its name, and "Venue · always in its room". */
@Composable
private fun PlaceRow(place: ChatPlace, onOpen: () -> Unit) {
    RowButton(onOpen, Modifier.clearAndSetSemantics { contentDescription = "${place.name}, venue" }) {
        Avatar(place.photoUrl, place.name, size = 44.dp, shape = RoundedCornerShape(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(place.name, style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Venue · always in its room", style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
        }
    }
}

/**
 * A lineup entry as the lineup's ActRow: avatar 44, its name, its role and set time for a hard start
 * ("Host · 9:00 PM – close"), its presence in words when known, and its badge at the right. Done acts are at 50%.
 */
@Composable
private fun LineupRow(face: LineupFace, onOpen: () -> Unit) {
    val description = face.description()
    val detail = listOfNotNull(face.entry.role?.label, face.entry.hours()).joinToString(" · ")
    RowButton(onOpen, Modifier.alpha(if (face.isDone) DoneAlpha else 1f).clearAndSetSemantics { contentDescription = description }) {
        LineupAvatar(face, 44.dp, tokens.surfaceRaised)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(face.entry.name, style = HotMessType.body.copy(fontWeight = FontWeight.SemiBold), color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            face.presence?.let { Text(it.words, style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1) }
        }
        face.badge?.let { LineupStatusBadge(it) }
    }
}

/** A guest: their face and name as the room shows them; the viewer is "You", and anyone else can be reported or blocked. */
@Composable
private fun GuestRow(person: RoomPerson, viewerId: String?, onGuest: (RoomPerson) -> Unit) {
    val viewer = RecordId.same(person.userId, viewerId)
    val name = if (viewer) "You" else person.name ?: "Someone"
    val presence = when {
        viewer || !person.isRecent -> Presence.ONLINE
        person.reachable -> Presence.PUSH
        else -> Presence.OFFLINE
    }
    RowButton(if (viewer) null else ({ onGuest(person) })) {
        Avatar(person.avatarUrl, name, size = Sizes.avatarSm, presence = presence, presenceRing = tokens.surfaceRaised, showOffline = true)
        Text(name, style = HotMessType.body, color = tokens.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (person.friend && !viewer) Text("Friend", style = HotMessType.bodySmall, color = tokens.inkMuted, maxLines = 1)
    }
}
