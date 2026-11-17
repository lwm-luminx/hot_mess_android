package social.hotmess.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.audiencekit.HttpRequest
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import com.audiencekit.AudienceKitClient
import org.json.JSONObject
import social.hotmess.android.AppConfiguration
import social.hotmess.android.AppGraph
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.core.AppRoute
import social.hotmess.android.ui.screens.FixTarget
import social.hotmess.core.HelpVenue
import java.util.Collections

/**
 * A stand-in for the AudienceKit API, for the screen tests: the SDK's HTTP goes through this instead of the network,
 * GraphQL answered by operation name ("Venue") and REST by "METHOD /path". Nothing opens a socket. A request nobody
 * stubbed gets an error reply and is remembered, and [unstubbed] lets a test fail on it; a request for any host but
 * the stub's is refused the same way.
 */
class StubApi : HttpTransport {
    class Request(val key: String, val operationName: String?, val variables: JSONObject, val url: String)

    sealed interface Reply {
        data class Data(val json: String) : Reply
        /** `{"errors":[…],"data":null}`, as the API refuses an operation; [code] is the error's `extensions.code`. */
        data class Errors(val messages: List<String>, val code: String? = null) : Reply
        data class Raw(val status: Int, val body: String) : Reply
    }

    private val replies = Collections.synchronizedMap(mutableMapOf<String, (Request) -> Reply>())
    private val recorded = Collections.synchronizedList(mutableListOf<Request>())
    private val missing = Collections.synchronizedList(mutableListOf<String>())
    private val tolerated = Collections.synchronizedSet(mutableSetOf<String>())

    val requests: List<Request> get() = synchronized(recorded) { recorded.toList() }
    val operationNames: List<String> get() = requests.map { it.key }
    val unstubbed: Set<String> get() = synchronized(missing) { missing.toSet() }

    fun on(key: String, reply: Reply) {
        replies[key] = { reply }
    }

    fun on(key: String, respond: (Request) -> Reply) {
        replies[key] = respond
    }

    fun data(key: String, json: String) = on(key, Reply.Data(json))
    fun error(key: String, message: String, code: String? = null) = on(key, Reply.Errors(listOf(message), code))

    /** Lets `keys` go unstubbed (an error reply) without failing the test: side loads that come and go with timing. */
    fun tolerate(vararg keys: String) {
        tolerated.addAll(keys)
    }

    fun count(key: String) = requests.count { it.key == key }
    fun last(key: String) = requests.lastOrNull { it.key == key }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (!request.url.startsWith(BASE_URL)) {
            missing.add("outside the stub: ${request.url}")
            return HttpResponse(599, """{"error":"not the stub"}""".toByteArray())
        }
        val body = request.body?.toString(Charsets.UTF_8).orEmpty()
        val json = runCatching { JSONObject(body) }.getOrNull()
        val query = json?.optString("query").orEmpty()
        val operationName = json?.optString("operationName")?.takeIf { it.isNotEmpty() && it != "null" }
            ?: Regex("""(?:query|mutation|subscription)\s+([A-Za-z_][A-Za-z0-9_]*)""").find(query)?.groupValues?.get(1)
        val key = if (query.isNotEmpty()) operationName ?: "graphql"
        else "${request.method} ${request.url.removePrefix(BASE_URL).substringBefore('?')}"
        val parsed = Request(key, operationName, json?.optJSONObject("variables") ?: JSONObject(), request.url)
        recorded.add(parsed)
        val respond = replies[key]
        if (respond == null) {
            if (key !in tolerated) missing.add(key)
            return reply(Reply.Errors(listOf("unstubbed $key")))
        }
        return reply(respond(parsed))
    }

    private fun reply(reply: Reply): HttpResponse = when (reply) {
        is Reply.Data -> HttpResponse(200, """{"data":${reply.json}}""".toByteArray())
        is Reply.Errors -> {
            val errors = org.json.JSONArray()
            reply.messages.forEach { message ->
                val error = JSONObject().put("message", message)
                reply.code?.let { error.put("extensions", JSONObject().put("code", it)) }
                errors.put(error)
            }
            HttpResponse(200, JSONObject().put("errors", errors).put("data", JSONObject.NULL).toString().toByteArray())
        }
        is Reply.Raw -> HttpResponse(reply.status, reply.body.toByteArray())
    }

    /** An AppGraph signed in (in memory) to this stub. */
    fun graph(signedIn: Boolean = true): AppGraph {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A test build, so "Pretend I'm here" (LocationProvider.simulate) works.
        val configuration = AppConfiguration(baseUrl = BASE_URL, audienceId = AUDIENCE_ID, isTestBuild = true)
        return AppGraph(context, configuration) { log ->
            AudienceKitClient(
                configuration.audienceKit,
                InMemoryTokenStore(if (signedIn) "stub-token" else null, AUDIENCE_ID),
                this,
                monitor = log,
            )
        }
    }

    companion object {
        const val BASE_URL = "https://stub.invalid"
        const val AUDIENCE_ID = "00000000-0000-4000-8000-000000000001"
    }
}

/** Shows `content` the way the app does: its theme and the graph screens read. */
fun ComposeContentTestRule.show(graph: AppGraph, darkTheme: Boolean = false, content: @Composable () -> Unit) {
    setContent {
        CompositionLocalProvider(LocalAppGraph provides graph) {
            HotMessTheme(darkTheme = darkTheme) { content() }
        }
    }
}

/** Waits until some node's text contains `text`. */
fun ComposeContentTestRule.waitForText(text: String, timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
    }
}

fun ComposeContentTestRule.hasText(text: String): Boolean =
    onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

/** A navigator that remembers where screens tried to go. */
class RecordingNavigator : Navigator {
    val opened = mutableListOf<String>()
    override fun open(route: AppRoute) { opened += "route:$route" }
    override fun openChat(venueId: String, venueName: String) { opened += "chat:$venueId" }
    override fun openLocaleChat(localeId: String, localeName: String) { opened += "localeChat:$localeId" }
    override fun openPass(admissionId: String) { opened += "pass:$admissionId" }
    override fun openPasses() { opened += "passes" }
    override fun openHelp(venue: HelpVenue) { opened += "help" }
    override fun openHelpAnywhere() { opened += "helpAnywhere" }
    override fun openSafetyAlert(id: String) { opened += "safetyAlert:$id" }
    override fun openSupport(note: String?) { opened += "support" }
    override fun openSafetyAlerts() { opened += "safetyAlerts" }
    override fun openGettingHelp() { opened += "gettingHelp" }
    override fun openCardsYouSee() { opened += "cards" }
    override fun openTribes() { opened += "tribes" }
    override fun openSuggestListing(event: Boolean, venueId: String?, venueName: String?) { opened += "suggest" }
    override fun openSuggestFix(target: FixTarget) { opened += "fix" }
    override fun openFixDetail(target: FixTarget) { opened += "fixDetail" }
    override fun openYourSuggestions() { opened += "suggestions" }
    override fun openCheckTheApp() { opened += "check" }
    override fun closeSuggestion() { opened += "closeSuggestion" }
    override fun back() { opened += "back" }
}
