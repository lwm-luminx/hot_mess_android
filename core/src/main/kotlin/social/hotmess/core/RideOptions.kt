package social.hotmess.core

import java.util.Locale

/**
 * What "Get a ride" says and links to. Get help and Need support show one "Get a ride" row; it opens the ride apps
 * the API lists for the region with the pickup set, transit directions, asking a friend to send a ride, and sharing
 * where to be picked up. Nothing here is sent to Hot Mess.
 */
object RideOptions {
    /** "Uber, Lyft, transit, or a friend.", from the apps the region has. */
    fun summary(rides: List<SupportRide>): String {
        val names = rides.map { it.name }
        return if (names.isEmpty()) "Transit, or share where to pick you up." else "${names.joinToString(", ")}, transit, or a friend."
    }

    /** Google Maps directions by transit, from the pickup when the app knows it; the user picks where they're going. */
    fun transitUrl(pickup: RidePickup?): String {
        val origin = pickup?.let { "&origin=${coordinate(it)}" }.orEmpty()
        return "https://www.google.com/maps/dir/?api=1$origin&travelmode=transit"
    }

    /** A map link to the pickup, for a message. */
    fun mapLink(pickup: RidePickup?): String? = pickup?.let { "https://maps.google.com/?q=${coordinate(it)}" }

    /** Where to pick the user up, written out: the venue's name and address, then a map link. */
    fun whereIAm(place: String?, pickup: RidePickup?): String? =
        listOfNotNull(place?.takeIf { it.isNotBlank() }, mapLink(pickup)).takeIf { it.isNotEmpty() }?.joinToString(" ")

    /** For a friend who'll book the ride in their own app: Uber and Lyft both let the payer set someone else as the rider. */
    fun sendMeARide(place: String?, pickup: RidePickup?): String {
        val here = whereIAm(place, pickup)
            ?: return "Can you send me a ride home? In Uber or Lyft you can book it with me as the rider."
        return "Can you send me a ride home? I'm here: $here. In Uber or Lyft you can book it with me as the rider."
    }

    /** "Pick me up here", for another app or a friend who can drive. */
    fun pickMeUp(place: String?, pickup: RidePickup?): String =
        whereIAm(place, pickup)?.let { "Can you pick me up? I'm here: $it" } ?: "Can you pick me up?"

    /** The line under each ride app: where it picks the user up. */
    fun pickupLine(pickup: RidePickup?): String = when {
        pickup == null -> "Opens the app to set your pickup."
        pickup.name != null -> "Pickup at ${pickup.name}."
        else -> "Pickup where you are now."
    }

    private fun coordinate(pickup: RidePickup) = "%.6f,%.6f".format(Locale.US, pickup.latitude, pickup.longitude)
}
