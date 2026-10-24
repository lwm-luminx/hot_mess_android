package social.hotmess.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI

/**
 * A venue's short link, `https://hotmess.social/v/<code>` (eight letters and digits), what a venue's TV QR code
 * carries. hotmess.social redirects it to the venue's chat (`/venues/<id>/chat`); a redirect within the site never
 * opens the app, so the app reads that redirect itself ([target]) and opens where it goes.
 */
object ShortLink {
    private val CODE = Regex("[0-9A-Za-z]{8}")

    /** The short link on hotmess.social that [url] is (`https://` or `hotmess://`), or null for any other link. */
    fun url(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val segments = when {
            uri.scheme == "hotmess" -> listOfNotNull(uri.host, uri.path).flatMap { it.split('/') }
            uri.scheme == "https" && uri.host?.lowercase() in setOf("hotmess.social", "www.hotmess.social") ->
                uri.path.orEmpty().split('/')
            else -> return null
        }.filter { it.isNotEmpty() }
        val code = segments.getOrNull(1)?.takeIf { segments.size == 2 && segments[0] == "v" && CODE.matches(it) }
        return code?.let { "https://hotmess.social/v/$it" }
    }

    /** Where the site redirects [url], or null when it doesn't (an unknown code, or offline). Blocks: call off main. */
    fun target(url: String, client: OkHttpClient = OkHttpClient()): String? = runCatching {
        val noFollow = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        noFollow.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            response.header("Location")?.takeIf { response.isRedirect }?.let { URI(url).resolve(it).toString() }
        }
    }.getOrNull()
}
