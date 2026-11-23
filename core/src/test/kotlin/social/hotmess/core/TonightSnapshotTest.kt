package social.hotmess.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightSnapshotTest {
    private val base = Instant.parse("2026-11-23T04:00:00Z")

    private fun ping(
        id: String,
        name: String = "Sam Rivera",
        at: Long = 0,
        mine: Boolean = false,
        joined: Boolean = false,
        via: Friend? = null,
        joins: List<String> = emptyList(),
    ) = Ping(
        id = id, user = Friend("u-$name", name), createdAt = base.plusSeconds(at), expiresAt = base.plusSeconds(3600),
        isMine = mine, joined = joined, via = via,
        joins = joins.mapIndexed { i, n -> PingJoin("$id-$i", Friend("u-$n", n)) },
    )

    @Test
    fun keepsYourPingAndFriendsPingsJoinedFirstNeverOnesSeenThroughACircle() {
        val pings = TonightSnapshot.pings(
            myPing = ping("mine", mine = true),
            friendPings = listOf(ping("old", at = 0), ping("new", at = 60), ping("in", at = 10, joined = true), ping("through", via = Friend("u-a", "Alex Kim"))),
            at = base,
        )
        assertEquals(listOf("mine", "in", "new", "old"), pings.map { it.id })
        assertEquals("You", pings.first().sender)
        assertEquals("Sam", pings.last().sender)
        assertEquals("Your Ping · Anywhere", pings.first().title)
        assertEquals("https://hotmess.social/pings/old", pings.last().url)
        assertTrue(TonightSnapshot.pings(null, listOf(ping("over")), base.plusSeconds(7200)).isEmpty(), "an ended Ping goes")
    }

    @Test
    fun whosInReadsAsFirstNamesTwoThenACount() {
        val entry = TonightSnapshot.entry(ping("a", joins = listOf("Alex Kim", "Jo P.", "Kai Lee", "Max R.")))
        assertEquals(listOf("Alex", "Jo", "Kai", "Max"), entry.people)
        assertEquals("Alex, Jo +2", entry.peopleLine)
        assertNull(TonightSnapshot.entry(ping("b")).peopleLine)
        assertTrue(entry.canJoin)
        assertEquals(false, TonightSnapshot.entry(ping("c", joined = true)).canJoin)
    }

    @Test
    fun endedPingsAndEventsDropOffAndTheNextChangeIsTheNextEnd() {
        val snapshot = TonightSnapshot(
            updatedAt = base,
            events = listOf(
                TonightSnapshot.Event("e1", "Bingo", startsAt = base, endsAt = base.plusSeconds(600), url = "u1"),
                TonightSnapshot.Event("e2", "Disco", startsAt = base.plusSeconds(1200), url = "u2"),
            ),
            pings = listOf(TonightSnapshot.entry(ping("p"))),
        )
        assertEquals(base.plusSeconds(600), snapshot.nextChange(base))
        assertEquals(listOf("e2"), snapshot.current(base.plusSeconds(900)).events.map { it.id })
        assertTrue(snapshot.current(base.plusSeconds(4000)).pings.isEmpty())
        assertTrue(snapshot.current(base.plusSeconds(1200 + 5 * 3600)).events.isEmpty())
    }

    @Test
    fun buildsFromNowAndSurvivesARoundTrip() {
        val disco = "b0f8b66a-e636-495d-9475-0f5317ea08e0"
        val atBingo = Event("ev-1", "Drag Bingo", startAt = base.minusSeconds(600), endAt = base.plusSeconds(3600))
        val later = Event(disco, "Disco", startAt = base.plusSeconds(7200))
        val tomorrow = Event("ev-3", "Brunch", startAt = base.plusSeconds(20 * 3600))
        val now = Now(
            event = atBingo, events = listOf(atBingo, later, tomorrow), locale = NowLocale("l1", "Seattle"),
            myPing = ping("mine", mine = true), friendPings = listOf(ping("f", joined = true)),
        )
        val snapshot = TonightSnapshot.from(now, base)
        assertEquals(listOf("ev-1", disco), snapshot.events.map { it.id }, "the event you're at first, nothing past tonight")
        assertEquals("https://hotmess.social/events/$disco", snapshot.events[1].url)
        assertEquals("Tonight in Seattle", snapshot.header)
        assertEquals(listOf("mine", "f"), snapshot.pings.map { it.id })
        assertEquals(snapshot, TonightSnapshot.decode(snapshot.encode()))
        assertNull(TonightSnapshot.decode("not json"))

        val joined = snapshot.replacing(snapshot.pings[1].copy(people = listOf("Sam")))
        assertEquals(listOf("Sam"), joined.pings[1].people)
        assertEquals(AppRoute.EventDetail(disco), DeepLink.route(snapshot.events[1].url))
        assertTrue(TonightSnapshot(updatedAt = base).isEmpty)
    }
}
