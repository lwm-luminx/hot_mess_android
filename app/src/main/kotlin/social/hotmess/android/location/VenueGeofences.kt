package social.hotmess.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.audiencekit.Coordinates
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import social.hotmess.android.appGraph
import social.hotmess.core.GeofencePlan
import social.hotmess.core.HotMessApi
import social.hotmess.core.VenueFence
import kotlin.coroutines.resume

/**
 * Venue geofences, as iOS's LocationProvider watches them with CLMonitor: Play Services watches the envelopes of the
 * venues nearest the device ([GeofencePlan]) and a re-center circle around it. Crossing a venue's edge reports the
 * position, so the API sees people arrive and leave without GPS staying on; leaving the re-center circle plans again
 * from there. With "Allow all the time" the crossings arrive with Hot Mess closed too ([VenueGeofenceReceiver]);
 * without it they arrive only while it's open, when the foreground fixes cover it anyway.
 *
 * The plan is kept so [VenueGeofenceRestore] can hand it back to Play Services after a restart or an update, which
 * clear geofences. Signing out drops it ([stopAll]), so a signed-out app is never woken for a venue.
 */
class VenueGeofences(private val context: Context, private val api: HotMessApi) {
    private val planning = Mutex()

    private val preferences get() = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private var plan: GeofencePlan?
        get() = GeofencePlan.decode(preferences.getString(PLAN_KEY, null))
        set(value) {
            preferences.edit().apply { if (value == null) remove(PLAN_KEY) else putString(PLAN_KEY, GeofencePlan.encode(value)) }.apply()
        }

    private var fences: List<VenueFence>
        get() = GeofencePlan.decodeFences(preferences.getString(FENCES_KEY, null))
        set(value) {
            preferences.edit().putString(FENCES_KEY, GeofencePlan.encodeFences(value)).putLong(FETCHED_AT_KEY, System.currentTimeMillis()).apply()
        }

    private val fencesFresh: Boolean
        get() = System.currentTimeMillis() - preferences.getLong(FETCHED_AT_KEY, 0L) < GeofencePlan.FENCES_MAX_AGE_MS

    /**
     * Picks the geofences to watch from [position], downloading the venues' envelopes when they're missing or old. Does
     * nothing without precise location, which geofences need, or while the device is still well inside the re-center
     * circle; [force] plans again anyway (after leaving it).
     */
    suspend fun plan(position: Coordinates, force: Boolean = false) {
        if (!hasPreciseLocation(context)) return
        planning.withLock {
            val current = plan
            if (!force && current != null && !current.isStale(position, fencesFresh)) return
            if (!fencesFresh) {
                try {
                    fences = api.venueFences(position)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Venue envelopes failed: ${e.message}")
                    // Try again on the next fix; keep watching what we had.
                    if (current != null) return
                }
            }
            val next = GeofencePlan.plan(position, fences)
            plan = next
            register(context, next)
        }
    }

    /** Signing out: stop watching, and forget the plan so nothing adds it back. */
    fun stopAll() {
        preferences.edit().clear().apply()
        runCatching { LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context)) }
    }

    /** Hands the kept plan back to Play Services, which forgets geofences on a restart, an update or location going off. */
    fun restore() {
        plan?.let { register(context, it) }
    }

    companion object {
        private const val TAG = "HotMess"
        private const val PREFERENCES = "hotmess.geofences"
        private const val PLAN_KEY = "plan"
        private const val FENCES_KEY = "fences"
        private const val FETCHED_AT_KEY = "fencesFetchedAt"

        /** Play Services' geofences need precise location; with approximate only, adding them fails. */
        fun hasPreciseLocation(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Whether crossings arrive with Hot Mess closed: "Allow all the time", from Android 10. */
        fun hasBackgroundLocation(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        /**
         * Replaces whatever Play Services watches for Hot Mess with [plan]. Never throws: it also runs at boot and after
         * updates, when a permission taken away in Settings must not crash the app.
         */
        @SuppressLint("MissingPermission")
        private fun register(context: Context, plan: GeofencePlan) {
            if (!hasPreciseLocation(context)) return
            val geofences = plan.venues.map { fence ->
                Geofence.Builder()
                    .setRequestId(GeofencePlan.requestId(fence))
                    .setCircularRegion(fence.latitude, fence.longitude, maxOf(fence.radius, GeofencePlan.MINIMUM_FENCE_RADIUS).toFloat())
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            } + Geofence.Builder()
                .setRequestId(GeofencePlan.RECENTER_ID)
                .setCircularRegion(plan.recenterLatitude, plan.recenterLongitude, plan.recenterRadius.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
            // No initial trigger: the fix that made the plan was just reported, so being inside a venue already is known.
            val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(geofences).build()
            val client = LocationServices.getGeofencingClient(context)
            val intent = pendingIntent(context)
            try {
                client.removeGeofences(intent).addOnCompleteListener {
                    try {
                        client.addGeofences(request, intent)
                            .addOnSuccessListener { Log.i(TAG, "Watching ${plan.venues.size} venues, re-center radius ${plan.recenterRadius.toInt()}m") }
                            .addOnFailureListener { Log.w(TAG, "Geofences not added: ${it.message}") }
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Geofences not added: ${e.message}")
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Geofences not added: ${e.message}")
            }
        }

        private fun pendingIntent(context: Context): PendingIntent =
            // Play Services fills in the crossing, so the intent must be mutable.
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, VenueGeofenceReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

        /** A fresh fix for a crossing, waiting at most [FIX_TIMEOUT_MS]; null without one. */
        @SuppressLint("MissingPermission")
        suspend fun currentLocation(context: Context): Location? = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationTokenSource()
                continuation.invokeOnCancellation { cancel.cancel() }
                try {
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancel.token)
                        .addOnSuccessListener { continuation.resume(it) }
                        .addOnFailureListener { continuation.resume(null) }
                } catch (e: SecurityException) {
                    continuation.resume(null)
                }
            }
        }

        /** Short enough to report within the ten seconds a broadcast receiver gets. */
        private const val FIX_TIMEOUT_MS = 7_000L
    }
}

/**
 * A venue's edge crossed, or the re-center circle left: read where the device is and report it, so the API sees the
 * arrival or departure; after leaving the re-center circle, plan the geofences again from there.
 */
class VenueGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
        val recentered = GeofencePlan.RECENTER_ID in ids && event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        if (!recentered && ids.none { GeofencePlan.venueId(it) != null }) return
        val graph = context.appGraph
        if (!graph.audienceKit.isSignedIn) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val location = event.triggeringLocation ?: VenueGeofences.currentLocation(context.applicationContext)
                if (location != null) graph.location.reportCrossing(location, replan = recentered)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Android forgets geofences on a restart and on an app update; add the kept ones back. */
class VenueGeofenceRestore : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = context.appGraph
        if (graph.audienceKit.isSignedIn) graph.geofences.restore()
    }
}
