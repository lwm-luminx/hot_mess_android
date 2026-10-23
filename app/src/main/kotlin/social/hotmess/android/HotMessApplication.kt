package social.hotmess.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.audiencekit.android.AudienceKitClient
import com.newrelic.agent.android.NewRelic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import social.hotmess.android.location.LocationProvider
import social.hotmess.android.payments.SquareCardEntry
import social.hotmess.android.push.InAppNotifications
import social.hotmess.android.push.PushRegistrar
import social.hotmess.android.session.AuthState
import social.hotmess.android.session.BrandStore
import social.hotmess.android.session.FacebookAppSignIn
import social.hotmess.android.session.SafetyStore
import social.hotmess.android.session.SessionStore
import social.hotmess.android.session.TribeStore
import social.hotmess.android.support.ErrorReporter
import social.hotmess.core.AppRoute
import social.hotmess.core.CableConnection
import social.hotmess.core.ChatMutes
import social.hotmess.core.GraphQLCable
import social.hotmess.core.HotMessApi
import social.hotmess.core.PassBook
import social.hotmess.core.StringSetStorage

/** The app's services, built once. Screens reach them through [LocalAppGraph][social.hotmess.android.ui.LocalAppGraph]. */
class AppGraph(context: Context, val configuration: AppConfiguration = AppConfiguration()) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val audienceKit = AudienceKitClient(context, configuration.audienceKit)
    val api = HotMessApi(audienceKit)
    val brand = BrandStore(audienceKit, scope)
    val push = PushRegistrar(context, api, scope)
    val session = SessionStore(context, api, audienceKit, configuration, push, scope)
    val location = LocationProvider(context, api, configuration, scope)

    /** Terms of use, blocking and reporting, as the API keeps them for the signed-in user. */
    val safety = SafetyStore(api)

    /** The user's tribes and whether the API has them; chat shows who shares one only when it does. */
    val tribes = TribeStore(api)

    /** Who the user muted in each chat room, kept on this device only (the app's preferences). */
    val chatMutes = ChatMutes(PreferenceStringSets(context.applicationContext))

    /** Crash and error reports, and what "Report a problem" attaches. */
    val errors = ErrorReporter(context, api, configuration, scope)

    /** The user's cover passes, kept so an opened pass still works with no signal at the door. */
    val passes = PassBook()

    /** Square's card entry, for Square venues' cover; MainActivity passes it card entry's result. */
    val squareCardEntry = SquareCardEntry()

    /**
     * The one Action Cable socket every GraphQL subscription shares (chat rooms, your notifications). It's
     * open while something is subscribed: the app in front and signed in, or a chat room on screen.
     */
    val cable = CableConnection(
        url = checkNotNull(GraphQLCable.realtimeUrl(configuration.baseUrl)) { "No realtime URL for ${configuration.baseUrl}" },
        token = { session.sessionToken },
    )

    /** Your notifications over the cable while the app is in front, shown in the app. */
    val notifications = InAppNotifications(
        scope = scope,
        cable = cable,
        audienceId = { api.client.audienceId() },
        onShown = { notification -> (notification.route as? AppRoute.NowPing)?.let { pingUpdated(it.pingId) } },
    )

    /** Whether the app is on screen, from the process lifecycle. */
    @Volatile var isForeground: Boolean = false
        private set

    fun setForeground(foreground: Boolean) {
        isForeground = foreground
        notifications.setForeground(foreground)
        if (foreground && session.isSignedIn) notifications.start() else notifications.stop()
    }

    init {
        // A socket the server refused as unauthorized ends the session as a 401 does.
        cable.onUnauthorized = { token -> scope.launch { session.sessionRejected(token) } }
        scope.launch {
            session.state.collect {
                when (it) {
                    AuthState.SignedOut -> {
                        passes.clear()
                        api.friends.clear()
                        notifications.clear()
                        safety.clear()
                        tribes.clear()
                        cable.disconnect()
                    }
                    AuthState.SignedIn -> {
                        if (isForeground) notifications.start()
                        scope.launch { safety.refresh() }
                    }
                    else -> Unit
                }
            }
        }
    }

    private val _pingUpdates = MutableSharedFlow<String?>(extraBufferCapacity = 8)

    /** A Ping push arrived or was tapped, with its Ping's ID; Now reloads to show it. */
    val pingUpdates: SharedFlow<String?> = _pingUpdates.asSharedFlow()

    fun pingUpdated(pingId: String?) {
        _pingUpdates.tryEmit(pingId)
    }

    private val _pingInvitation = MutableStateFlow<AppRoute.NowPing?>(null)

    /**
     * A texted Ping link that was opened, with its invitation, until Now shows it. Kept while signed out, so it's
     * shown after signing in.
     */
    val pingInvitation: StateFlow<AppRoute.NowPing?> = _pingInvitation.asStateFlow()

    fun openPingInvitation(link: AppRoute.NowPing?) {
        _pingInvitation.value = link
    }
}

class HotMessApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        graph = AppGraph(this)
        graph.configuration.newRelicToken?.let { NewRelic.withApplicationToken(it).start(this) }
        graph.errors.start()
        FacebookAppSignIn.initialize(this, graph.configuration)

        graph.brand.load()
        graph.scope.launch { graph.session.start() }

        // Follow the device only while the app is on screen, like the iOS app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (graph.session.isSignedIn) graph.location.start()
                // Your notifications come over the cable while the app is in front (and signed in).
                graph.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                graph.location.stop()
                graph.setForeground(false)
            }
        })
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as HotMessApplication).graph

/** String sets in the app's preferences ([SessionStore.PREFERENCES]), so "Reset local data" clears them too. */
private class PreferenceStringSets(private val context: Context) : StringSetStorage {
    private val preferences get() = context.getSharedPreferences(SessionStore.PREFERENCES, Context.MODE_PRIVATE)

    override fun get(key: String): Set<String> = preferences.getStringSet(key, null)?.toSet().orEmpty()

    override fun put(key: String, values: Set<String>) {
        preferences.edit().apply { if (values.isEmpty()) remove(key) else putStringSet(key, values.toSet()) }.apply()
    }
}
