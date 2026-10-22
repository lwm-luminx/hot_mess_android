package social.hotmess.core

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * A photo of a venue or event: a photographer's, or one a member took with the app's camera there. Either is
 * tagged with whoever was on stage, so it shows on the venue's, the event's and their pages (`IPosts.photos`).
 */
@Serializable
data class EventPhoto(
    val id: String,
    val url: String? = null,
    val altText: String? = null,
    val caption: String? = null,
    /** PROFESSIONAL (a photographer's) or AMATEUR (a member's, from the app's camera). */
    val source: String = "PROFESSIONAL",
    /** The photographer's name, or the member's short name ("Aurora B."). */
    val takenBy: String? = null,
    @Serializable(with = InstantSerializer::class) val takenAt: Instant? = null,
    /** The photographer's page for it, to see it full size or buy it. */
    val viewUrl: String? = null,
    /** The viewer took it, so they can remove it. */
    val viewerCanRemove: Boolean = false,
    /** Who was on stage when it was taken, in billing order. */
    val people: List<Tag> = emptyList(),
    val event: EventLink? = null,
) {
    @Serializable
    data class Tag(val id: String, val name: String = "")

    @Serializable
    data class EventLink(val id: String, val name: String = "")

    val isProfessional: Boolean get() = source != "AMATEUR"

    /** "Photo by Jo Lens" or "Taken by Aurora B.". */
    val credit: String?
        get() = takenBy?.let { if (isProfessional) "Photo by $it" else "Taken by $it" }

    /** "Aurora Borealis & Bianca Bee", for its line and TalkBack. */
    val peopleNames: String?
        get() = people.map { it.name }.takeIf { it.isNotEmpty() }?.let { names ->
            if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " & " + names.last()
        }
}
