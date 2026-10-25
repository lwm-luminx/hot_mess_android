package social.hotmess.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyAlertsTest {
    private val alertJson = """
        {"id":"6f1c3a5e-1d2b-4c1e-9a77-0b1b2c3d4e5f","state":"OPEN","place":"DANCE_FLOOR","concerns":["BOTHERING"],
         "summary":"Dance floor · Someone's bothering them","seen":true,"createdAt":"2026-10-23T03:15:00Z",
         "venue":{"id":"8b4f1b60-9a5d-4d0e-9b3f-2c6b3e5d8a11","name":"Purr"},
         "messages":[{"id":"m1","body":"Where are you?","fromStaff":true,"senderName":"Jo","createdAt":"2026-10-23T03:16:00Z"}],
         "outcome":"SORTED"}
    """.trimIndent()

    private fun alert() = HotMessApi.json.decodeFromString(SafetyAlert.serializer(), alertJson)

    @Test
    fun decodesTheAlertTheGuestSees() {
        val alert = alert()
        assertEquals(SafetyAlertState.OPEN, alert.state)
        assertEquals(SafetyPlace.DANCE_FLOOR, alert.place)
        assertEquals(listOf(SafetyConcern.BOTHERING), alert.concerns)
        assertTrue(alert.isActive)
        assertTrue(alert.messages.single().fromStaff)
    }

    @Test
    fun offersOtherWaysToGetHelpAfterTwoMinutes() {
        val alert = alert()
        val sent = Instant.parse("2026-10-23T03:15:00Z")
        assertFalse(alert.isUnanswered(sent.plusSeconds(90)))
        assertTrue(alert.isUnanswered(sent.plusSeconds(121)))
        assertFalse(alert.copy(state = SafetyAlertState.ACKNOWLEDGED).isUnanswered(sent.plusSeconds(600)))
    }

    @Test
    fun opensAGuestsPushToTheAlert() {
        assertEquals(
            AppRoute.SafetyAlertHelp("6f1c3a5e-1d2b-4c1e-9a77-0b1b2c3d4e5f"),
            DeepLink.route("https://hotmess.social/safety-alerts/6f1c3a5e-1d2b-4c1e-9a77-0b1b2c3d4e5f"),
        )
    }

    @Test
    fun setsARidesPickupAtTheVenue() {
        val ride = SupportRide(
            id = "uber",
            name = "Uber",
            url = "https://m.uber.com/ul/",
            pickupUrl = "https://m.uber.com/ul/?action=setPickup&pickup%5Blatitude%5D={latitude}" +
                "&pickup%5Blongitude%5D={longitude}&pickup%5Bnickname%5D={name}",
        )
        val venue = HelpVenue("v1", "The Wildrose & Co", latitude = 47.6137, longitude = -122.3197)

        assertEquals(
            "https://m.uber.com/ul/?action=setPickup&pickup%5Blatitude%5D=47.613700" +
                "&pickup%5Blongitude%5D=-122.319700&pickup%5Bnickname%5D=The%20Wildrose%20%26%20Co",
            ride.link(venue.ridePickup),
        )
        assertEquals("https://m.uber.com/ul/", ride.link(null))
    }

    @Test
    fun writesSupportNumbersOut() {
        val trevor = SupportLine("trevor", "The Trevor Project", phone = "18664887386", textNumber = "678678", textBody = "START")
        assertEquals("Call 1-866-488-7386 · Text START to 678678", trevor.numbers)
        assertEquals("Call 988 · Text 988", SupportLine("988", "988", phone = "988", textNumber = "988").numbers)
    }

    @Test
    fun choosesOnePlaceAndAnyConcerns() {
        val draft = SafetyAlertDraft().choose(SafetyPlace.BAR).toggle(SafetyConcern.UNWELL).toggle(SafetyConcern.OTHER)
        assertEquals(SafetyPlace.BAR, draft.place)
        assertEquals(setOf(SafetyConcern.UNWELL, SafetyConcern.OTHER), draft.concerns)
        assertEquals(null, draft.choose(SafetyPlace.BAR).place)
        assertEquals(setOf(SafetyConcern.OTHER), draft.toggle(SafetyConcern.UNWELL).concerns)
    }
}
