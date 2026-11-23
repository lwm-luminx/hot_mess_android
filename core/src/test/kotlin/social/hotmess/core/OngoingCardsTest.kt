package social.hotmess.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OngoingCardsTest {
    private val now = Instant.parse("2026-11-23T04:00:00Z")
    private val later = Instant.parse("2026-11-23T12:00:00Z")
    private val sam = Friend("u-sam", "Sam Rivera")
    private val alex = Friend("u-alex", "Alex Kim")

    private fun ping(
        id: String,
        user: Friend = sam,
        mine: Boolean = false,
        joined: Boolean = false,
        createdAt: Instant = now.minusSeconds(60),
        expiresAt: Instant = later,
        via: Friend? = null,
        joins: List<PingJoin> = emptyList(),
        place: String? = null,
    ) = Ping(
        id = id, user = user, createdAt = createdAt, expiresAt = expiresAt, isMine = mine, joined = joined, via = via,
        joins = joins, targets = place?.let { listOf(PingTarget("t-$id", venue = Venue(id = "v-$id", name = it))) }.orEmpty(),
    )

    @Test
    fun showsYourOwnPingThenOneYoureInThenTheNewestFriendsPing() {
        val older = ping("older", createdAt = now.minusSeconds(600))
        val newer = ping("newer", user = alex)
        val throughSomeone = ping("via", createdAt = now, via = alex)
        val joined = ping("joined", joined = true, createdAt = now.minusSeconds(900))
        val mine = ping("mine", mine = true)

        assertEquals("mine", OngoingCards.shownPing(mine, listOf(older, newer, joined), now)?.id)
        assertEquals("joined", OngoingCards.shownPing(null, listOf(older, newer, joined), now)?.id)
        assertEquals("newer", OngoingCards.shownPing(null, listOf(older, newer, throughSomeone), now)?.id, "never one seen via a circle")
        assertNull(OngoingCards.shownPing(ping("over", mine = true, expiresAt = now), emptyList(), now), "an ended Ping goes")
    }

    @Test
    fun aFriendsPingOffersImInThenChat() {
        val card = OngoingCards.ping(ping("p1", place = "Purr", joins = listOf(PingJoin("j", alex))))
        assertEquals("Sam's Ping", card.title)
        assertEquals("1 person in · Purr", card.status)
        assertEquals(listOf(OngoingAction.JOIN_PING), card.actions)
        assertEquals("https://hotmess.social/pings/p1/chat", card.chatUrl)

        val joined = OngoingCards.ping(ping("p1", joined = true))
        assertEquals(listOf(OngoingAction.CHAT), joined.actions)
        assertEquals("0 people in", joined.status)
        assertEquals("Your Ping", OngoingCards.ping(ping("p2", mine = true)).title)
    }

    @Test
    fun aQuickieNeverNamesThePartner() {
        val match = PairUpMatch(
            id = "m1", topic = PairUpTopic("dogs", "Dogs or cats?"),
            partner = PairUpPartner("u-alex", "Alex K."),
        )
        val proposed = OngoingCards.quickie(PairUpState(PairUpStatus.PROPOSED, match = match), "v1", "Purr")!!
        assertEquals("Quickie at Purr", proposed.title)
        assertEquals("Dogs or cats?", proposed.topic)
        assertEquals("m1", proposed.matchId)
        assertEquals(listOf(OngoingAction.ACCEPT_QUICKIE, OngoingAction.NEW_TOPIC), proposed.actions)
        assertEquals(false, listOf(proposed.title, proposed.status, proposed.topic).any { it?.contains("Alex") == true })

        val accepted = PairUpState(PairUpStatus.PROPOSED, match = match.copy(accepted = true))
        assertEquals(listOf(OngoingAction.NEW_TOPIC), OngoingCards.quickieActions(accepted))
        assertEquals(listOf(OngoingAction.CHAT), OngoingCards.quickie(PairUpState(PairUpStatus.REVEALED, match = match), "v1", null)!!.actions)
        assertEquals("Quickie", OngoingCards.quickie(PairUpState(PairUpStatus.SEARCHING), "v1", "")!!.title)
        assertNull(OngoingCards.quickie(PairUpState(PairUpStatus.SEARCHING), "v1", null)!!.matchId)
        assertNull(OngoingCards.quickie(PairUpState(PairUpStatus.MET, match = match), "v1", "Purr"), "over once they met")
        assertNull(OngoingCards.quickie(null, "v1", "Purr"))
        val venueId = "b0f8b66a-e636-495d-9475-0f5317ea08e0"
        val routed = OngoingCards.quickie(PairUpState(PairUpStatus.SEARCHING), venueId, null)!!
        assertEquals(AppRoute.QuickieChat(venueId), DeepLink.route(routed.chatUrl), "Chat opens the Quickie's chat")
        assertEquals(AppRoute.VenueDetail(venueId), DeepLink.route(routed.url))
    }
}
