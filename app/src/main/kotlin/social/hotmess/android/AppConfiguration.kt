package social.hotmess.android

import android.content.Context
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.AudienceKitEnvironment

/** The settings each build type bakes in (see app/build.gradle.kts), like the iOS xcconfig files. */
data class AppConfiguration(
    val baseUrl: String = BuildConfig.API_BASE_URL,
    val audienceHost: String = BuildConfig.AUDIENCE_HOST,
    val audienceId: String? = BuildConfig.AUDIENCE_ID.ifBlank { null },
    val facebookAppId: String = BuildConfig.FACEBOOK_APP_ID,
    /** The Facebook app's client token, which the Facebook SDK needs to sign in through the Facebook app. */
    val facebookClientToken: String = BuildConfig.FACEBOOK_CLIENT_TOKEN,
    val googleServerClientId: String = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
    /** The Services ID "Continue with Apple" opens Apple's web sign-in with; empty for none. */
    val appleServicesId: String = BuildConfig.APPLE_SERVICES_ID,
    /** The Login for Business configuration, for a build that signs in with a Business-type app. */
    val facebookLoginConfigId: String? = BuildConfig.FACEBOOK_LOGIN_CONFIG_ID.ifBlank { null },
    /** Signs in through an ephemeral Custom Tab that doesn't share Chrome's Facebook cookies (Sign-in Test). */
    val privateSignIn: Boolean = BuildConfig.PRIVATE_SIGN_IN,
    /** Staging builds tell the API so, which then signs people in with Hot Mess's staging Facebook app. */
    val environment: AudienceKitEnvironment =
        AudienceKitEnvironment.entries.firstOrNull { it.value == BuildConfig.AUDIENCEKIT_ENVIRONMENT }
            ?: AudienceKitEnvironment.PRODUCTION,
    val beaconUuid: String? = BuildConfig.BEACON_UUID.ifBlank { null },
    /** The New Relic mobile app's token; no token, no agent. */
    val newRelicToken: String? = BuildConfig.NEW_RELIC_TOKEN.ifBlank { null },
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    /** Staging and debug builds, which get testing aids such as pretending to be at a venue. */
    val isTestBuild: Boolean = BuildConfig.DEBUG || environment != AudienceKitEnvironment.PRODUCTION,
) {
    /** The two APIs a Next build can talk to: it starts on [NEXT], and testers switch to [PRODUCTION] in Me. */
    enum class ApiServer(val title: String, val baseUrl: String, val audienceHost: String) {
        NEXT("Next", "https://api.next.audiencekit.com", "hotmess.admin.next.audiencekit.com"),
        PRODUCTION("Production", "https://api.audiencekit.com", "hotmess.admin.audiencekit.com"),
    }

    /**
     * Staging (Next) builds can switch API. Release is locked to production, and Debug keeps its local API. The
     * environment (and so the Facebook app) stays the build's: both APIs share one database, so the audience's
     * staging app answers on either.
     */
    val canSwitchApiServer: Boolean
        get() = environment != AudienceKitEnvironment.PRODUCTION

    /** Which of the two APIs [baseUrl] is, or null for another (a local API). */
    val apiServer: ApiServer?
        get() = ApiServer.entries.firstOrNull { it.baseUrl == baseUrl }

    /** This build pointed at [server]. */
    fun with(server: ApiServer): AppConfiguration = copy(baseUrl = server.baseUrl, audienceHost = server.audienceHost)

    /** How the AudienceKit SDK reaches the Hot Mess audience. */
    val audienceKit: AudienceKitConfiguration
        get() = AudienceKitConfiguration(
            baseUrl = baseUrl,
            host = audienceHost,
            audienceId = audienceId,
            facebookAppId = facebookAppId,
            environment = environment,
        )

    /** Which Facebook app the build signs in with, for the Me screen. */
    val facebookEnvironment: String
        get() = when (facebookAppId) {
            "1168782378316790" -> "production"
            "713525445368431" -> "AudienceKit platform"
            "1660272792277019" -> "staging"
            "842337999153841" -> "development"
            else -> facebookAppId
        }

    companion object {
        private const val PREFERENCES = "hotmess_api_server"
        private const val KEY = "server"

        /** The build's configuration, on the API picked in Me when the build can switch. */
        fun load(context: Context): AppConfiguration {
            val base = AppConfiguration()
            if (!base.canSwitchApiServer) return base
            val stored = preferences(context).getString(KEY, null)
            val server = ApiServer.entries.firstOrNull { it.name == stored } ?: return base
            return base.with(server)
        }

        /** Keeps [server] for the next launch. Its own preferences file, so "Reset all data" leaves it be. */
        fun store(context: Context, server: ApiServer) {
            preferences(context).edit().putString(KEY, server.name).commit()
        }

        private fun preferences(context: Context) =
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
}
