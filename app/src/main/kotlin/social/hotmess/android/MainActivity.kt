package social.hotmess.android

import android.accounts.Account
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.login.LoginBehavior
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import social.hotmess.android.session.FacebookAppSignIn
import social.hotmess.android.session.GoogleAccounts
import social.hotmess.android.session.LocalGoogleAccounts
import social.hotmess.android.ui.HotMessApp
import social.hotmess.android.ui.LocalAppGraph
import social.hotmess.android.ui.components.NotificationBanner
import social.hotmess.android.ui.theme.HotMessTheme
import social.hotmess.core.AppRoute
import social.hotmess.core.DeepLink
import social.hotmess.core.PingPush

class MainActivity : ComponentActivity(), GoogleAccounts {
    private val graph by lazy { appGraph }
    private val links = Channel<AppRoute>(Channel.CONFLATED)
    private val linkFlow = links.receiveAsFlow()

    /** Sign-in through the Facebook app, when this build's Facebook SDK started (see [FacebookAppSignIn]). */
    private var facebookLogin: ActivityResultLauncher<Collection<String>>? = null

    /** Google's consent screen for [EMAILS_SCOPE], and the access token it answers [pendingEmailsToken] with. */
    private lateinit var googleConsent: ActivityResultLauncher<IntentSenderRequest>
    private var pendingEmailsToken: CompletableDeferred<String?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) handle(intent)
        registerFacebookLogin()
        googleConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val token = runCatching {
                Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data).accessToken
            }.getOrNull()
            pendingEmailsToken?.complete(token)
            pendingEmailsToken = null
        }

        setContent {
            val branding by graph.brand.branding.collectAsStateWithLifecycle()
            HotMessTheme(branding = branding?.theme) {
                CompositionLocalProvider(LocalAppGraph provides graph, LocalGoogleAccounts provides this) {
                    Box(Modifier.fillMaxSize()) {
                        HotMessApp(links = linkFlow, onSignIn = ::signIn, onGoogleSignIn = ::signInWithGoogle)
                        // A notification that came over the cable while the app is open; a tap opens it as a push's would.
                        NotificationBanner(graph.notifications) { notification -> openPush(notification.data) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // Square's card entry still starts with startActivityForResult, so its result arrives here.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        graph.squareCardEntry.onActivityResult(requestCode, data)
    }

    override fun onResume() {
        super.onResume()
        // A redirect arrives through onNewIntent first, so still waiting here means the tab was closed.
        graph.session.signInAbandoned()
    }

    private fun signIn() {
        val facebookLogin = facebookLogin
        if (facebookLogin != null && FacebookAppSignIn.isAvailable(this, graph.configuration)) {
            // Forget whoever the SDK last signed in, so a different Facebook account doesn't trip it up.
            LoginManager.getInstance().logOut()
            facebookLogin.launch(graph.session.beginFacebookAppSignIn())
            return
        }
        signInWithFacebookDialog()
    }

    /** Facebook's login dialog in a Custom Tab, for phones without the Facebook app. */
    private fun signInWithFacebookDialog() {
        val url = graph.session.beginSignIn() ?: return
        CustomTabsIntent.Builder()
            .setEphemeralBrowsingEnabled(graph.configuration.privateSignIn)
            .build()
            .launchUrl(this, url.toUri())
    }

    /** Registers sign-in through the Facebook app, which has to happen before the activity starts. */
    private fun registerFacebookLogin() {
        if (!FacebookSdk.isInitialized()) return
        val callbacks = CallbackManager.Factory.create()
        val loginManager = LoginManager.getInstance().setLoginBehavior(LoginBehavior.NATIVE_ONLY)
        loginManager.registerCallback(callbacks, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                graph.session.signInWithFacebookToken(result.accessToken.token)
            }

            override fun onCancel() {
                graph.session.signInCancelled()
            }

            override fun onError(error: FacebookException) {
                Log.e("HotMess", "Facebook app sign-in failed: ${error.message}")
                // Test builds show why (such as a key hash missing from the Facebook app), so it gets fixed; people
                // get the dialog instead.
                if (graph.configuration.isTestBuild) {
                    graph.session.signInFailed(error.message ?: "The Facebook app couldn't sign you in.")
                } else {
                    signInWithFacebookDialog()
                }
            }
        })
        // The contract hands Facebook's result to the callbacks itself.
        facebookLogin = registerForActivityResult(loginManager.createLogInActivityResultContract(callbacks)) {}
    }

    override val available: Boolean get() = graph.configuration.googleServerClientId.isNotBlank()

    private fun signInWithGoogle() {
        if (!available) {
            graph.session.signInFailed("Google sign-in isn't set up for this build yet.")
            return
        }

        graph.session.beginGoogleSignIn()
        lifecycleScope.launch {
            try {
                val credential = googleIdCredential()
                if (credential == null) {
                    graph.session.signInFailed("Unexpected credential type returned from Google.")
                    return@launch
                }
                graph.session.signInWithGoogle(
                    idToken = credential.idToken,
                    displayName = credential.displayName,
                    accessToken = emailsAccessToken(credential.id),
                )
            } catch (e: GetCredentialCancellationException) {
                graph.session.signInCancelled()
            } catch (e: Exception) {
                graph.session.signInFailed(e.message ?: "Google sign-in failed.")
            }
        }
    }

    override suspend fun connect(): String? {
        if (!available) return "Google sign-in isn't set up for this build yet."
        return try {
            val credential = googleIdCredential() ?: return "Unexpected credential type returned from Google."
            graph.session.connectGoogle(credential.idToken)
        } catch (e: GetCredentialCancellationException) {
            ""
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Google sign-in failed."
        }
    }

    /** Google's account sheet: the ID token of the account the person picks, or null for some other credential. */
    private suspend fun googleIdCredential(): GoogleIdTokenCredential? {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(graph.configuration.googleServerClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        val credential = CredentialManager.create(this).getCredential(this, request).credential
        return if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleIdTokenCredential.createFrom(credential.data)
        } else {
            null
        }
    }

    /**
     * An access token for reading the Google account's other emails (its alternate addresses and verified "About me"
     * emails), so the API can find an account that uses one of those rather than the Google account's own address.
     * Google asks once; after that it answers without a screen. Null when the person says no or anything goes wrong:
     * sign-in carries on with the ID token alone.
     */
    private suspend fun emailsAccessToken(email: String): String? = try {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(EMAILS_SCOPE)))
            .setAccount(Account(email, "com.google"))
            .build()
        val result = suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(this).authorize(request)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
        val consent = result?.pendingIntent
        when {
            result == null -> null
            result.hasResolution() && consent != null -> {
                val pending = CompletableDeferred<String?>()
                pendingEmailsToken = pending
                googleConsent.launch(IntentSenderRequest.Builder(consent.intentSender).build())
                pending.await()
            }
            else -> result.accessToken
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("HotMess", "Google didn't share the account's other emails: ${e.message}")
        null
    }

    private fun handle(intent: Intent?) {
        // A tapped push: Firebase's own notification or ours both carry its data (`url` and friends) as extras.
        val extras = intent?.extras
        if (intent != null && extras != null) {
            if (openPush(PingPush.KEYS.associateWith { extras.getString(it) })) {
                PingPush.KEYS.forEach { intent.removeExtra(it) }
                return
            }
        }
        val url = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (graph.session.handleRedirect(url)) return
        val link = DeepLink.link(url) ?: return
        val expiry = link.expiry
        if (expiry == null) {
            links.trySend(link.route)
            return
        }
        // A share link signs when it expires: one that has opens Venues and says so, never the record.
        lifecycleScope.launch {
            val host = link.host ?: graph.configuration.audienceHost
            val expired = graph.api.shareLinkExpired(host, link.path, expiry)
            links.trySend(if (expired) AppRoute.LinkExpired else link.route)
        }
    }

    /**
     * Opens a push by its data: the `url` it carries is the route (a Ping's goes to Now, which reloads). `kind`
     * is analytics only and never decides anything. False when the data has no route the app knows.
     */
    private fun openPush(data: Map<String, String?>): Boolean {
        val route = data[PingPush.URL]?.let(DeepLink::route)
            // A push from before `url` was sent still names its Ping.
            ?: PingPush.parse(data)?.let { AppRoute.NowPing(it.pingId) }
            ?: return false
        if (route is AppRoute.NowPing) graph.pingUpdated(route.pingId)
        links.trySend(route)
        return true
    }

    private companion object {
        /** Reads a Google account's email addresses, for finding an existing account by any of them. */
        const val EMAILS_SCOPE = "https://www.googleapis.com/auth/user.emails.read"
    }
}
