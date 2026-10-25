package social.hotmess.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TribesTest {
    private val bears = """{"id":"t1","name":"Bear","bothPhrase":"You're both bears"}"""
    private val games = """{"id":"t2","name":"Board games","bothPhrase":"You both play board games"}"""

    private fun frames(event: String): List<ChatProtocol.Frame> =
        ChatProtocol.parse("""{"identifier":"x","message":{"result":{"data":{"venueChat":$event}},"more":true}}""")

    @Test
    fun bothLineIsTheFirstPhrasePlusACount() {
        val tribes = listOf(SharedTribe("t1", "Bear", "You're both bears"), SharedTribe("t2", "Board games", "You both play board games"))
        assertNull(emptyList<SharedTribe>().bothLine)
        assertEquals("You're both bears", tribes.take(1).bothLine)
        assertEquals("You're both bears +1", tribes.bothLine)
        assertEquals("You both picked Poz", SharedTribe("t3", "Poz").bothPhrase)
    }

    @Test
    fun asksForSharedTribesOnlyWhenTold() {
        val room = ChatRoom.venue("v1")
        assertFalse(ChatProtocol.document(room).contains("sharedTribes"))
        val document = ChatProtocol.document(room, sharedTribes = true)
        assertTrue(document.contains("people { userId name avatarUrl friend sharedTribes { id name bothPhrase } }"))
        assertTrue(document.contains("recentUntil sharedTribes { id name bothPhrase } }"))
        assertTrue(ChatProtocol.document(ChatRoom.locale("l1"), sharedTribes = true).startsWith("subscription ChatRoom(\$id: ID!) { localeChat"))
    }

    @Test
    fun readsSharedTribesOffTheRosterArrivalsAndLines() {
        val joined = frames(
            """{"__typename":"ChatJoined","outOfRange":false,"roster":{"people":[
              {"userId":"U1","name":"Jordan P.","friend":false,"sharedTribes":[$bears,$games]},
              {"userId":"u2","name":"Sam K.","friend":false,"sharedTribes":[]},
              {"userId":"u3","name":"Old server","friend":false}],"friends":[],"recent":[]},"history":[]}""",
        )
        val roster = assertIs<ChatProtocol.Frame.Roster>(joined[2])
        assertEquals(listOf("Bear", "Board games"), roster.people[0].sharedTribes?.map { it.name })
        assertEquals(emptyList(), roster.people[1].sharedTribes)
        assertNull(roster.people[2].sharedTribes)

        val arrival = frames("""{"__typename":"ChatPresenceChanged","userId":"u4","presence":"ONLINE","name":"Ari","sharedTribes":[$bears]}""")
        assertEquals("You're both bears", assertIs<ChatProtocol.Frame.PresenceChanged>(arrival.single()).sharedTribes?.bothLine)

        val line = frames(
            """{"__typename":"ChatMessagePosted","senderPresence":null,"message":{"id":"m1","message":"hi","userId":"u1",""" +
                """"sharedTribes":[$bears,{"id":"bad"}]}}""",
        )
        assertEquals(listOf("t1"), assertIs<ChatProtocol.Frame.Message>(line.single()).message.sharedTribes?.map { it.id })
    }

    @Test
    fun choicesHoldPicksToTheCapAndFindTribesByName() {
        val choices = TribeChoices(
            categories = listOf(
                TribeCategory("scene", "Body type and scene", listOf(Tribe("t1", "Bear"))),
                TribeCategory("games", "Games and play", listOf(Tribe("t2", "Board games"))),
            ),
            cap = 1,
            picked = setOf("t2"),
        )
        assertTrue(choices.isAtCap)
        assertNull(choices.toggled(Tribe("t1", "Bear")))
        assertEquals(emptySet(), choices.toggled(Tribe("t2", "Board games")))
        assertEquals(listOf("Board games"), choices.matching("bo").map { it.tribe.name })
        assertEquals(emptyList(), choices.matching("  "))
    }

    @Test
    fun `tags and tribes show lowercase with dashes for spaces`() {
        assertEquals("board-games", Tribe("t2", "Board games").label)
        assertEquals("resident-dj", tagLabel("  Resident   DJ "))
        assertEquals("bear", tagLabel("Bear"))
    }
}
