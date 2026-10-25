package social.hotmess.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowLayoutTest {
    @Test
    fun windowWidthFollowsMaterialBreakpoints() {
        assertEquals(WindowWidth.COMPACT, WindowWidth.of(411f))
        assertEquals(WindowWidth.COMPACT, WindowWidth.of(599.9f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.of(600f))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.of(839f))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.of(840f))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.of(1280f))
    }

    @Test
    fun railFromMediumUp() {
        assertFalse(WindowWidth.COMPACT.usesRail)
        assertTrue(WindowWidth.MEDIUM.usesRail)
        assertTrue(WindowWidth.EXPANDED.usesRail)
    }

    @Test
    fun feedColumnsGrowWithWidth() {
        assertEquals(1, AdaptiveLayout.feedColumns(680f))
        assertEquals(1, AdaptiveLayout.feedColumns(879f))
        assertEquals(2, AdaptiveLayout.feedColumns(880f))
        assertEquals(2, AdaptiveLayout.feedColumns(1199f))
        assertEquals(3, AdaptiveLayout.feedColumns(1200f))
    }

    @Test
    fun chatDocksWhenThePageKeepsItsWidth() {
        assertFalse(AdaptiveLayout.docksChat(800f))
        assertTrue(AdaptiveLayout.docksChat(960f))
        assertTrue(AdaptiveLayout.docksChat(1192f))
    }
}
