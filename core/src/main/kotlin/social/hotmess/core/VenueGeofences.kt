package social.hotmess.core

import com.audiencekit.Coordinates
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A venue's envelope: the circle around it that counts as being there. The API buffers the venue's point by its
 * `distanceTolerance`, so the envelope is exactly this circle and Play Services can watch it as a geofence (as iOS's
 * VenueFence does with Core Location).
 */
@Serializable
data class VenueFence(val id: String, val latitude: Double, val longitude: Double, val radius: Double) {
    /** Metres from [position] to the edge of the envelope; negative inside it. */
    fun distanceToEdge(position: Coordinates): Double = position.metersTo(Coordinates(latitude, longitude)) - radius
}

/**
 * Which geofences to watch from a position, as iOS's GeofencePlan: the venues nearest the position and one "re-center"
 * circle around the position itself. The re-center circle stops short of every venue that didn't make the cut, so the
 * device can't walk into an unwatched venue without first leaving it; leaving it is the cue to plan again from there.
 */
@Serializable
data class GeofencePlan(
    val venues: List<VenueFence>,
    val recenterLatitude: Double,
    val recenterLongitude: Double,
    val recenterRadius: Double,
) {
    /**
     * Whether a fix at [position] calls for planning again: no plan yet, envelopes older than [GeofencePlan.FENCES_MAX_AGE_MS],
     * or the device has gone half way to the re-center circle's edge.
     */
    fun isStale(position: Coordinates, fencesFresh: Boolean): Boolean =
        !fencesFresh || position.metersTo(Coordinates(recenterLatitude, recenterLongitude)) >= recenterRadius / 2

    companion object {
        /**
         * Play Services watches at most 100 geofences per app. One is the re-center circle; the rest are venues.
         */
        const val REGION_LIMIT = 100

        /** Smallest re-center circle: geofences much smaller than this miss crossings. */
        const val MINIMUM_RECENTER_RADIUS = 150.0

        /** Largest re-center circle, for when every venue nearby is watched: far enough for the next city. */
        const val MAXIMUM_RECENTER_RADIUS = 25_000.0

        /** Envelopes are downloaded again after six hours, as on iOS. */
        const val FENCES_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        /** Play Services recommends geofences of at least 100 m; smaller ones miss crossings. */
        const val MINIMUM_FENCE_RADIUS = 100.0

        const val RECENTER_ID = "recenter"
        private const val VENUE_PREFIX = "venue:"

        fun plan(around: Coordinates, candidates: List<VenueFence>, regionLimit: Int = REGION_LIMIT): GeofencePlan {
            val sorted = candidates.distinctBy { it.id }.map { it to it.distanceToEdge(around) }.sortedBy { it.second }
            val watched = sorted.take((regionLimit - 1).coerceAtLeast(0))
            val nearestUnwatched = sorted.getOrNull(watched.size)?.second
            return GeofencePlan(
                venues = watched.map { it.first },
                recenterLatitude = around.latitude,
                recenterLongitude = around.longitude,
                recenterRadius = (nearestUnwatched ?: MAXIMUM_RECENTER_RADIUS)
                    .coerceAtLeast(MINIMUM_RECENTER_RADIUS)
                    .coerceAtMost(MAXIMUM_RECENTER_RADIUS),
            )
        }

        /** A venue geofence's request id. */
        fun requestId(fence: VenueFence) = "$VENUE_PREFIX${fence.id}"

        /** The venue a request id watches, or null for the re-center circle or anything else. */
        fun venueId(requestId: String): String? =
            requestId.takeIf { it.startsWith(VENUE_PREFIX) }?.removePrefix(VENUE_PREFIX)?.takeIf { it.isNotBlank() }

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(plan: GeofencePlan): String = json.encodeToString(serializer(), plan)
        fun decode(raw: String?): GeofencePlan? = raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }
        fun encodeFences(fences: List<VenueFence>): String = json.encodeToString(ListSerializer(VenueFence.serializer()), fences)
        fun decodeFences(raw: String?): List<VenueFence> =
            raw?.let { runCatching { json.decodeFromString(ListSerializer(VenueFence.serializer()), it) }.getOrNull() }.orEmpty()
    }
}
