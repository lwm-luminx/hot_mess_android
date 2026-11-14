package social.hotmess.core

import kotlinx.coroutines.CancellationException

/**
 * Changing your RSVP to an event: the screen shows the new choice at once, saves it, and puts the old one back if
 * the save fails. Picking the answer you already gave, or picking before the event has loaded, does nothing.
 */
object RsvpChange {
    /** Shown when a failed save has no message of its own. */
    const val FALLBACK_MESSAGE = "Try again in a moment."

    /**
     * Moves from [current] (null while the event is loading) to [chosen]: [show] puts a choice on screen and [save]
     * sends it. Returns null when it saved or there was nothing to do, else the message to show; a cancelled save
     * stays cancelled, leaving what's on screen to whatever cancelled it.
     */
    suspend fun choose(
        current: Rsvp?,
        chosen: Rsvp,
        show: (Rsvp) -> Unit,
        save: suspend (Rsvp) -> Unit,
    ): String? {
        if (current == null || chosen == current) return null
        show(chosen)
        return try {
            save(chosen)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show(current)
            ApiError.of(e).message ?: FALLBACK_MESSAGE
        }
    }
}
