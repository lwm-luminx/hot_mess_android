package social.hotmess.core

import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleListingTest {
    private val now = Instant.parse("2026-11-02T20:00:00Z")
    private val slc = "11111111-1111-1111-1111-111111111111"
    private val pdx = "22222222-2222-2222-2222-222222222222"

    private val people: List<Person> = HotMessApi.json.decodeFromString(
        ListSerializer(Person.serializer()),
        """
        [
          {"id":"p-1","name":"Aurora","venues":{"home":{"name":"Club Try-Angles","locale":{"id":"$slc"},
             "friends":{"list":{"nodes":[{"id":"f-1","name":"Sam Okafor"}]}}}},"locales":[],
           "events":{"involvements":{"nodes":[]}}},
          {"id":"p-2","name":"Bea","locales":[{"id":"${pdx.uppercase()}"}],
           "events":{"involvements":{"nodes":[
             {"startAt":"2026-11-20T03:00:00Z","locale":{"id":"$slc"},"venue":{"name":"Too far"}},
             {"startAt":"2026-11-04T03:00:00Z","locale":{"id":"$pdx"},"venue":{"name":"Elsewhere"}},
             {"startAt":"2026-11-03T04:00:00Z","venue":{"name":"Sun Trapp","venue":{"name":"Sun Trapp","locale":{"id":"$slc"},
                "friends":{"list":{"nodes":[{"id":"f-1","name":"Sam Okafor"},{"id":"f-2","name":"Lee"}]}}}}}
           ]}}},
          {"id":"p-3","name":"Cass","venues":{"home":{"locale":{"id":"$slc"}}},
           "events":{"involvements":{"nodes":[{"startAt":"2026-11-02T15:00:00Z","locale":{"id":"$slc"},"venue":{"name":"Earlier today"}}]}}},
          {"id":"p-4","name":"Dee"}
        ]
        """.trimIndent(),
    )

    @Test
    fun localSplitsPlayingThisWeekFromBasedHere() {
        val local = PeopleListing(people).local(slc, now)
        // Cass's gig started five hours ago (inside the grace), Bea's is tonight; the one two weeks out doesn't count.
        assertEquals(listOf("p-3", "p-2"), local.playing.map { it.person.id })
        assertEquals("Sun Trapp", local.playing.last().gig.venueName)
        assertEquals(listOf("p-1"), local.based.map { it.id })
    }

    @Test
    fun featuredLocalesMatchWhateverTheCase() {
        val local = PeopleListing(people).local(pdx, now)
        assertEquals(listOf("p-2"), local.playing.map { it.person.id })
        assertTrue(local.based.isEmpty())
    }

    @Test
    fun friendsOutAreEachListedOnce() {
        assertEquals(listOf("f-1", "f-2"), people[1].friendsOut.map { it.id })
        assertEquals(listOf("f-1"), people[0].friendsOut.map { it.id })
        assertTrue(people[3].friendsOut.isEmpty() && people[3].gigs.isEmpty())
    }

    @Test
    fun nobodyLocalIsEmpty() {
        assertTrue(PeopleListing(people).local("33333333-3333-3333-3333-333333333333", now).isEmpty)
    }
}
