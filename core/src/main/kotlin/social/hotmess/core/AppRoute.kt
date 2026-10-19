package social.hotmess.core

import java.net.URI

/** The tabs, in the order the bottom bar shows them. */
enum class AppTab { NOW, EVENTS, VENUES, PEOPLE, ME }

/** A screen a link or a row can open. IDs are lowercase UUIDs. */
sealed interface AppRoute {
    data class VenueDetail(val id: String) : AppRoute
    data class EventDetail(val id: String) : AppRoute
    data class PersonDetail(val id: String) : AppRoute

    /** The Now tab, opened from a Ping push; [pingId] names the Ping it was about. */
    data class NowPing(val pingId: String? = null) : AppRoute

    /** A chat room by its venue's or locale's id ([room]), from a link or a push about a room the user left. */
    data class Chat(val room: ChatRoom) : AppRoute

    /** Which tab the route opens in. */
    val tab: AppTab
        get() = when (this) {
            is VenueDetail -> AppTab.VENUES
            is EventDetail -> AppTab.EVENTS
            is PersonDetail -> AppTab.PEOPLE
            is NowPing -> AppTab.NOW
            // A venue's room over its page; a locale's is reached from Now.
            is Chat -> if (room.kind == ChatRoom.Kind.VENUE) AppTab.VENUES else AppTab.NOW
        }
}

object DeepLink {
    /**
     * Reads `hotmess://venues/<uuid>` (the type is the host) and
     * `https://hotmess.social/venues/<uuid>`; likewise `events`, `people` and `pings` (a Ping opens Now).
     * A room's link, `venues/<uuid>/chat` or `locales/<uuid>/chat`, opens its chat. Pushes carry such a
     * URL in `url`, which is what the app routes on (a `recent_chat` push carries its room's).
     */
    fun route(url: String): AppRoute? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = if (uri.scheme == "hotmess") {
            listOfNotNull(uri.host, uri.path).flatMap { it.split('/') }
        } else {
            uri.path.orEmpty().split('/')
        }.filter { it.isNotEmpty() }

        if (segments.size < 2) return null
        val id = runCatching { java.util.UUID.fromString(segments[1]).toString() }.getOrNull() ?: return null

        if (segments.getOrNull(2) == "chat") {
            return when (segments[0]) {
                "venues" -> AppRoute.Chat(ChatRoom.venue(id))
                "locales" -> AppRoute.Chat(ChatRoom.locale(id))
                else -> null
            }
        }

        return when (segments[0]) {
            "venues" -> AppRoute.VenueDetail(id)
            "events" -> AppRoute.EventDetail(id)
            "people" -> AppRoute.PersonDetail(id)
            "pings" -> AppRoute.NowPing(id)
            else -> null
        }
    }
}
