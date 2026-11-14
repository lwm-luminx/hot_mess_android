package social.hotmess.android.ui

import org.json.JSONObject
import social.hotmess.core.CableSocket
import social.hotmess.core.CableSocketFactory
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A stand-in for the API's Action Cable endpoint, for the screen tests: welcomes every socket, confirms every
 * subscription, and answers each operation run on it with what [on] registered for its operation name (a chat
 * room's `ChatJoined`, say). Nothing opens a socket; an operation nobody registered just hears nothing.
 */
class StubCable : CableSocketFactory {
    private val replies = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val sockets = Collections.synchronizedList(mutableListOf<Socket>())
    private val executed = Collections.synchronizedList(mutableListOf<String>())
    private val thread = Executors.newSingleThreadExecutor { Thread(it, "stub-cable").apply { isDaemon = true } }

    /** The operations run over the cable, by field: "venueChat", "localeChat", … */
    val operations: List<String> get() = synchronized(executed) { executed.toList() }

    /** Answers an operation whose query subscribes to `field` with the subscription's first event, `event`. */
    fun on(field: String, event: String) {
        replies[field] = event
    }

    /** Sends `event` to every subscription to `field` that is open now. */
    fun push(field: String, event: String) {
        synchronized(sockets) { sockets.toList() }.forEach { it.deliver(field, event) }
    }

    override fun open(url: String, token: String?, listener: CableSocket.Listener): CableSocket =
        Socket(listener).also { socket ->
            sockets += socket
            thread.execute { listener.onMessage("""{"type":"welcome"}""") }
        }

    private inner class Socket(private val listener: CableSocket.Listener) : CableSocket {
        /** Subscription identifiers by the field their operation subscribes to. */
        private val fields = Collections.synchronizedMap(mutableMapOf<String, String>())

        override fun send(text: String): Boolean {
            val frame = JSONObject(text)
            val identifier = frame.optString("identifier")
            when (frame.optString("command")) {
                "subscribe" -> thread.execute {
                    listener.onMessage(JSONObject().put("type", "confirm_subscription").put("identifier", identifier).toString())
                }
                "message" -> {
                    val query = JSONObject(frame.optString("data")).optString("query")
                    val field = Regex("""\{\s*([A-Za-z]+)\s*\(""").find(query.substringAfter("subscription"))?.groupValues?.get(1)
                        ?: return true
                    executed += field
                    fields[field] = identifier
                    replies[field]?.let { deliver(field, it) }
                }
            }
            return true
        }

        fun deliver(field: String, event: String) {
            val identifier = fields[field] ?: return
            val message = """{"identifier":${JSONObject.quote(identifier)},"message":{"result":{"data":{"$field":$event}},"more":true}}"""
            thread.execute { listener.onMessage(message) }
        }

        override fun close(code: Int, reason: String?) {
            sockets -= this
            thread.execute { listener.onClosed(reason) }
        }
    }
}
