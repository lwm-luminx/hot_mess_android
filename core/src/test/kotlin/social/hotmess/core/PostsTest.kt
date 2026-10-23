package social.hotmess.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private object PostFixtures {
    const val WILDROSE = "6f1c2c1e-4d2a-4f6b-9a37-0c1d2e3f4a5b"
    const val KIKI = "7a8b9c0d-1e2f-4a3b-8c4d-5e6f7a8b9c0d"

    val VENUE_POST = """
        {"id":"11111111-2222-4333-8444-555555555555","body":"Two-for-one wells until 10.","posterName":"The Wildrose",
         "posterAvatarUrl":"https://cdn.example.com/w.jpg","roleLabel":"Venue","actingPersonName":null,
         "createdAt":"2026-10-21T18:00:00Z","edited":true,"pinned":true,"commentsOpen":true,"commentCount":3,
         "photos":[{"url":"https://cdn.example.com/1.jpg","altText":"The bar"},{"url":null,"altText":null}],
         "event":{"id":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","name":"Drag Bingo","startAt":"2026-10-24T04:00:00Z"},
         "person":null,"venue":{"id":"$WILDROSE"}}
    """.trimIndent()

    val TALENT_POST = """
        {"id":"22222222-3333-4444-8555-666666666666","body":"See you tonight!","posterName":"House of Lux",
         "roleLabel":"Troupe","actingPersonName":"Kiki Riot for House of Lux","createdAt":"2026-10-21T19:00:00Z",
         "commentsOpen":false,"commentCount":0,"photos":[],"event":null,"person":{"id":"$KIKI"},"venue":null}
    """.trimIndent()
}

class PostsTest {
    private val json = HotMessApi.json

    @Test
    fun readsAVenuePostAndDropsPhotosWithoutAUrl() {
        val post = json.decodeFromString(Post.serializer(), PostFixtures.VENUE_POST)

        assertTrue(post.isVenuePost)
        assertEquals(AppRoute.VenueDetail(PostFixtures.WILDROSE), post.posterRoute)
        assertEquals(listOf(PostPhoto("https://cdn.example.com/1.jpg", "The bar")), post.shownPhotos)
        assertEquals(PostPoster(PostPoster.Kind.VENUE, PostFixtures.WILDROSE, "The Wildrose"), post.poster)
        assertEquals("Drag Bingo", post.event?.name)
    }

    @Test
    fun readsATroupePostWrittenByAMember() {
        val post = json.decodeFromString(Post.serializer(), PostFixtures.TALENT_POST)

        assertFalse(post.isVenuePost)
        assertEquals(AppRoute.PersonDetail(PostFixtures.KIKI), post.posterRoute)
        assertEquals("Kiki Riot for House of Lux", post.actingPersonName)
        assertFalse(post.commentsOpen)
        assertNull(post.event)
    }

    @Test
    fun metadataSaysPinnedWhenEditedAndTheEvent() {
        val post = json.decodeFromString(Post.serializer(), PostFixtures.VENUE_POST)
        val now = post.createdAt.plusSeconds(2 * 3600)

        assertEquals("Pinned · 2h · Edited · on Drag Bingo", post.metadata(now))
        assertEquals("2h · Edited", post.metadata(now, showsPin = false, showsEvent = false))
    }

    @Test
    fun relativeTimesStepFromNowToMinutesHoursAndDays() {
        val at = Instant.parse("2026-10-21T18:00:00Z")
        assertEquals("now", Post.relativeTime(at, at.plusSeconds(30)))
        assertEquals("5m", Post.relativeTime(at, at.plusSeconds(5 * 60)))
        assertEquals("3d", Post.relativeTime(at, at.plusSeconds(3 * 86_400)))
    }

    @Test
    fun recentKeepsTheLastDayForNow() {
        val post = json.decodeFromString(Post.serializer(), PostFixtures.VENUE_POST)
        assertEquals(listOf(post), listOf(post).recent(24, post.createdAt.plusSeconds(3600)))
        assertTrue(listOf(post).recent(24, post.createdAt.plusSeconds(25 * 3600)).isEmpty())
    }

    @Test
    fun subjectsRoundTripThroughNavigation() {
        listOf(PostsSubject.Venue("a"), PostsSubject.Event("b"), PostsSubject.Person("c"), PostsSubject.Locale("d")).forEach {
            assertEquals(it, postsSubject(it.kind, it.id))
        }
        assertFalse(PostsSubject.Locale("d").floatsPins)
    }

    @Test
    fun opensAPostFromItsLink() {
        val id = "11111111-2222-4333-8444-555555555555"
        assertEquals(AppRoute.PostDetail(id), DeepLink.route("https://hotmess.social/posts/$id"))
        assertEquals(AppTab.NOW, AppRoute.PostDetail(id).tab)
        assertEquals(AppTab.VENUES, AppRoute.Posts(PostsSubject.Venue(id), "The Wildrose").tab)
    }
}
