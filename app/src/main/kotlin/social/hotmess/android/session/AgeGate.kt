package social.hotmess.android.session

import android.content.Context

/**
 * Hot Mess is for adults: the terms say 18+ and so does the store rating. Before anyone signs in they confirm they're
 * 18 or older, and the confirmation is kept on this device so it's asked once (hot_mess_ios AgeGate, whose fallback
 * when iOS can't share a declared age range is this same confirmation).
 */
object AgeGate {
    const val MINIMUM_AGE = 18

    private const val PREFERENCES = "hotmess.age"
    private const val CONFIRMED_KEY = "confirmedAdult"

    /** Whether this device already has an 18+ confirmation. */
    fun isConfirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(CONFIRMED_KEY, false)

    /** Keeps the person's 18+ confirmation on this device. */
    fun confirm(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(CONFIRMED_KEY, true).apply()
    }
}
