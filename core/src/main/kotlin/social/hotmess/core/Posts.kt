package social.hotmess.core

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * A post by a venue or a talent listing: an announcement, a special, a poster or photos that stays on
 * their page, unlike chat. Everyone reads posts and comments on them; whoever manages the venue or the talent
 * listing also writes and manages them here, as they do in Velvet and Backstage.
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
    /** Whether the event's venue hid this talent post from the event. It still shows on the person's page. */
    val hiddenFromEvent: Boolean = false,
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

    /** Who wrote it, for editing: the venue, or the talent listing. */
    val author: PostAuthor?
        get() = person?.let { PostAuthor(PostAuthor.Kind.PERSON, it.id, posterName) }
            ?: venue?.let { PostAuthor(PostAuthor.Kind.VENUE, it.id, posterName) }

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
enum class PostVisibility(val title: String, val summary: String) {
    /** People signed in to Hot Mess. The default. */
    COMMUNITY("Community", "People signed in to Hot Mess. A shared link asks them to sign in."),

    /** The web too: hotmess.social, link previews and search, while the poster's own page is Public. */
    PUBLIC("Public", "On hotmess.social too, in link previews and search. Comments stay in the app."),
}

/** Who a manager posts as: a venue they manage, or a talent listing they manage or act for (a troupe included). */
data class PostAuthor(val kind: Kind, val id: String, val name: String) {
    enum class Kind { VENUE, PERSON }

    val isVenue: Boolean get() = kind == Kind.VENUE
}

/** Where a new post goes: the author's own page, or an event (at the venue, or one the person is on). */
sealed interface PostTarget {
    data object Page : PostTarget

    data class Event(val id: String, val name: String) : PostTarget
}

/** What a manager does to a post from its menu. */
enum class PostManageAction { EDIT, TOGGLE_PIN, TOGGLE_COMMENTS, TOGGLE_VISIBILITY, TOGGLE_HIDDEN_FROM_EVENT, DELETE }

/** The venues and talent the signed-in user manages here, from the staff view (`me { listings }`); empty for everyone else. */
data class ManagedListings(val venues: List<PostAuthor> = emptyList(), val people: List<PostAuthor> = emptyList()) {
    val isEmpty: Boolean get() = venues.isEmpty() && people.isEmpty()

    fun managesVenue(id: String?): Boolean = id != null && venues.any { RecordId.same(it.id, id) }

    fun managesPerson(id: String?): Boolean = id != null && people.any { RecordId.same(it.id, id) }

    /** Whether they may edit, pin, close comments on or delete it: they manage whoever posted it. */
    fun canManage(post: Post): Boolean = if (post.person != null) managesPerson(post.person.id) else managesVenue(post.venue?.id)

    /** Whether they may hide a talent post from its event: they manage the event's venue. */
    fun canHideFromEvent(post: Post): Boolean = post.person != null && post.event != null && managesVenue(post.venue?.id)

    /**
     * Who they can post as on a page: the venue's or the person's own, or an event's (its venue, and whoever on it
     * they manage).
     */
    fun authors(subject: PostsSubject, eventVenueId: String? = null, eventPeople: List<String> = emptyList()): List<PostAuthor> =
        when (subject) {
            is PostsSubject.Venue -> venues.filter { RecordId.same(it.id, subject.id) }
            is PostsSubject.Person -> people.filter { RecordId.same(it.id, subject.id) }
            is PostsSubject.Event ->
                venues.filter { eventVenueId != null && RecordId.same(it.id, eventVenueId) } +
                    people.filter { person -> eventPeople.any { RecordId.same(it, person.id) } }
            is PostsSubject.Locale -> emptyList()
        }

    companion object {
        val NONE = ManagedListings()

        /** The most photos a post takes. */
        const val MAX_PHOTOS = 4
    }
}

/** A photo for a new post: a JPEG and what it shows. */
class PostPhotoUpload(val jpeg: ByteArray, val altText: String = "")

/** A manager's post written, edited or deleted, for the lists showing it. */
sealed interface PostChange {
    data class Updated(val post: Post) : PostChange

    data class Deleted(val id: String) : PostChange
}

/**
 * This list with [change] in it: an edited post in its place, a new one first, a deleted one gone. Where pins float
 * ([floatsPins]), pinned posts go back to the top.
 */
fun List<Post>.applying(change: PostChange, floatsPins: Boolean = true): List<Post> {
    val changed = when (change) {
        is PostChange.Deleted -> filterNot { RecordId.same(it.id, change.id) }
        is PostChange.Updated ->
            if (any { RecordId.same(it.id, change.post.id) }) map { if (RecordId.same(it.id, change.post.id)) change.post else it }
            else listOf(change.post) + this
    }
    return if (floatsPins) changed.sortedByDescending { it.pinned } else changed
}
