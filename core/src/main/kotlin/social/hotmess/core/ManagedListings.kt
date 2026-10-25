package social.hotmess.core

/**
 * The venues and talent the signed-in user manages here (`me { listings }`), empty for everyone else. Hot Mess doesn't
 * write or manage posts: Me sends venue managers to Velvet and talent to Backstage, where they do.
 */
data class ManagedListings(val venues: List<Listing> = emptyList(), val people: List<Listing> = emptyList()) {
    data class Listing(val id: String, val name: String)

    val isEmpty: Boolean get() = venues.isEmpty() && people.isEmpty()

    companion object {
        val NONE = ManagedListings()
    }
}
