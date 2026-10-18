package social.hotmess.android.session

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Connect Google on the Me screen: what [social.hotmess.android.MainActivity] does with Google's account sheet, for a
 * screen that has no activity of its own.
 */
interface GoogleAccounts {
    /** Whether this build can sign in with Google at all (it has a server client ID). */
    val available: Boolean

    /**
     * Asks Google for an account and connects it to the signed-in account. Returns null once it's connected, or what
     * to tell the person when it isn't (they closed the sheet: an empty string, nothing to show).
     */
    suspend fun connect(): String?
}

val LocalGoogleAccounts = staticCompositionLocalOf<GoogleAccounts?> { null }
