package social.hotmess.core

import com.audiencekit.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VenueGeofencesTest {
    private val here = Coordinates(47.6145, -122.3210)

    /** A venue [north] metres north of [here] (about 111,195 m to a degree of latitude on [metersTo]'s sphere). */
    private fun venue(id: String, north: Double, radius: Double = 50.0) =
        VenueFence(id, here.latitude + north / 111_195.0, here.longitude, radius)

    @Test
    fun watchesTheNearestVenuesAndStopsTheRecenterCircleShortOfTheNextOne() {
        val venues = listOf(venue("far", 2_000.0), venue("near", 300.0), venue("middle", 900.0))
        val plan = GeofencePlan.plan(here, venues, regionLimit = 3)

        assertEquals(listOf("near", "middle"), plan.venues.map { it.id })
        // The next venue's edge: 2,000 m to its point less its 50 m radius.
        assertEquals(1_950.0, plan.recenterRadius, 2.0)
        assertEquals(here.latitude, plan.recenterLatitude)
    }

    @Test
    fun theRecenterCircleStaysWithinItsBounds() {
        val close = GeofencePlan.plan(here, listOf(venue("a", 10.0), venue("b", 60.0)), regionLimit = 2)
        assertEquals(GeofencePlan.MINIMUM_RECENTER_RADIUS, close.recenterRadius)

        val everyVenueWatched = GeofencePlan.plan(here, listOf(venue("a", 300.0)))
        assertEquals(GeofencePlan.MAXIMUM_RECENTER_RADIUS, everyVenueWatched.recenterRadius)

        val none = GeofencePlan.plan(here, emptyList())
        assertTrue(none.venues.isEmpty())
        assertEquals(GeofencePlan.MAXIMUM_RECENTER_RADIUS, none.recenterRadius)
    }

    @Test
    fun aVenueListedTwiceIsWatchedOnce() {
        val plan = GeofencePlan.plan(here, listOf(venue("a", 300.0), venue("a", 300.0)))
        assertEquals(1, plan.venues.size)
    }

    @Test
    fun plansAgainHalfWayToTheEdgeOrWhenTheEnvelopesAreOld() {
        val plan = GeofencePlan.plan(here, listOf(venue("a", 300.0), venue("b", 1_000.0)), regionLimit = 2)
        val nearby = Coordinates(here.latitude + 100 / 111_195.0, here.longitude)
        val halfWay = Coordinates(here.latitude + 500 / 111_195.0, here.longitude)

        assertFalse(plan.isStale(nearby, fencesFresh = true))
        assertTrue(plan.isStale(halfWay, fencesFresh = true))
        assertTrue(plan.isStale(nearby, fencesFresh = false))
    }

    @Test
    fun requestIdsNameTheVenue() {
        val fence = venue("0d6d3f3e-8a59-4d0b-9b8e-7b8c0f6c5a10", 0.0)
        assertEquals("0d6d3f3e-8a59-4d0b-9b8e-7b8c0f6c5a10", GeofencePlan.venueId(GeofencePlan.requestId(fence)))
        assertNull(GeofencePlan.venueId(GeofencePlan.RECENTER_ID))
        assertNull(GeofencePlan.venueId("venue:"))
    }

    @Test
    fun plansAndEnvelopesSurviveBeingSaved() {
        val venues = listOf(venue("a", 300.0), venue("b", 900.0))
        val plan = GeofencePlan.plan(here, venues)
        assertEquals(plan, GeofencePlan.decode(GeofencePlan.encode(plan)))
        assertEquals(venues, GeofencePlan.decodeFences(GeofencePlan.encodeFences(venues)))
        assertNull(GeofencePlan.decode("not json"))
        assertTrue(GeofencePlan.decodeFences(null).isEmpty())
    }
}
