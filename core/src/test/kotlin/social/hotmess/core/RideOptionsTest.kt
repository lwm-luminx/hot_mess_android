package social.hotmess.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideOptionsTest {
    private val uber = SupportRide("uber", "Uber", "https://m.uber.com/ul/")
    private val lyft = SupportRide("lyft", "Lyft", "https://lyft.com/ride")
    private val tryAngles = RidePickup(40.7608, -111.891, "Club Try-Angles")

    @Test
    fun oneRowNamesTheRegionsApps() {
        assertEquals("Transit, or share where to pick you up.", RideOptions.summary(emptyList()))
        assertEquals("Uber, transit, or a friend.", RideOptions.summary(listOf(uber)))
        assertEquals("Uber, Lyft, transit, or a friend.", RideOptions.summary(listOf(uber, lyft)))
    }

    @Test
    fun transitStartsAtThePickup() {
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&origin=40.760800,-111.891000&travelmode=transit",
            RideOptions.transitUrl(tryAngles),
        )
        assertEquals("https://www.google.com/maps/dir/?api=1&travelmode=transit", RideOptions.transitUrl(null))
    }

    @Test
    fun messagesSayWhereToPickUp() {
        assertEquals(
            "Can you pick me up? I'm here: Club Try-Angles, 251 W 900 S https://maps.google.com/?q=40.760800,-111.891000",
            RideOptions.pickMeUp("Club Try-Angles, 251 W 900 S", tryAngles),
        )
        assertEquals("Can you pick me up?", RideOptions.pickMeUp(null, null))
        assertTrue(RideOptions.sendMeARide(null, tryAngles).contains("I'm here: https://maps.google.com/?q=40.760800,-111.891000."))
        assertTrue(RideOptions.sendMeARide(null, null).startsWith("Can you send me a ride home?"))
        assertEquals("Pickup at Club Try-Angles.", RideOptions.pickupLine(tryAngles))
        assertEquals("Pickup where you are now.", RideOptions.pickupLine(tryAngles.copy(name = null)))
    }
}
