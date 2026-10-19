package social.hotmess.android.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.hotmess.core.BlockedUser
import social.hotmess.core.HotMessApi
import social.hotmess.core.ReportReason
import social.hotmess.core.ReportResult
import social.hotmess.core.ReportSubject
import social.hotmess.core.SafetyState

/**
 * The signed-in user's safety settings: whether they agreed to the terms of use (chat and Pings ask first) and who
 * they block, which chat hides at once. Null until loaded, and when the API can't say; cleared on sign-out.
 */
class SafetyStore(private val api: HotMessApi) {
    private val _state = MutableStateFlow<SafetyState?>(null)
    val state: StateFlow<SafetyState?> = _state.asStateFlow()

    /** Loads what the API has. Failures leave what was known. */
    suspend fun refresh() {
        try {
            _state.value = api.safety()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Whether to ask for the terms before chatting or sending a Ping: only when the API said they haven't agreed. */
    val needsTerms: Boolean get() = _state.value?.termsAccepted == false

    /** Records their agreement. Throws when it didn't go. */
    suspend fun acceptTerms() {
        api.acceptTerms()
        _state.value = (_state.value ?: SafetyState()).copy(termsAccepted = true)
    }

    /** Blocks [person]: hidden at once, then whatever the API says. Throws when it didn't go. */
    suspend fun block(person: BlockedUser) {
        val before = _state.value
        val current = before ?: SafetyState(termsAccepted = true)
        if (!current.isBlocked(person.id)) _state.value = current.copy(blockedUsers = current.blockedUsers + person)
        try {
            _state.value = current.copy(blockedUsers = api.block(person.id))
        } catch (e: Exception) {
            _state.value = before
            throw e
        }
    }

    /** Unblocks someone. Throws when it didn't go. */
    suspend fun unblock(userId: String) {
        val current = _state.value ?: SafetyState(termsAccepted = true)
        _state.value = current.copy(blockedUsers = api.unblock(userId))
    }

    /** Files a report; when [block] blocked the sender too, they're hidden at once. Throws when it didn't go. */
    suspend fun report(subject: ReportSubject, reason: ReportReason, block: Boolean): ReportResult {
        val result = api.report(subject.target, subject.targetId, reason.label, block && subject.sender != null)
        val sender = subject.sender
        if (result.blocked && sender != null) {
            val current = _state.value ?: SafetyState(termsAccepted = true)
            if (!current.isBlocked(sender.id)) _state.value = current.copy(blockedUsers = current.blockedUsers + sender)
        }
        return result
    }

    /** Deletes the account for good. Throws when it didn't go; the caller signs out after. */
    suspend fun deleteAccount() {
        api.deleteAccount()
        clear()
    }

    fun clear() {
        _state.value = null
    }
}
