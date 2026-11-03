package social.hotmess.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiencekit.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.hotmess.android.AppConfiguration
import social.hotmess.android.session.SessionStore
import social.hotmess.core.AppLocale
import social.hotmess.core.HotMessApi
import social.hotmess.core.PositionTracker
import social.hotmess.core.ReportThrottle
import social.hotmess.core.metersTo

enum class LocationAccess { NOT_REQUESTED, ALLOWED, DENIED }

/** Why the app has no position, for screens that explain it and offer the way back. */
enum class LocationProblem {
    /** Never asked: the button asks. */
    NOT_ASKED,
    /** Hot Mess may not use location; the button asks again, or opens the app's settings once Android won't. */
    DENIED,
    /** Allowed, but location is off for the whole device; the button opens the device's location settings. */
    SERVICES_OFF,
    /** Allowed and on, still waiting for the first fix. */
    WAITING,
}

/**
 * Where the device is, which of the audience's locales that is, and which venue beacon is nearest.
 * Reports new positions to the API (`reportLocation`), as the iOS app does, at most once a minute
 * unless the device has moved 50 metres ([ReportThrottle]); [coordinates] changes only when a report
 * goes out, so the screens that follow it don't send their own bursts. The locale is looked up again
 * only after moving 50 metres, with one lookup in flight at a time.
 */
