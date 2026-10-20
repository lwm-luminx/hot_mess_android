import java.time.Instant
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.util.Base64
import java.util.Properties

// AGP 9 compiles Kotlin itself (built-in Kotlin), so there's no separate Kotlin Android plugin.
plugins {
    id("com.android.application") // on the root build classpath; see ../build.gradle.kts
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets that must not be committed (Firebase's app settings) come from local.properties or Gradle
// properties, e.g. `hotmess.firebase.apiKey=…`.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String =
    providers.gradleProperty("hotmess.$name").orNull ?: localProperties.getProperty("hotmess.$name") ?: ""

// A ValueSource makes Gradle recheck the clock even when configuration caching is enabled.
// Seconds since 2026-01-01 UTC keep local and CI builds ordered without modifying tracked files.
abstract class AutomaticBuildNumber : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = (Instant.now().epochSecond - 1767225600L).toString()
}

val buildNumberText = providers.gradleProperty("hotmess.buildNumber")
    .orElse(providers.environmentVariable("BUILD_NUMBER"))
    .orElse(providers.of(AutomaticBuildNumber::class) {})
    .get()
val buildNumber = buildNumberText.toIntOrNull()
    ?.takeIf { it in 1..2100000000 }
    ?: error("Build number must be an integer between 1 and 2100000000; got '$buildNumberText'")

/** The settings that differ between Debug, Staging and Release, like the iOS app's xcconfig files. */
data class Environment(
    val name: String,
    val apiBaseUrl: String,
    val audienceId: String,
    val facebookAppId: String,
    /**
     * Staging calls the next preview (api.next.audiencekit.com), which runs the API as staging and signs people in
     * with Hot Mess's staging Facebook app. Debug runs against a local API.
     */
    val audienceKitEnvironment: String = "production",
    /**
     * The Facebook app's client token (its dashboard's Settings > Advanced), which the Facebook SDK needs to sign in
     * through the Facebook app. It ships in the app, so it isn't a secret. Without one, sign-in uses the Custom Tab.
     */
    val facebookClientToken: String = "",
    /** The Login for Business configuration, for a Business-type Facebook app. */
    val facebookLoginConfigId: String = "",
    /**
     * Opens Facebook's dialog in an ephemeral Custom Tab, which doesn't share Chrome's cookies, so
     * whoever is signed in to facebook.com in Chrome doesn't get in the sign-in test's way.
     */
    val privateSignIn: Boolean = false,
    /**
     * Names the Hot Mess audience to the API for sign-in and branding: its console host on that API's platform.
     * hotmess.admin.audiencekit.com resolves by subdomain; switch to hotmess.social once that domain is verified.
     */
    val audienceHost: String = "hotmess.admin.audiencekit.com",
    val googleServerClientId: String = "",
)

val environments = mapOf(
    // The emulator reaches the host's localhost at 10.0.2.2.
    "debug" to Environment("debug", "http://10.0.2.2:3000", "", "842337999153841"),
    "staging" to Environment("staging", "https://api.next.audiencekit.com", "", "1660272792277019", "staging",
        facebookClientToken = "5d40b408dee01b2985755736fd046296", audienceHost = "hotmess.admin.next.audiencekit.com"),
    // Debug for the sign-in test: Hot Mess's development app against the local API. The app is in development
    // mode, so the test signs its account (which needs a role on the app) in to Chrome first, and sign-in shares
    // Chrome's session rather than using a private tab.
    "signInTest" to Environment("signInTest", "http://10.0.2.2:3000", "", "2967605370250663"),
    "release" to Environment("release", "https://api.audiencekit.com", "b0f8b66a-e636-495d-9475-0f5317ea08e0", "1168782378316790",
        facebookClientToken = "ee4e193e42709c8aaf8f3aa6b383feab"),
)

android {
    namespace = "social.hotmess.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "social.hotmess.android"
        minSdk = 28
        targetSdk = 36
        versionCode = buildNumber
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The Facebook sign-in test's test user, from the environment (scripts/facebook-signin-test.sh
        // loads them). Base64 so a password's symbols survive `am instrument`'s command line.
        mapOf(
            "fbEmail" to "FB_TEST_ANDROID_EMAIL",
            "fbPassword" to "FB_TEST_ANDROID_PASSWORD",
            "fbName" to "FB_TEST_ANDROID_NAME",
            "fbAppId" to "FB_TEST_APP_ID",
        ).forEach { (argument, variable) ->
            providers.environmentVariable(variable).orNull?.takeIf { it.isNotEmpty() }?.let {
                testInstrumentationRunnerArguments[argument] = Base64.getEncoder().encodeToString(it.toByteArray())
            }
        }

        // Venues' iBeacons; their major value names a locale.
        buildConfigField("String", "BEACON_UUID", "\"1422F585-E729-49E3-9E1F-B943DE12BAA9\"")
        // Push needs a Firebase app. Leave these empty and the app runs without push.
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${secret("firebase.projectId")}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${secret("firebase.applicationId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${secret("firebase.apiKey")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${secret("firebase.senderId")}\"")
    }

    // Release builds are signed with the Play upload key, kept outside the repository: `hotmess.upload.storeFile`,
    // `.storePassword`, `.keyAlias` and `.keyPassword` in local.properties. Without them Release is unsigned.
    // Google Play re-signs what it ships with the app signing key it manages.
    val uploadStoreFile = secret("upload.storeFile")
    if (uploadStoreFile.isNotEmpty()) {
        signingConfigs.create("upload") {
            storeFile = file(uploadStoreFile.replaceFirst(Regex("^~"), System.getProperty("user.home")))
            storePassword = secret("upload.storePassword")
            keyAlias = secret("upload.keyAlias").ifEmpty { "upload" }
            keyPassword = secret("upload.keyPassword").ifEmpty { secret("upload.storePassword") }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".development"
            versionNameSuffix = "-development"
        }
        getByName("release") {
            // The Play listing's package.
            applicationIdSuffix = ".app"
            signingConfigs.findByName("upload")?.let { signingConfig = it }
            // R8 shrinks, optimizes and obfuscates the code (Google Play flags apps under 25% obfuscation) and drops
            // unused resources. The bundle carries R8's mapping file, which Play uses to deobfuscate crash reports;
            // app/build/outputs/mapping/release/mapping.txt retraces an error report's stack trace by hand.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("signInTest") {
            initWith(getByName("staging"))
            applicationIdSuffix = ".signintest"
            versionNameSuffix = "-signintest"
            // The UI tests' build: instrumentation tests run against unminified code.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    // UI tests sign in against the real API, so they run on Sign-in Test: Staging in a private Custom Tab.
    testBuildType = "signInTest"
    // It looks like Staging.
    sourceSets.getByName("signInTest").res.srcDir("src/staging/res")

    buildTypes.configureEach {
        val environment = environments.getValue(name)
        // scripts/ui-tests.py runs the sign-in test against the API it starts: `-Photmess.testApiUrl=…`.
        val apiBaseUrl = providers.gradleProperty("hotmess.testApiUrl").orNull?.takeIf { name == "signInTest" }
            ?: environment.apiBaseUrl
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "AUDIENCE_HOST", "\"${environment.audienceHost}\"")
        buildConfigField("String", "AUDIENCE_ID", "\"${environment.audienceId}\"")
        buildConfigField("String", "FACEBOOK_APP_ID", "\"${environment.facebookAppId}\"")
        buildConfigField("String", "FACEBOOK_CLIENT_TOKEN", "\"${environment.facebookClientToken}\"")
        buildConfigField("String", "FACEBOOK_LOGIN_CONFIG_ID", "\"${environment.facebookLoginConfigId}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"${environment.googleServerClientId.ifEmpty { secret("google.serverClientId") }}\"")
        buildConfigField("boolean", "PRIVATE_SIGN_IN", environment.privateSignIn.toString())
        buildConfigField("String", "AUDIENCEKIT_ENVIRONMENT", "\"${environment.audienceKitEnvironment}\"")
        resValue(
            "string",
            "app_name",
            when (environment.name) {
                "debug" -> "Hot Mess Dev"
                "staging" -> "Hot Mess Staging"
                "signInTest" -> "Hot Mess Sign-in Test"
                else -> "Hot Mess"
            },
        )
        manifestPlaceholders["facebookAppId"] = environment.facebookAppId
        manifestPlaceholders["usesCleartextTraffic"] = apiBaseUrl.startsWith("http:").toString()
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.audiencekit.android)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // Google's AuthorizationClient, for the scope that reads a Google account's other emails.
    implementation(libs.play.services.auth)
    // Sign-in through the Facebook app. Facebook Login brings an old Fragment; the Activity Result API needs 1.3
    // or later.
    implementation(libs.facebook.login)
    implementation(libs.androidx.fragment)
    implementation(libs.maplibre)
    implementation(libs.firebase.messaging)
    // Cover charge: Stripe's payment sheet or Square's card entry takes the payment.
    implementation(libs.stripe.android)
    implementation(libs.square.card.entry)
    // Pair me up: scanning a match's QR code (decoded with the core module's ZXing reader).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.uiautomator)
}
