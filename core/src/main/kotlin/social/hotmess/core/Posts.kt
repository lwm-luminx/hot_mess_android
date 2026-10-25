package social.hotmess.core

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * A post by a venue or a talent listing: an announcement, a special, a poster or photos that stays on
 * their page, unlike chat. Hot Mess only reads posts and comments on them; venues post from Velvet,
 * talent from Backstage.
 */
@Serializable
data class Post(
    val id: String,
    val body: String = "",
    /** The venue or the person, in full: listings are public pages. */
    val posterName: String = "",
    val posterAvatarUrl: String? = null,
    /** "Venue", or talent's part on the event ("MC", "DJ", "Act", "Duet", "Troupe", "Host"); null on their own page. */
    val roleLabel: String? = null,
    /** "Kiki Riot for House of Lux", on a troupe's post written by a member. */
    val actingPersonName: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val edited: Boolean = false,
    val pinned: Boolean = false,
    val commentsOpen: Boolean = true,
    val commentCount: Int = 0,
    val photos: List<PostPhoto> = emptyList(),
    val event: PostEvent? = null,
    /** The talent listing posting it; null when the venue posted. */
    val person: Ref? = null,
    /** The venue posting it, or a talent post's event venue. */
    val venue: Ref? = null,
    /** Public posts are on the web too (hotmess.social, link previews) while their poster is public; Community ones need sign-in. */
    val visibility: PostVisibility = PostVisibility.COMMUNITY,
) {
    @Serializable
    data class Ref(val id: String)

    /** Posted as a venue, not a person: a rounded-square face and the "Venue" tag. */
    val isVenuePost: Boolean get() = person == null

    /** Its photos that have a URL. */
    val shownPhotos: List<PostPhoto> get() = photos.filter { !it.url.isNullOrBlank() }

    /** The poster's page. */
    val posterRoute: AppRoute?
        get() = person?.let { AppRoute.PersonDetail(it.id) } ?: venue?.let { AppRoute.VenueDetail(it.id) }

    /** Who "Hide posts from" mutes. */
    val poster: PostPoster?
        get() = person?.let { PostPoster(PostPoster.Kind.PERSON, it.id, posterName) }
            ?: venue?.let { PostPoster(PostPoster.Kind.VENUE, it.id, posterName) }

    /**
     * What Share sends with the post's text: the post's own page on hotmess.social. A Public post shows there and in
     * link previews; a Community one asks whoever opens it to sign in. In the app it opens the post either way.
     */
    val shareUrl: String get() = "https://hotmess.social/posts/${id.lowercase()}"

    /**
     * The metadata line: "Pinned · 2h · Edited · on Drag Bingo". [showsPin] is off where pins don't float
     * (the updates feed); [showsEvent] is off on the event's own page.
     */
    fun metadata(now: Instant = Instant.now(), showsPin: Boolean = true, showsEvent: Boolean = true): String =
        buildList {
            if (pinned && showsPin) add("Pinned")
            add(relativeTime(createdAt, now))
            if (edited) add("Edited")
            if (showsEvent && event != null) add("on ${event.name}")
        }.joinToString(" · ")

    companion object {
        /** "now", "5m", "2h", "3d", then the date. */
        fun relativeTime(instant: Instant, now: Instant = Instant.now()): String {
            val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
            return when {
                seconds < 60 -> "now"
                seconds < 3600 -> "${seconds / 60}m"
                seconds < 86_400 -> "${seconds / 3600}h"
                seconds < 7 * 86_400 -> "${seconds / 86_400}d"
                else -> "${Formatting.monthAbbreviation(instant)} ${Formatting.dayOfMonth(instant)}"
            }
        }
    }
}

@Serializable
data class PostPhoto(val url: String? = null, val altText: String? = null)

/** The event a post is on, for its "on {event}" line and chip. */
@Serializable
data class PostEvent(
    val id: String,
    val name: String = "",
    @Serializable(with = InstantSerializer::class) val startAt: Instant? = null,
)

/** A comment on a post. The API names commenters as rooms do ("Aurora B."); the app swaps in a friend's full name. */
@Serializable
data class PostComment(
    val id: String,
    val authorId: String,
    val authorName: String = "",
    val authorAvatarUrl: String? = null,
    val body: String = "",
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val viewerCanRemove: Boolean = false,
)

/** A post with its comments, oldest first, for the post screen. */
data class PostDetail(val post: Post, val comments: List<PostComment>)

/** A venue or a person whose posts someone can hide (`mutePostAuthor`). */
data class PostPoster(val kind: Kind, val id: String, val name: String) {
    enum class Kind { VENUE, PERSON }
}

/** Whose posts a list shows: the API's `PostsFrom`. */
enum class PostsFilter(val title: String) {
    ALL("All"),
    VENUES("Venues"),
    TALENT("Talent"),
    /** Posts on events the viewer RSVP'd going to. */
    GOING("Going"),
}

/** Whose posts a section or screen lists. */
sealed interface PostsSubject {
    val id: String

    /** The venue's own posts, on it and its events, pinned first. */
    data class Venue(override val id: String) : PostsSubject

    /** Posts on the event by its venue and the people on it. */
    data class Event(override val id: String) : PostsSubject

    /** The listing's own posts, pinned first. */
    data class Person(override val id: String) : PostsSubject

    /** The locale's updates from the last 14 days, newest first. */
    data class Locale(override val id: String) : PostsSubject

    /** Pins float above the rest everywhere but the locale feed. */
    val floatsPins: Boolean get() = this !is Locale

    /** Its kind as a navigation argument; [postsSubject] reads it back. */
    val kind: String
        get() = when (this) {
            is Venue -> "venue"
            is Event -> "event"
            is Person -> "person"
            is Locale -> "locale"
        }
}

/** The subject a navigation argument names (see [PostsSubject.kind]). */
fun postsSubject(kind: String, id: String): PostsSubject =
    when (kind) {
        "event" -> PostsSubject.Event(id)
        "person" -> PostsSubject.Person(id)
        "locale" -> PostsSubject.Locale(id)
        else -> PostsSubject.Venue(id)
    }

/** Posts from the last [hours], for Now's "Latest from tonight". */
fun List<Post>.recent(hours: Long, now: Instant = Instant.now()): List<Post> =
    filter { !it.createdAt.isBefore(now.minus(Duration.ofHours(hours))) }

/** Who can see a post: the API's `PostVisibility`. */
@Serializable
enum class PostVisibility {
    /** People signed in to Hot Mess. The default. */
    COMMUNITY,

    /** The web too: hotmess.social, link previews and search, while the poster's own page is Public. */
    PUBLIC,
}