class LocationProvider(
    private val context: Context,
    private val api: HotMessApi,
    configuration: AppConfiguration,
    private val scope: CoroutineScope,
    private val geofences: VenueGeofences? = null,
) {
    // First, since the flows below read it as they start.
    private val manager = context.getSystemService(LocationManager::class.java)

    private val _coordinates = MutableStateFlow<Coordinates?>(null)
    val coordinates: StateFlow<Coordinates?> = _coordinates.asStateFlow()

    private val _locale = MutableStateFlow(restoreLocale())
    val locale: StateFlow<AppLocale?> = _locale.asStateFlow()

    private val _access = MutableStateFlow(currentAccess(asked = false))
    val access: StateFlow<LocationAccess> = _access.asStateFlow()

    private val _servicesOn = MutableStateFlow(servicesEnabled())

    /** Whether location is on for the device as a whole (Quick Settings' Location tile). */
    val servicesOn: StateFlow<Boolean> = _servicesOn.asStateFlow()

    private val _cityChosen = MutableStateFlow(preferences.getBoolean(LOCALE_CHOSEN_KEY, false))

    /** Whether [locale] is a city picked by hand, rather than the one the device is in. */
    val cityChosen: StateFlow<Boolean> = _cityChosen.asStateFlow()

    private val _simulatedVenue = MutableStateFlow<String?>(null)

    /** Test builds only: the venue the app is pretending to be at, whose position is reported instead of the device's. */
    val simulatedVenue: StateFlow<String?> = _simulatedVenue.asStateFlow()

    private val tracker = PositionTracker(configuration.isTestBuild)
    private val throttle = ReportThrottle()

    /** A report waiting for the throttle; it sends whatever is latest when it fires. */
    private var pendingReport: Job? = null

    /** The ClosestLocale lookup in flight, which other callers join, and where the last one was. */
    private var localeLookup: Deferred<Unit>? = null
    private var localeLookedUpNear: Coordinates? = null

    private val beacons = configuration.beaconUuid?.let { BeaconScanner(context, it, ::onBeacon) }
    private var isMonitoring = false

    private val listener = LocationListener { location -> update(location) }

    /** The device's location switch flipped (often from Quick Settings, with Hot Mess still in front). */
    private val providersChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = servicesEnabled()
            if (on == _servicesOn.value) return
            _servicesOn.value = on
            // Picks a provider again: the one chosen while location was off was none.
            if (on && isMonitoring) {
                manager?.removeUpdates(listener)
                beacons?.stop()
                isMonitoring = false
                beginMonitoring()
            }
        }
    }
    private var watchingProviders = false

    /**
     * Starts following the device when it may; the screens ask for permission first. Also reads the permission and the
     * device's location switch again, since either can change in Settings while Hot Mess is in the background.
     */
    fun start() {
        _servicesOn.value = servicesEnabled()
        if (!hasLocationPermission()) {
            if (_access.value == LocationAccess.ALLOWED) _access.value = LocationAccess.DENIED
            return
        }
        _access.value = LocationAccess.ALLOWED
        beginMonitoring()
    }

    /** What's in the way of a position, or null when there is one. */
    fun problem(access: LocationAccess, servicesOn: Boolean, coordinates: Coordinates?): LocationProblem? = when {
        coordinates != null -> null
        access == LocationAccess.NOT_REQUESTED -> LocationProblem.NOT_ASKED
        access == LocationAccess.DENIED -> LocationProblem.DENIED
        !servicesOn -> LocationProblem.SERVICES_OFF
        else -> LocationProblem.WAITING
    }

    /**
     * Uses [city] for Events, Venues and People until the device has a position of its own, for anyone who keeps
     * location off. A real fix's city replaces it.
     */
    fun chooseCity(city: AppLocale) {
        _locale.value = city
        _cityChosen.value = true
        persist(city, chosen = true)
    }

    fun stop() {
        if (watchingProviders) {
            runCatching { context.unregisterReceiver(providersChanged) }
            watchingProviders = false
        }
        if (!isMonitoring) return
        manager?.removeUpdates(listener)
        beacons?.stop()
        pendingReport?.cancel()
        isMonitoring = false
    }

    /** Called with the answer to the permission prompt. */
    fun onPermissionResult() {
        _access.value = currentAccess(asked = true)
        start()
    }

    /**
     * Asks the API which locale the current position belongs to, unless it already has for a position
     * within 50 metres. A lookup already in flight is joined rather than sent again, and carries on
     * when a screen that asked goes away.
     */
    suspend fun refreshLocale() {
        val position = _coordinates.value ?: return
        val near = localeLookedUpNear
        if (near != null && near.metersTo(position) < ReportThrottle.MIN_DISTANCE_METERS) return
        val lookup = localeLookup?.takeIf { it.isActive }
            ?: scope.async { lookUpLocale(position) }.also { localeLookup = it }
        lookup.await()
    }

    private suspend fun lookUpLocale(position: Coordinates) {
        try {
            val resolved = api.closestLocale(position)
            localeLookedUpNear = position
            resolved ?: return
            if (resolved.id == _locale.value?.id && !_cityChosen.value) return
            _locale.value = resolved
            _cityChosen.value = false
            persist(resolved, chosen = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HotMess", "Locale lookup failed: ${e.message}")
        }
    }

    /**
     * Reports this position as the device's until [stopSimulating], so a test build can be "at" a
     * venue from anywhere. The API puts the user at whichever venue's envelope contains the point.
     * The returned job finishes once the position is reported.
     */
    fun simulate(latitude: Double, longitude: Double, venueName: String): Job? {
        if (!tracker.simulate(latitude, longitude, venueName)) return null
        return reportNow()
    }

    /** Goes back to the device's real position. The returned job finishes once it's reported. */
    fun stopSimulating(): Job? {
        if (!tracker.stopSimulating()) return null
        if (tracker.current == null) {
            publish()
            return null
        }
        return reportNow()
    }

    private fun publish() {
        _coordinates.value = tracker.current
        _simulatedVenue.value = tracker.simulatedVenue
    }

    @SuppressLint("MissingPermission")
    private fun beginMonitoring() {
        if (isMonitoring) return
        val manager = manager ?: return
        isMonitoring = true
        _servicesOn.value = servicesEnabled()
        if (!watchingProviders) {
            ContextCompat.registerReceiver(
                context,
                providersChanged,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            watchingProviders = true
        }

        // Like significant-change monitoring on iOS: a fix every few minutes or 100 metres.
        val provider = listOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        ).filterNotNull().firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

        try {
            provider?.let { manager.requestLocationUpdates(it, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, listener, Looper.getMainLooper()) }
            listOfNotNull(provider, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstNotNullOfOrNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                ?.let(::update)
        } catch (e: SecurityException) {
            Log.w("HotMess", "Location unavailable: ${e.message}")
        }

        beacons?.start()
        // Play Services forgets geofences when location is turned off and on again; hand the kept plan back.
        geofences?.restore()
        scope.launch { refreshLocale() }
    }

    private fun update(location: Location) {
        // Geofences follow the device, even while pretending to be elsewhere; the plan only changes once it's moved.
        geofences?.let { fences -> scope.launch { fences.plan(Coordinates(location.latitude, location.longitude)) } }
        if (!tracker.deviceFix(location.latitude, location.longitude)) return
        reportWhenDue()
    }

    /**
     * A venue geofence was crossed (or the re-center circle left), perhaps with Hot Mess closed: reports [location] at
     * once, since arriving or leaving is what the API needs to hear, then plans the geofences again from there when
     * [replan] (leaving the re-center circle means unwatched venues may be close now).
     */
    suspend fun reportCrossing(location: Location, replan: Boolean) {
        tracker.deviceFix(location.latitude, location.longitude)
        pendingReport?.cancel()
        publish()
        tracker.current?.let { position ->
            throttle.sent(position, SystemClock.elapsedRealtime())
            reportPosition(position)
        }
        geofences?.plan(Coordinates(location.latitude, location.longitude), force = replan)
    }

    private fun onBeacon(major: Int, minor: Int) {
        if (!tracker.beacon(major, minor)) return
        reportWhenDue()
    }

    /**
     * Reports the latest position once the throttle allows: now, when it's the first or has moved 50
     * metres or a minute has passed; otherwise when the minute is up, with whatever is latest by then.
     */
    private fun reportWhenDue() {
        if (pendingReport?.isActive == true) return
        val position = tracker.current ?: return
        val now = SystemClock.elapsedRealtime()
        if (throttle.isDue(position, now)) {
            send()
            return
        }
        pendingReport = scope.launch {
            delay(throttle.waitMs(now))
            send()
        }
    }

    /** Reports the latest position straight away, for a deliberate change (pretending, or stopping). */
    private fun reportNow(): Job? {
        pendingReport?.cancel()
        return send()
    }

    /** Publishes the latest position and reports it, looking up its locale when it's moved far enough. */
    private fun send(): Job? {
        publish()
        val position = tracker.current ?: return null
        throttle.sent(position, SystemClock.elapsedRealtime())
        return scope.launch {
            refreshLocale()
            reportPosition(position)
        }
    }

    /**
     * Reports the latest position again, so the API knows the device is still where it was (chat
     * checks for a recent one). Joins a report of the same position already in flight.
     */
    suspend fun reportAgain() {
        pendingReport?.cancel()
        publish()
        val position = tracker.current ?: return
        throttle.sent(position, SystemClock.elapsedRealtime())
        reportPosition(position)
    }

    private suspend fun reportPosition(position: Coordinates) {
        try {
            api.reportLocation(position)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HotMess", "Location report failed: ${e.message}")
        }
    }

    private fun hasLocationPermission(): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun servicesEnabled(): Boolean {
        val manager = manager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.isLocationEnabled }.getOrDefault(true)
        } else {
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).any { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        }
    }

    private fun currentAccess(asked: Boolean): LocationAccess = when {
        hasLocationPermission() -> LocationAccess.ALLOWED
        asked || preferences.getBoolean(ASKED_KEY, false) -> LocationAccess.DENIED
        else -> LocationAccess.NOT_REQUESTED
    }.also { if (asked) preferences.edit().putBoolean(ASKED_KEY, true).apply() }

    private val preferences get() = context.getSharedPreferences(SessionStore.PREFERENCES, Context.MODE_PRIVATE)

    /** Remembered so a cold start has a locale before the first fix. */
    private fun restoreLocale(): AppLocale? {
        val id = preferences.getString(LOCALE_ID_KEY, null) ?: return null
        val name = preferences.getString(LOCALE_NAME_KEY, null) ?: return null
        return AppLocale(id, name)
    }

    private fun persist(locale: AppLocale, chosen: Boolean) {
        preferences.edit()
            .putString(LOCALE_ID_KEY, locale.id)
            .putString(LOCALE_NAME_KEY, locale.name)
            .putBoolean(LOCALE_CHOSEN_KEY, chosen)
            .apply()
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 5 * 60 * 1000L
        private const val UPDATE_DISTANCE_M = 100f
        private const val LOCALE_ID_KEY = "localeId"
        private const val LOCALE_NAME_KEY = "localeName"
        private const val LOCALE_CHOSEN_KEY = "localeChosen"
        private const val ASKED_KEY = "locationAsked"
    }
}
