package social.hotmess.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One of the audience's curated tribes, as the picker lists it (a tag with `tribe` set). */
@Serializable
data class Tribe(val id: String, val name: String) {
    /** How a chip shows it: "drag-brunch" for Drag Brunch. */
    val label: String get() = tagLabel(name)
}

/** A tag or tribe name as the app shows it: lowercase, with a dash for each run of spaces ("Drag Brunch" -> "drag-brunch"). */
fun tagLabel(name: String): String = name.trim().lowercase().split(Regex("\\s+")).joinToString("-")

/** A group of tribes in the picker: "Arts and culture", or "More" for the ones the audience made itself. */
@Serializable
data class TribeCategory(val key: String, val name: String, val tribes: List<Tribe> = emptyList())

/** The picker's whole list, how many anyone may pick ([cap], `max(1, floor(N / 4))`), and the user's picks by id. */
data class TribeChoices(val categories: List<TribeCategory>, val cap: Int, val picked: Set<String>) {
    val all: List<Tribe> get() = categories.flatMap { it.tribes }

    /** The picked tribes by name, for the "Your picks" chips. */
    val pickedTribes: List<Tribe> get() = all.filter { it.id in picked }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    val isAtCap: Boolean get() = picked.size >= cap

    /** A search result: the tribe and its category's name. */
    data class Match(val tribe: Tribe, val category: String)

    /** Tribes whose name contains [search], by name. */
    fun matching(search: String): List<Match> {
        val query = search.trim()
        if (query.isEmpty()) return emptyList()
        return categories.flatMap { category ->
            category.tribes.filter { it.name.contains(query, ignoreCase = true) }.map { Match(it, category.name) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.tribe.name })
    }

    /** [picked] with [tribe] picked or not, held to the [cap]; null when it's at the cap already. */
    fun toggled(tribe: Tribe): Set<String>? = when {
        tribe.id in picked -> picked - tribe.id
        isAtCap -> null
        else -> picked + tribe.id
    }
}

/**
 * A tribe the user and someone else in a venue chat both picked. Only the two of them ever see it: the API
 * resolves `sharedTribes` for the viewer alone, and the app shows it only in venue and locale chat.
 */
@Serializable
data class SharedTribe(
    val id: String,
    val name: String,
    /** "You're both bears", the curated phrase for this tribe. */
    @SerialName("bothPhrase") val phrase: String? = null,
) {
    val bothPhrase: String get() = phrase?.takeIf { it.isNotBlank() } ?: "You both picked $name"
}

/** The BothLine: the first tribe's phrase, plus a count of the rest ("You're both bears +1"); null when nothing's shared. */
val List<SharedTribe>.bothLine: String?
    get() = firstOrNull()?.let { first -> if (size == 1) first.bothPhrase else "${first.bothPhrase} +${size - 1}" }

/** What TalkBack adds after a name: "you share 2 tribes". */
val List<SharedTribe>.accessibilityPhrase: String?
    get() = when (size) {
        0 -> null
        1 -> "you share a tribe: ${first().name}"
        else -> "you share $size tribes"
    }
