package social.hotmess.android.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.hotmess.core.ApiError
import social.hotmess.core.HotMessApi
import social.hotmess.core.TribeChoices

/**
 * The signed-in user's tribes: the audience's curated list, the cap, and their picks. Null until loaded; cleared
 * on sign-out. [isSupported] says whether the API has tribes at all (an older API refuses the fields), which chat
 * checks before asking a room for shared tribes.
 */
class TribeStore(private val api: HotMessApi, private val now: () -> Long = System::currentTimeMillis) {
    private val _choices = MutableStateFlow<TribeChoices?>(null)
    val choices: StateFlow<TribeChoices?> = _choices.asStateFlow()

    private val _isSupported = MutableStateFlow<Boolean?>(null)

    /** True once the API answered with tribes, false when it refused the fields, null when nobody knows yet. */
    val isSupported: StateFlow<Boolean?> = _isSupported.asStateFlow()

    private var checkedAt = 0L

    /** Loads the list and picks, unless they're fresh and [force] is off. Failures leave what was known. */
    suspend fun load(force: Boolean = false) {
        if (!force && _choices.value != null && now() - checkedAt < FRESH_FOR) return
        try {
            _choices.value = api.tribes()
            _isSupported.value = true
            checkedAt = now()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A GraphQL error with no code is graphql-ruby refusing a field this API doesn't have.
            if (ApiError.of(e) is ApiError.Other) {
                _isSupported.value = false
                checkedAt = now()
            }
        }
    }

    /** Whether the API has tribes, asking it when the last answer is old or missing. */
    suspend fun supportsTribes(): Boolean {
        if (_isSupported.value == null || now() - checkedAt >= FRESH_FOR) load(force = true)
        return _isSupported.value == true
    }

    /** Replaces the user's picks. Throws when it didn't go. */
    suspend fun save(picked: Set<String>) {
        val saved = api.setTribes(picked)
        _choices.value = _choices.value?.copy(picked = saved)
    }

    fun clear() {
        _choices.value = null
        _isSupported.value = null
        checkedAt = 0L
    }

    companion object {
        /** How long a load stands before chat or the picker asks again. */
        const val FRESH_FOR = 10 * 60 * 1000L
    }
}
