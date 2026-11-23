package social.hotmess.android.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.hotmess.android.MainActivity
import social.hotmess.android.appGraph
import social.hotmess.core.Now
import social.hotmess.core.PingPush
import social.hotmess.core.TonightSnapshot
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Where the Tonight widget's snapshot lives: a file in the app's no-backup folder. The widget runs in the app's own
 * process, so (unlike iOS) there's no app group; it only ever reads this file, and never signs in or calls the API.
 */
object TonightStore {
    private const val FILE = "tonight.json"

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE)

    suspend fun read(context: Context): TonightSnapshot? = withContext(Dispatchers.IO) {
        TonightSnapshot.decode(runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull())
    }

    /** Saves [snapshot] and redraws the widgets, unless only its time changed. */
    suspend fun save(context: Context, snapshot: TonightSnapshot) {
        val previous = read(context)
        if (previous?.copy(updatedAt = snapshot.updatedAt) == snapshot) return
        withContext(Dispatchers.IO) {
            val target = file(context)
            val temp = File(target.parentFile, "$FILE.tmp")
            temp.writeText(snapshot.encode())
            temp.renameTo(target)
        }
        TonightWidget().updateAll(context)
    }

    /** From a freshly loaded Now. */
    suspend fun update(context: Context, now: Now) = save(context, TonightSnapshot.from(now))

    /** Forgets everything, for signing out. */
    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) { file(context).delete() }
        TonightWidget().updateAll(context)
    }
}

/**
 * The Tonight home screen widget: the Android counterpart of the iOS Tonight widget (hot_mess_ios
 * `HotMessWidgets/HotMessTonightWidget.swift`, #210). It shows the user's Ping and friends' Pings with who's in and
 * an "I'm in" button, then what's on tonight where they are. Tapping a row opens it in the app. Ended Pings and
 * events drop off whenever it redraws (on each save, and every half hour from `tonight_widget_info.xml`).
 */
class TonightWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val signedIn = context.appGraph.session.isSignedIn
        val snapshot = TonightStore.read(context)?.takeIf { signedIn }?.current(Instant.now())
        provideContent {
            GlanceTheme { Content(context, snapshot) }
        }
    }

    @Composable
    private fun Content(context: Context, snapshot: TonightSnapshot?) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .cornerRadius(20.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(14.dp),
        ) {
            Text(
                text = snapshot?.header ?: "Tonight",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.clickable(open(context, SITE)),
            )
            snapshot?.onNow?.let {
                Text(text = "On now: $it", style = secondary(), maxLines = 1)
            }
            Spacer(GlanceModifier.height(8.dp))
            if (snapshot == null || snapshot.isEmpty) {
                Text(
                    text = "Open Hot Mess to see what's on tonight and your friends' Pings.",
                    style = secondary(),
                    modifier = GlanceModifier.clickable(open(context, SITE)),
                )
                return@Column
            }
            LazyColumn {
                items(snapshot.pings, itemId = { ("p" + it.id).hashCode().toLong() }) { ping -> PingRow(context, ping) }
                items(snapshot.events, itemId = { ("e" + it.id).hashCode().toLong() }) { event -> EventRow(context, event) }
            }
        }
    }

    @Composable
    private fun PingRow(context: Context, ping: TonightSnapshot.Ping) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open(context, ping.url)),
        ) {
            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(text = ping.title, style = primary(), maxLines = 1)
                Text(text = ping.peopleLine?.let { "$it in" } ?: "No one in yet", style = secondary(), maxLines = 1)
            }
            if (ping.canJoin) {
                Spacer(GlanceModifier.width(8.dp))
                Button(
                    text = "I'm in",
                    onClick = actionRunCallback<JoinPingFromWidget>(actionParametersOf(JoinPingFromWidget.PING to ping.id)),
                )
            }
        }
    }

    @Composable
    private fun EventRow(context: Context, event: TonightSnapshot.Event) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open(context, event.url))) {
            Text(text = event.name, style = primary(), maxLines = 1)
            val time = TIME.format(event.startsAt.atZone(ZoneId.systemDefault()))
            Text(text = listOfNotNull(time, event.place).joinToString(" · "), style = secondary(), maxLines = 1)
        }
    }

    @Composable
    private fun primary() = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)

    @Composable
    private fun secondary() = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp)

    private companion object {
        const val SITE = "https://hotmess.social/"
        val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        /** Opens [url] in the app, as a tapped push does (MainActivity reads its `url`). */
        fun open(context: Context, url: String) = actionStartActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PingPush.URL, url),
        )
    }
}

/** "I'm in" on a friend's Ping, from the widget without opening the app. */
class JoinPingFromWidget : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val pingId = parameters[PING] ?: return
        val graph = context.appGraph
        if (!graph.session.isSignedIn) return
        try {
            val ping = graph.api.joinPing(pingId, null)
            TonightStore.read(context)?.let { TonightStore.save(context, it.replacing(TonightSnapshot.entry(ping))) }
            graph.pingUpdated(ping.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The button stays; opening the app shows what happened.
        }
    }

    companion object {
        val PING = ActionParameters.Key<String>("ping")
    }
}

class TonightWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TonightWidget()
}
