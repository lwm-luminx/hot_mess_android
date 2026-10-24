package social.hotmess.android.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import social.hotmess.core.HotMessApi
import social.hotmess.core.ManagedListings

/**
 * The venues and talent the signed-in user manages, so Me offers Velvet or Backstage. Empty for everyone else, and
 * until loaded; cleared on sign-out.
 */
class ListingsStore(private val api: HotMessApi) {
    private val _state = MutableStateFlow(ManagedListings.NONE)
    val state: StateFlow<ManagedListings> = _state.asStateFlow()

    /** Loads them (consumer endpoint). Anything that goes wrong means none. */
    suspend fun refresh() {
        _state.value = api.managedListings()
    }

    fun clear() {
        _state.value = ManagedListings.NONE
    }
}
