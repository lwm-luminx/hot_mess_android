package social.hotmess.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import social.hotmess.android.MainActivity
import social.hotmess.android.R
import social.hotmess.android.appGraph
import social.hotmess.core.HotMessApi
import social.hotmess.core.OngoingAction
import social.hotmess.core.OngoingCard
import social.hotmess.core.OngoingCards
import social.hotmess.core.PairUpState
import social.hotmess.core.Ping
import social.hotmess.core.PingPush

/**
 * The ongoing notifications for an open Ping and a Quickie: the Android counterpart of the iOS Live Activities (#234,
 * #279). The app posts them whenever it loads that state (Now, the Quickie sheet) and after each button, and takes
 * them down once it's over. In the background they keep their last state; a Ping's counts down to its 5am end and is
 * dropped then. Silent: they update in place without a sound or a buzz. On Android 16 they ask to be promoted to a
 * Live Update (the status bar chip); elsewhere they're plain ongoing notifications.
 */
class OngoingNotifications(private val context: Context, private val api: HotMessApi, private val scope: CoroutineScope) {
    private var pingKey: String? = null
    private val quickieTitles = mutableMapOf<String, String>()

    /** Shows the open Ping from what Now loaded, or takes the Ping card down when there's none. */
    fun syncPings(myPing: Ping?, friendPings: List<Ping>) {
        val card = OngoingCards.ping(myPing, friendPings)
        if (card == null) {
            pingKey?.let { cancel(OngoingCard.Kind.PING, it) }
            pingKey = null
            return
        }
        pingKey?.takeIf { it != card.key }?.let { cancel(OngoingCard.Kind.PING, it) }
        pingKey = card.key
        post(card)
    }

    /** Shows the user's Quickie at [venueId] while it's open, or takes it down. A blank [venueName] keeps the last title. */
    fun syncQuickie(state: PairUpState?, venueId: String, venueName: String?) {
        val card = OngoingCards.quickie(state, venueId, venueName?.takeIf { it.isNotBlank() } ?: quickieTitles[venueId]?.removePrefix("Quickie at "))
        if (card == null) {
            cancel(OngoingCard.Kind.QUICKIE, venueId)
            quickieTitles.remove(venueId)
            return
        }
        quickieTitles[venueId] = card.title
        post(card)
    }

    /** Takes every card down, for signing out. */
    fun clear() {
        pingKey?.let { cancel(OngoingCard.Kind.PING, it) }
        pingKey = null
        quickieTitles.keys.toList().forEach { cancel(OngoingCard.Kind.QUICKIE, it) }
        quickieTitles.clear()
    }

    /** Runs a card's button, then shows what the API answered. */
    internal fun perform(action: OngoingAction, kind: OngoingCard.Kind, key: String, matchId: String?, venueName: String?): Job =
        scope.launch {
            try {
                when (action) {
                    OngoingAction.JOIN_PING -> {
                        val ping = api.joinPing(key, null)
                        post(OngoingCards.ping(ping))
                        context.appGraph.pingUpdated(ping.id)
                    }
                    OngoingAction.ACCEPT_QUICKIE -> matchId?.let { syncQuickie(api.acceptPairUp(it), key, venueName) }
                    OngoingAction.NEW_TOPIC -> matchId?.let { syncQuickie(api.skipPairUp(it, partner = false), key, venueName) }
                    OngoingAction.CHAT -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The card stays as it was; opening the app shows what happened.
            }
        }

    private fun post(card: OngoingCard) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Tonight", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Your open Ping and Quickie, with buttons to join or answer."
                setShowBadge(false)
            },
        )
        val id = notificationId(card.kind, card.key)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(card.title)
            .setContentText(card.topic ?: card.status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(card.status, card.topic?.let { "“$it”" }).joinToString("\n")))
            .setContentIntent(open(card.url, id))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(if (card.kind == OngoingCard.Kind.PING) NotificationCompat.CATEGORY_SOCIAL else NotificationCompat.CATEGORY_STATUS)
            // Locked, the shade shows the title only: the status and topic are for the user.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                    .setContentTitle(if (card.kind == OngoingCard.Kind.PING) "Ping" else "Quickie")
                    .build(),
            )
            // Android 16's Live Updates (Notification.EXTRA_REQUEST_PROMOTED_ONGOING); ignored before.
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        card.endsAt?.let { ends ->
            builder.setWhen(ends.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            val left = ends.toEpochMilli() - System.currentTimeMillis()
            if (left > 0) builder.setTimeoutAfter(left)
        }
        card.actions.forEachIndexed { index, action ->
            val intent = if (action == OngoingAction.CHAT) {
                open(card.chatUrl, id * 10 + index)
            } else {
                val broadcast = Intent(context, OngoingActionReceiver::class.java)
                    .setAction(ACTION)
                    .putExtra(EXTRA_ACTION, action.name)
                    .putExtra(EXTRA_KIND, card.kind.name)
                    .putExtra(EXTRA_KEY, card.key)
                    .putExtra(EXTRA_MATCH, card.matchId)
                    .putExtra(EXTRA_TITLE, card.title.removePrefix("Quickie at ").takeIf { card.kind == OngoingCard.Kind.QUICKIE && it != "Quickie" })
                PendingIntent.getBroadcast(context, id * 10 + index, broadcast, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            builder.addAction(0, action.label, intent)
        }
        try {
            NotificationManagerCompat.from(context).notify(TAG, id, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun cancel(kind: OngoingCard.Kind, key: String) {
        NotificationManagerCompat.from(context).cancel(TAG, notificationId(kind, key))
    }

    /** Opens [url] in the app, as a tapped push does (MainActivity reads its `url`). */
    private fun open(url: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PingPush.URL, url)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notificationId(kind: OngoingCard.Kind, key: String): Int = (kind.name + key).hashCode() and 0x0FFFFFFF

    companion object {
        const val CHANNEL = "tonight"
        private const val TAG = "ongoing"
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        internal const val ACTION = "social.hotmess.android.ONGOING_ACTION"
        internal const val EXTRA_ACTION = "action"
        internal const val EXTRA_KIND = "kind"
        internal const val EXTRA_KEY = "key"
        internal const val EXTRA_MATCH = "match"
        internal const val EXTRA_TITLE = "title"
    }
}

/** A button on an ongoing card ("I'm in", "New topic"), run without opening the app. */
class OngoingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OngoingNotifications.ACTION) return
        val action = intent.getStringExtra(OngoingNotifications.EXTRA_ACTION)?.let { runCatching { OngoingAction.valueOf(it) }.getOrNull() } ?: return
        val kind = intent.getStringExtra(OngoingNotifications.EXTRA_KIND)?.let { runCatching { OngoingCard.Kind.valueOf(it) }.getOrNull() } ?: return
        val key = intent.getStringExtra(OngoingNotifications.EXTRA_KEY) ?: return
        val graph = context.applicationContext.appGraph
        if (!graph.session.isSignedIn) return
        // Keeps the process up until the API answers and the card is redrawn.
        val pending = goAsync()
        graph.ongoing.perform(
            action, kind, key,
            intent.getStringExtra(OngoingNotifications.EXTRA_MATCH),
            intent.getStringExtra(OngoingNotifications.EXTRA_TITLE),
        ).invokeOnCompletion { pending.finish() }
    }
}
