package social.hotmess.core

/**
 * How wide the app's window is, in Material's window size classes: under 600dp the phone layout, from 600dp a
 * navigation rail in place of the bottom bar, from 840dp room for a second pane. Decided by the window, never by the
 * device, so a phone in landscape, an unfolded foldable and a tablet in split screen each get what fits.
 */
enum class WindowWidth {
    COMPACT,
    MEDIUM,
    EXPANDED;

    /** Whether the tabs go in a navigation rail down the side instead of the bottom bar. */
    val usesRail: Boolean get() = this != COMPACT

    companion object {
        fun of(widthDp: Float): WindowWidth = when {
            widthDp >= 840f -> EXPANDED
            widthDp >= 600f -> MEDIUM
            else -> COMPACT
        }
    }
}

/** The layout choices made from a width in dp, here so they're tested. */
object AdaptiveLayout {
    /** The widest a feed of cards grows; everything else in a feed stays at the readable content width. */
    const val WIDE_FEED_MAX_DP = 1200f
    /** The docked chat's width beside a venue's page, as on iPad. */
    const val DOCKED_CHAT_DP = 400f
    /** The narrowest a venue's page gets with the chat docked beside it. */
    const val DOCKED_PAGE_MIN_DP = 560f

    /** Cards a feed this wide lays side by side: one up to 880dp, two up to 1200dp, then three. */
    fun feedColumns(widthDp: Float): Int = when {
        widthDp >= 1200f -> 3
        widthDp >= 880f -> 2
        else -> 1
    }

    /** Whether a venue's page this wide has room for its chat docked beside it. */
    fun docksChat(widthDp: Float): Boolean = widthDp >= DOCKED_CHAT_DP + DOCKED_PAGE_MIN_DP
}
