package social.hotmess.android

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The country of the reader's Play storefront ("US"), which decides whether tip to read without ads can be offered
 * (the SDK's AdFreeTips: a web checkout, US storefronts only). Play Billing is used for nothing else. Null when Play
 * isn't there or doesn't answer, so the tip isn't offered.
 */
object PlayStorefront {
    @Volatile private var cached: String? = null

    suspend fun countryCode(context: Context): String? {
        cached?.let { return it }
        val client = BillingClient.newBuilder(context.applicationContext)
            .setListener { _, _ -> }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        return try {
            withTimeoutOrNull<String?>(5_000) {
                if (!client.connect()) return@withTimeoutOrNull null
                suspendCancellableCoroutine<String?> { continuation ->
                    client.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { result, config ->
                        continuation.resume(config?.countryCode?.takeIf { result.responseCode == BillingClient.BillingResponseCode.OK })
                    }
                }
            }?.also { cached = it }
        } finally {
            client.endConnection()
        }
    }

    private suspend fun BillingClient.connect(): Boolean = suspendCancellableCoroutine { continuation ->
        startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (continuation.isActive) continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
            }

            override fun onBillingServiceDisconnected() {
                if (continuation.isActive) continuation.resume(false)
            }
        })
    }
}
