package social.hotmess.android.session

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.facebook.FacebookSdk
import social.hotmess.android.AppConfiguration

/**
 * Sign-in through the Facebook app, with the Facebook SDK's native login, like the iOS app's switch to Facebook. It
 * hands back an access token, which the API checks against the build's Facebook app.
 *
 * Facebook only accepts the switch from a build whose signing key hash is on the Facebook app's Android platform,
 * with the build's package name and `social.hotmess.android.MainActivity` as its class.
 *
 * Builds without a client token for their Facebook app, Login for Business builds and the Sign-in Test (which needs
 * a private tab) keep the Custom Tab dialog, as does anyone without the Facebook app.
 */
object FacebookAppSignIn {
    private const val FACEBOOK_APP = "com.facebook.katana"

    /**
     * Starts the Facebook SDK, which the manifest keeps from starting itself: it refuses to start without a client
     * token, and only collects app events or advertising ids if told to, which it isn't.
     */
    fun initialize(context: Context, configuration: AppConfiguration) {
        if (!isConfigured(configuration)) return
        try {
            FacebookSdk.setApplicationId(configuration.facebookAppId)
            FacebookSdk.setClientToken(configuration.facebookClientToken)
            FacebookSdk.setAutoLogAppEventsEnabled(false)
            FacebookSdk.setAdvertiserIDCollectionEnabled(false)
            @Suppress("DEPRECATION")
            FacebookSdk.sdkInitialize(context.applicationContext)
        } catch (e: Exception) {
            Log.e("HotMess", "The Facebook SDK didn't start: ${e.message}")
        }
    }

    /** Whether sign-in can go through the Facebook app on this phone. */
    fun isAvailable(context: Context, configuration: AppConfiguration): Boolean =
        isConfigured(configuration) && FacebookSdk.isInitialized() && isFacebookInstalled(context)

    private fun isConfigured(configuration: AppConfiguration): Boolean =
        configuration.facebookAppId.isNotBlank() &&
            configuration.facebookClientToken.isNotBlank() &&
            configuration.facebookLoginConfigId == null &&
            !configuration.privateSignIn

    private fun isFacebookInstalled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(FACEBOOK_APP, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}
