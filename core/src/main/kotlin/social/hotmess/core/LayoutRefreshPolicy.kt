package social.hotmess.core

import com.audiencekit.Coordinates

/**
 * Decides when a screen's card layout is worth asking the server for again, so a new position, a screen coming
 * back, or a slow server can't turn into a stream of `Layout` queries.
 *
 * A layout is due when it was never asked for, once the server's `refreshAfter` (never under
 * [minRefreshAfterMs]) has passed since the last good one, at once for another locale or beacon, or when the
 * device moved at least [minDistanceMeters] and the last ask is [minSpacingMs] old. After a failure nothing is due
 * until a backoff passes, doubling from [firstBackoffMs] up to [maxBackoffMs], however often the screen asks.
 * Only a person's own refresh (`force`) skips the wait. Mirrors the iOS app's LayoutRefreshPolicy.
 */
class LayoutRefreshPolicy(
    private val minDistanceMeters: Double = 150.0,
    private val minSpacingMs: Long = 30_000,
    private val minRefreshAfterMs: Long = 15_000,
    private val firstBackoffMs: Long = 15_000,
    private val maxBackoffMs: Long = 600_000,
) {
    /** What a layout was asked for. */
    data class Request(val position: Coordinates?, val localeId: String?)

    private var lastAsked: Request? = null
    private var lastAskedAtMs = 0L

    /** When the server's order is next due (after a success) or may be tried again (after a failure). */
    private var dueAtMs: Long? = null

    var failures = 0
        private set

    val isBackingOff: Boolean get() = failures > 0

    fun isDue(request: Request, nowMs: Long, force: Boolean = false): Boolean {
        if (force) return true
        val last = lastAsked ?: return true
        dueAtMs?.let { if (nowMs >= it) return true }
        // A failure waits out its backoff wherever the device goes.
        if (isBackingOff) return false
        if (changedPlace(last, request)) return true
        if (nowMs - lastAskedAtMs < minSpacingMs) return false
        return moved(last, request)
    }

    fun asked(request: Request, nowMs: Long) {
        lastAsked = request
        lastAskedAtMs = nowMs
    }

    /** The server answered; ask again once its `refreshAfter` has passed. */
    fun succeeded(refreshAfterSeconds: Int, nowMs: Long) {
        failures = 0
        dueAtMs = nowMs + maxOf(refreshAfterSeconds * 1_000L, minRefreshAfterMs)
    }

    /** The ask failed or timed out; wait longer each time in a row. */
    fun failed(nowMs: Long) {
        failures += 1
        dueAtMs = nowMs + backoffMs(failures)
    }

    /** The wait after [failures] failures in a row. */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0
        var wait = firstBackoffMs
        repeat(failures - 1) {
            wait *= 2
            if (wait >= maxBackoffMs) return maxBackoffMs
        }
        return minOf(wait, maxBackoffMs)
    }

    /** Another locale, another beacon, or a position appearing or going away. */
    private fun changedPlace(old: Request, new: Request): Boolean {
        if (old.localeId != new.localeId) return true
        val from = old.position
        val to = new.position
        if (from == null || to == null) return (from == null) != (to == null)
        return from.beaconMajor != to.beaconMajor
    }

    private fun moved(old: Request, new: Request): Boolean {
        val from = old.position ?: return false
        val to = new.position ?: return false
        return from.metersTo(to) >= minDistanceMeters
    }
}
