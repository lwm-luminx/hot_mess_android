package social.hotmess.core

import java.net.URI
import java.net.URLDecoder

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

    /** A share link that has expired: Venues, which says so, in place of the record it named. */
    data object LinkExpired : AppRoute

    /** Which tab the route opens in. */
    val tab: AppTab
        get() = when (this) {
            is VenueDetail -> AppTab.VENUES
            is EventDetail -> AppTab.EVENTS
            is PersonDetail -> AppTab.PEOPLE
            is NowPing -> AppTab.NOW
            LinkExpired -> AppTab.VENUES
            // A venue's room over its page; a locale's is reached from Now.
            is Chat -> if (room.kind == ChatRoom.Kind.VENUE) AppTab.VENUES else AppTab.NOW
        }
}

object DeepLink {
    /**
     * A link as the app opens it: its [route], and what `GET /v1/share` checks a share link by. [host] is the
     * link's (null for a `hotmess:` link), [path] its record's (`/venues/<uuid>`), and [expiry] its signed
     * expiry, the `e` query value; null on links from before share links expired, which open as they always have.
     */
    data class Link(val route: AppRoute, val host: String?, val path: String, val expiry: String?)

    /**
     * Reads `hotmess://venues/<uuid>` (the type is the host) and
     * `https://hotmess.social/venues/<uuid>`; likewise `events`, `people` and `pings` (a Ping opens Now).
     * A room's link, `venues/<uuid>/chat` or `locales/<uuid>/chat`, opens its chat. Pushes carry such a
     * URL in `url`, which is what the app routes on (a `recent_chat` push carries its room's).
     */
    fun route(url: String): AppRoute? = link(url)?.route

    /** [route], with the link's host, path and `e` (`?e=<expiry>.<signature>`) for checking a share link. */
    fun link(url: String): Link? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = if (uri.scheme == "hotmess") {
            listOfNotNull(uri.host, uri.path).flatMap { it.split('/') }
        } else {
            uri.path.orEmpty().split('/')
        }.filter { it.isNotEmpty() }

        val route = route(segments) ?: return null
        val expiry = uri.rawQuery.orEmpty().split('&')
            .firstOrNull { it.startsWith("e=") }
            ?.let { URLDecoder.decode(it.removePrefix("e="), Charsets.UTF_8) }
            ?.takeIf { it.isNotEmpty() }
        return Link(
            route = route,
            host = uri.host.takeIf { uri.scheme != "hotmess" },
            path = segments.joinToString("/", prefix = "/"),
            expiry = expiry,
        )
    }

    private fun route(segments: List<String>): AppRoute? {
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

/**
 * A share link's `e`: `<expiry in Unix seconds, base36>.<signature>`. Only the API can check the signature, but
 * the expiry can be read here, so a link that has plainly expired never waits on the network.
 */
object ShareLinkExpiry {
    /** When the link stops opening its record, in Unix seconds; null when [token] doesn't start with one. */
    fun expiresAt(token: String): Long? =
        token.substringBefore('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }?.toLongOrNull(36)

    /** Whether [token]'s expiry has passed by [now] (Unix seconds). One that can't be read is left to the API. */
    fun hasPassed(token: String, now: Long): Boolean = expiresAt(token)?.let { now >= it } ?: false
}
