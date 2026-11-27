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
        assertEquals(listOf("ev-1", disco, "ev-3"), snapshot.events.map { it.id }, "the event you're at first, then the next week's")
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

    @Test
    fun anEventsLocationIsItsVenueAndStreet() {
        fun event(place: String?, address: String?) =
            TonightSnapshot.Event("e", "Bingo", place = place, startsAt = base, url = "u", address = address)
        assertEquals("Purr · 1518 11th Ave", event("Purr", "1518 11th Ave, Seattle, WA").location)
        assertEquals("Purr", event("Purr", null).location)
        assertEquals("Purr", event("Purr", " ").location)
        assertEquals("1518 11th Ave", event(null, "1518 11th Ave, Seattle").location)
        assertNull(event(null, null).location)
    }

    @Test
    fun upcomingKeepsTheNextWeekSoonestFirstAtMostTheLimit() {
        val soon = Event("soon", "Soon", startAt = base.plusSeconds(2 * 3600))
        val later = Event("later", "Later", startAt = base.plusSeconds(30 * 3600))
        val nextMonth = Event("month", "Month", startAt = base.plusSeconds(30L * 24 * 3600))
        val over = Event("over", "Over", startAt = base.minusSeconds(7200), endAt = base.minusSeconds(60))
        assertEquals(listOf("soon", "later"), TonightSnapshot.upcoming(listOf(later, nextMonth, over, soon), at = base).map { it.id })
        val many = (1..TonightSnapshot.LIMIT + 3).map { Event("e$it", "E$it", startAt = base.plusSeconds(it * 3600L)) }
        assertEquals(TonightSnapshot.LIMIT, TonightSnapshot.upcoming(many, at = base).size)
    }

    @Test
    fun goingAndPingedComeFromTheRsvpAndYourPing() {
        val bingo = Event("bingo", "Bingo", startAt = base.plusSeconds(3600)).withRsvp(Rsvp.ATTENDING)
        val disco = Event("disco", "Disco", startAt = base.plusSeconds(7200))
        val mine = ping("mine", mine = true).copy(targets = listOf(PingTarget("t", event = disco)))
        val snapshot = TonightSnapshot.from(Now(events = listOf(bingo, disco), myPing = mine), base)
        assertEquals(listOf(true, false), snapshot.events.map { it.going })
        assertEquals(listOf(false, true), snapshot.events.map { it.pinged })
        assertEquals(listOf("disco", "bingo"), TonightSnapshot.pingEventIds("bingo", mine))
        assertEquals(listOf("disco"), TonightSnapshot.pingEventIds("disco", mine))
        assertEquals(listOf("bingo"), TonightSnapshot.pingEventIds("bingo", null))
        val going = snapshot.updatingEvent("disco") { it.copy(going = true) }
        assertEquals(listOf(true, true), going.events.map { it.going })
    }

    @Test
    fun onlyEventsTonightOfferPing() {
        val tonight = TonightSnapshot.Event("a", "A", startsAt = base.plusSeconds(3 * 3600), url = "u")
        val tomorrow = TonightSnapshot.Event("b", "B", startsAt = base.plusSeconds(30 * 3600), url = "u")
        assertTrue(tonight.isPingable(base))
        assertEquals(false, tomorrow.isPingable(base))
    }

    @Test
    fun theCardFeaturesTonightElseTheNextOneNeverOneDaysAway() {
        val slot = TonightSnapshot.ROTATION.seconds
        val start = Instant.ofEpochSecond(Math.floorDiv(base.epochSecond, slot) * slot)
        fun at(id: String, hours: Long) = TonightSnapshot.Event(id, id, startsAt = start.plusSeconds(hours * 3600), url = "u")
        val nothingTonight = TonightSnapshot(updatedAt = start, events = listOf(at("fri", 27), at("sat", 51), at("sun", 75)))
        assertEquals(setOf("fri"), (0..5).map { nothingTonight.featuredEvent(start.plusSeconds(it * slot))?.id }.toSet())
        assertEquals(1, nothingTonight.featuredEvents(start).size)
        val twoTonight = TonightSnapshot(updatedAt = start, events = listOf(at("bingo", 2), at("karaoke", 3), at("fri", 27)))
        assertEquals(listOf("bingo", "karaoke"), twoTonight.featuredEvents(start).map { it.id })
        assertEquals(setOf("bingo", "karaoke"), (0..3).map { twoTonight.featuredEvent(start.plusSeconds(it * slot))?.id }.toSet())
    }

    @Test
    fun previousAndNextStepThroughEveryEventAndHold() {
        val events = listOf("a", "b", "c").map { TonightSnapshot.Event(it, it, startsAt = base.plusSeconds(30 * 3600), url = "u") }
        val snapshot = TonightSnapshot(updatedAt = base, events = events)
        assertEquals("b", snapshot.event(1, events[0], base)?.id)
        assertEquals("c", snapshot.event(-1, events[0], base)?.id)
        assertEquals("a", snapshot.event(1, events[2], base)?.id)
        val browsing = TonightBrowse("c", base.plusSeconds(600))
        assertEquals("c", snapshot.featuredEvent(base, browsing)?.id)
        assertEquals(snapshot.featuredEvent(base.plusSeconds(601))?.id, snapshot.featuredEvent(base.plusSeconds(601), browsing)?.id)
        assertEquals(browsing, TonightBrowse.decode(browsing.encode()))
    }

    @Test
    fun atAVenueTheWidgetListsTheRestOfTheCitysEventsToo() {
        val sunday = Event("sun", "Sunday Karaoke", startAt = base.plusSeconds(75 * 3600))
        val therapy = Event("milk", "Therapy Thursdays", startAt = base.plusSeconds(2 * 3600))
        val snapshot = TonightSnapshot.from(Now(events = listOf(sunday), cityEvents = listOf(therapy, sunday)), base)
        assertEquals(listOf("milk", "sun"), snapshot.events.map { it.id })
    }

    @Test
    fun theFeaturedEventRotatesAndWrapsAround() {
        val slot = TonightSnapshot.ROTATION.seconds
        val start = Instant.ofEpochSecond(Math.floorDiv(base.epochSecond, slot) * slot)
        val snapshot = TonightSnapshot(
            updatedAt = start,
            events = listOf("a", "b", "c").map { TonightSnapshot.Event(it, it, startsAt = start.plusSeconds(3600), url = "u") },
        )
        val ids = (0..3).map { snapshot.featuredEvent(start.plusSeconds(it * slot))?.id }
        assertEquals(setOf("a", "b", "c"), ids.take(3).toSet())
        assertEquals(ids[0], ids[3])
        assertEquals(ids[0], snapshot.featuredEvent(start.plusSeconds(slot - 1))?.id, "it stays put within a slot")
        assertNull(TonightSnapshot(updatedAt = base).featuredEvent(base))
    }

    @Test
    fun aSnapshotSavedBeforeLocationAndButtonsStillReads() {
        val old = """{"updatedAt":"2026-11-20T00:00:00Z","events":[{"id":"e","name":"Bingo","place":"Purr",""" +
            """"startsAt":"2026-11-20T03:00:00Z","url":"u"}]}"""
        val snapshot = TonightSnapshot.decode(old)
        assertEquals("Purr", snapshot?.events?.first()?.location)
        assertEquals(false, snapshot?.events?.first()?.going)
    }

    @Test
    fun timeLabelsSayTodayTomorrowOrTheDay() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val now = Instant.parse("2026-11-24T20:00:00Z") // Tue noon in Seattle
        fun label(at: String) = TonightSnapshot.Event("e", "E", startsAt = Instant.parse(at), url = "u")
            .timeLabel(now, zone, java.util.Locale.US)
        assertEquals("On now", label("2026-11-24T19:00:00Z"))
        assertTrue(label("2026-11-25T05:00:00Z").startsWith("9:00"))
        assertTrue(label("2026-11-26T05:00:00Z").startsWith("Tomorrow 9:00"))
        assertTrue(label("2026-11-28T05:00:00Z").startsWith("Fri 9:00"))
        assertEquals("Sat, Dec 5", label("2026-12-06T05:00:00Z"))
    }
}
