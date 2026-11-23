package social.hotmess.core

import com.audiencekit.Coordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutRefreshPolicyTest {
    private val nyne = Coordinates(47.6575451, -117.414777)
    private val locale = "locale-1"

    private fun north(metres: Double, beaconMajor: Int? = null) =
        Coordinates(nyne.latitude + metres / 111_320, nyne.longitude, beaconMajor = beaconMajor)

    private fun request(position: Coordinates?, localeId: String? = locale) = LayoutRefreshPolicy.Request(position, localeId)

    private fun s(seconds: Int) = seconds * 1_000L

    @Test
    fun theFirstAskIsAlwaysDue() {
        assertTrue(LayoutRefreshPolicy().isDue(request(nyne), 0))
    }

    @Test
    fun aGoodLayoutWaitsForRefreshAfterHoweverTheFixJitters() {
        val policy = LayoutRefreshPolicy()
        policy.asked(request(nyne), 0)
        policy.succeeded(60, 0)
        for (second in 1 until 60) {
            assertFalse(policy.isDue(request(north((second % 20).toDouble())), s(second)), "at $second s")
        }
        assertTrue(policy.isDue(request(nyne), s(60)))
    }

    @Test
    fun aRefreshAfterOfZeroStillWaitsTheMinimum() {
        val policy = LayoutRefreshPolicy()
        policy.asked(request(nyne), 0)
        policy.succeeded(0, 0)
        assertFalse(policy.isDue(request(nyne), s(1)))
        assertTrue(policy.isDue(request(nyne), s(15)))
    }

    @Test
    fun aRealMoveAsksAgainButNotMoreThanOncePerSpacing() {
        val policy = LayoutRefreshPolicy()
        policy.asked(request(nyne), 0)
        policy.succeeded(600, 0)
        assertFalse(policy.isDue(request(north(500.0)), s(10)))
        assertTrue(policy.isDue(request(north(500.0)), s(30)))
        assertFalse(policy.isDue(request(north(100.0)), s(30)))
    }

    @Test
    fun anotherLocaleOrBeaconAsksAtOnce() {
        val policy = LayoutRefreshPolicy()
        policy.asked(request(nyne), 0)
        policy.succeeded(600, 0)
        assertTrue(policy.isDue(request(nyne, "locale-2"), s(1)))
        assertTrue(policy.isDue(request(north(0.0, beaconMajor = 7)), s(1)))
    }

    @Test
    fun failuresBackOffDoublingToTheCapWhereverTheDeviceGoes() {
        val policy = LayoutRefreshPolicy()
        assertEquals(s(15), policy.backoffMs(1))
        assertEquals(s(30), policy.backoffMs(2))
        assertEquals(s(60), policy.backoffMs(3))
        assertEquals(s(600), policy.backoffMs(20))

        policy.asked(request(nyne), 0)
        policy.failed(0)
        assertFalse(policy.isDue(request(nyne), s(1)))
        assertFalse(policy.isDue(request(north(5_000.0), "locale-2"), s(14)))
        assertTrue(policy.isDue(request(nyne), s(15)))

        policy.asked(request(nyne), s(15))
        policy.failed(s(15))
        assertFalse(policy.isDue(request(nyne), s(44)))
        assertTrue(policy.isDue(request(nyne), s(45)))

        policy.asked(request(nyne), s(45))
        policy.succeeded(60, s(45))
        assertFalse(policy.isBackingOff)
        assertFalse(policy.isDue(request(nyne), s(100)))
    }

    @Test
    fun aPersonsRefreshIsAlwaysDueEvenBackingOff() {
        val policy = LayoutRefreshPolicy()
        policy.asked(request(nyne), 0)
        policy.failed(0)
        assertTrue(policy.isDue(request(nyne), s(1), force = true))
    }
}
