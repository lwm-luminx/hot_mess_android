package social.hotmess.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Google sign-in's step that asks to read the account's other email addresses waits here for Google's consent screen
 * to answer. It lives outside the activity on purpose: Android can recreate MainActivity while the consent screen is
 * up (rotating the phone, a dark mode or font change, low memory), and the answer then reaches the new activity. When
 * the wait lived on the old activity, the new one had nothing to complete, so sign-in spun for ever. A consent screen
 * that never answers gives up after [TIMEOUT], and sign-in carries on without the extra emails, which are only a
 * convenience. (The same fix as the staff apps' "hung on login".)
 */
internal object GoogleConsent {
    val TIMEOUT: Duration = 3.minutes
    private var pending: CompletableDeferred<String?>? = null

    /** Starts waiting for an answer; an earlier wait that never got one is ended with none. */
    @Synchronized
    fun begin(): CompletableDeferred<String?> {
        pending?.complete(null)
        return CompletableDeferred<String?>().also { pending = it }
    }

    /** The consent screen answered, from whichever activity instance received it: [token] is null when declined. */
    @Synchronized
    fun finish(token: String?) {
        pending?.complete(token)
        pending = null
    }

    /** The access token the consent screen answered with, or null when it was declined or never answered. */
    suspend fun await(wait: CompletableDeferred<String?>, timeout: Duration = TIMEOUT): String? =
        withTimeoutOrNull(timeout) { wait.await() } ?: run {
            synchronized(this) { if (pending === wait) pending = null }
            wait.complete(null)
            null
        }
}
