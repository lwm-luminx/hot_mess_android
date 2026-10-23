package social.hotmess.core

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomLineupTest {
    private fun entry(
        id: String,
        role: String? = "PERFORMER",
        state: String = "UPCOMING",
        kind: String = "ACT",
        people: List<String> = listOf(id),
    ) = LineupEntry(
        id = id,
        kindName = kind,
        roleName = role,
        stateName = state,
        person = people.firstOrNull()?.let { Person("p-$it", name = it) },
    ).let { base ->
        // The people connection, as the API sends it, when there's more than one.
        if (people.size < 2) base else HotMessApi.json.decodeFromString(
            LineupEntry.serializer(),
            """{"id":"$id","kind":"$kind","role":"$role","state":"$state",
              "people":{"nodes":[${people.joinToString(",") { """{"id":"p-$it","name":"$it"}""" }}]}}""",
        )
    }

    /** A subscription result carrying one ChatRoomEvent, as GraphQLChannel transmits it. */
    private fun result(event: String) = """{"identifier":"x","message":{"result":{"data":{"venueChat":$event}},"more":true}}"""

    @Test
    fun ordersTheHostFirstThenOnNowUpNextTheRestAndDoneLast() {
        val entries = listOf(
            entry("opener", state = "DONE"),
            entry("dj", role = "DJ", state = "DONE"),
            entry("aurora", state = "ON"),
            entry("bunny"),
            entry("singer", role = "SINGER", state = "UPCOMING"),
            entry("break", role = null, kind = "INTERMISSION", people = emptyList()),
            entry("closer"),
            entry("mc", role = "HOST", state = "ON"),
        )
        val faces = RoomLineup.faces(entries, upNextId = "bunny", presence = null)
        assertEquals(listOf("mc", "aurora", "bunny", "closer", "opener", "dj"), faces.map { it.id })
        assertEquals(
            listOf(LineupBadge.ON_NOW, LineupBadge.ON_NOW, LineupBadge.UP_NEXT, null, LineupBadge.DONE, LineupBadge.DONE),
            faces.map { it.badge },
        )
        assertTrue(faces.last().isDone)
        // Without presence from the room, no face has any.
        assertTrue(faces.all { it.presence == null })
    }

    @Test
    fun aDuetIsOneFaceNamedForBoth() {
        val faces = RoomLineup.faces(listOf(entry("duet", state = "ON", people = listOf("Aurora", "Bunny"))), null, null)
        val duet = faces.single()
        assertEquals(listOf("p-Aurora", "p-Bunny"), duet.entry.people.map { it.id })
        assertEquals("Aurora & Bunny", duet.entry.name)
    }

    @Test
    fun presenceRollsUpPerEntry() {
        val duet = entry("duet", people = listOf("A", "B"))
        assertEquals(LineupPresence.ONLINE, RoomLineup.presenceOf(duet, mapOf("p-a" to LineupPresence.PUSH, "p-b" to LineupPresence.ONLINE)))
        assertEquals(LineupPresence.PUSH, RoomLineup.presenceOf(duet, mapOf("p-a" to LineupPresence.PUSH)))
        // The room lists every lineup person, so anyone it leaves out is offline.
        assertEquals(LineupPresence.OFFLINE, RoomLineup.presenceOf(duet, emptyMap()))
        val faces = RoomLineup.faces(listOf(duet), null, mapOf("p-b" to LineupPresence.ONLINE))
        assertEquals(LineupPresence.ONLINE, faces.single().presence)
        assertEquals("In chat now", LineupPresence.ONLINE.words)
        assertEquals("Gets notifications", LineupPresence.PUSH.words)
        assertEquals("Not in chat yet", LineupPresence.OFFLINE.words)
    }

    @Test
    fun theStripFoldsAfterSixFaces() {
        val faces = RoomLineup.faces((1..9).map { entry("act-$it") }, null, null)
        val (shown, folded) = RoomLineup.strip(faces)
        assertEquals(6, shown.size)
        assertEquals(3, folded)
        assertEquals("+3 on the lineup", RoomLineup.moreLabel(folded))
        assertEquals(0, RoomLineup.strip(faces.take(4)).second)
        assertEquals("Tonight · 23 here", RoomLineup.header(23))
    }

    @Test
    fun theSheetSubtitleNamesTheEventAndItsVenue() {
        assertEquals("Drag Bingo · The Wildrose", RoomLineup.subtitle("Drag Bingo", "The Wildrose"))
        assertEquals("The Wildrose", RoomLineup.subtitle("The Wildrose", "The Wildrose"))
        assertEquals("Neighbours", RoomLineup.subtitle("Neighbours", null))
    }

    @Test
    fun thePeekLineNamesTheVenueTheLineupAndAFriend() {
        assertEquals("The Wildrose, 3 from tonight's lineup and Jordan · 23 here", RoomLineup.peekLine("The Wildrose", 3, "Jordan", 23))
        assertEquals("The Wildrose · 23 here", RoomLineup.peekLine("The Wildrose", 0, "Jordan", 23))
        assertEquals("The Wildrose and 2 from tonight's lineup · 9 here", RoomLineup.peekLine("The Wildrose", 2, null, 9))
        assertEquals("The Wildrose", RoomLineup.peekLine("The Wildrose", 0, null, null))
    }

    @Test
    fun aHardStartShowsItsSet() {
        val start = Instant.parse("2026-10-24T02:00:00Z")
        val hard = LineupEntry("a", startTimingName = "HARD", startsAt = start)
        assertEquals("02:00 – close", hard.hours(ZoneId.of("UTC"), Locale.UK))
        assertNull(hard.copy(startTimingName = "SOFT").hours(ZoneId.of("UTC"), Locale.UK))
    }

    @Test
    fun decodesARunningOrderEntry() {
        val decoded = HotMessApi.json.decodeFromString(
            LineupEntry.serializer(),
            """{"id":"e-1","kind":"ACT","role":"HOST","state":"ON","startTiming":"HARD","startsAt":"2026-10-24T02:00:00Z",
              "endsAt":null,"title":null,"person":{"id":"p-1","name":"Aurora Borealis","avatarUrl":"https://a/1.jpg"},
              "people":{"nodes":[{"id":"p-1","name":"Aurora Borealis","avatarUrl":"https://a/1.jpg"},null]}}""",
        )
        assertEquals(AppearanceRole.HOST, decoded.role)
        assertEquals(LineupState.ON, decoded.state)
        assertTrue(decoded.isHardStart)
        assertFalse(decoded.isSinger)
        assertEquals(listOf("Aurora Borealis"), decoded.people.map { it.name })
        assertEquals(LineupState.UPCOMING, LineupState.of("SOMETHING_NEW"))
    }

    @Test
    fun readsLineupPresenceFromTheRosterWhenItIsThere() {
        val frames = ChatProtocol.parse(
            result(
                """{"__typename":"ChatJoined","outOfRange":false,
                  "roster":{"people":[],"friends":[],"recent":[],
                    "lineup":[{"personId":"P-1","presence":"ONLINE"},{"personId":"p-2","presence":"PUSH"},
                              {"personId":"p-3","presence":"OFFLINE"},{"personId":"p-4","presence":"LATER"},{"presence":"ONLINE"}]},
                  "history":[],"pinnedMessage":null}""",
            ),
        )
        val roster = assertIs<ChatProtocol.Frame.Roster>(frames[2])
        assertEquals(
            mapOf("p-1" to LineupPresence.ONLINE, "p-2" to LineupPresence.PUSH, "p-3" to LineupPresence.OFFLINE),
            roster.lineup,
        )
    }

    @Test
    fun aRosterWithoutLineupHasNoLineupPresence() {
        val frames = ChatProtocol.parse(
            result("""{"__typename":"ChatJoined","outOfRange":false,"roster":{"people":[],"friends":[]},"history":[]}"""),
        )
        assertNull(assertIs<ChatProtocol.Frame.Roster>(frames[2]).lineup)
    }

    @Test
    fun readsALineupChange() {
        val frame = ChatProtocol.parse(
            result("""{"__typename":"ChatLineupChanged","lineup":[{"personId":"p-1","presence":"PUSH"}]}"""),
        ).single()
        assertEquals(ChatProtocol.Frame.LineupChanged(mapOf("p-1" to LineupPresence.PUSH)), frame)
        // Malformed, it says nothing.
        assertTrue(ChatProtocol.parse(result("""{"__typename":"ChatLineupChanged","lineup":"nope"}""")).isEmpty())
    }

    @Test
    fun theRoomDoesNotAskForLineupPresenceUntilTheApiHasIt() {
        // The production API rejects a subscription naming a field or type it doesn't know; ask once it ships.
        assertFalse(ChatProtocol.ROOM_EVENT_FIELDS.contains("ChatLineupChanged"))
        assertFalse(ChatProtocol.ROOM_EVENT_FIELDS.contains("lineup"))
    }

    @Test
    fun asksForTonightsRunningOrderAndUpNext() {
        assertTrue(Documents.VENUE_LINEUP.contains("runningOrder { nodes { ${Documents.LINEUP_ENTRY_FIELDS} } }"))
        assertTrue(Documents.VENUE_LINEUP.contains("upNext { id }"))
    }
}
