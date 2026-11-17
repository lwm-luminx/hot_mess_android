package social.hotmess.android

import android.content.Context
import com.audiencekit.android.PublisherAdsAcknowledgement

/**
 * Who has acknowledged that publishers run their own ads (the SDK's PublisherAdsCopy), asked before a person's first
 * Soapbox story. Kept per user on this device (com.audiencekit.soapbox.xml, backed up with everything else); a guest's
 * lasts until the app quits since there's no one to record it for.
 */
class PublisherAds(context: Context) {
    private val store = PublisherAdsAcknowledgement(context.applicationContext)

    @Volatile private var guestAcknowledged = false

    fun isAcknowledged(userId: String?): Boolean =
        if (userId == null) guestAcknowledged else store.isAcknowledged(userId)

    fun acknowledge(userId: String?) {
        if (userId == null) guestAcknowledged = true else store.acknowledge(userId)
    }
}
