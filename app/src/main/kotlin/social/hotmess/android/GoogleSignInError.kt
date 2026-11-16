package social.hotmess.android

import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException

/**
 * What to tell someone when Google's account sheet fails on their phone, before anything reaches the API. These
 * failures never show up in the API's logs, so the message has to say what happened and what to do instead. Google's
 * sign-in doesn't check whether a phone is rooted, but a rooted or de-Googled phone often has Google Play services
 * missing, old or modified, which shows up here as an unsupported or misconfigured provider.
 */
internal object GoogleSignInError {
    const val OTHER_WAYS = "sign in with Facebook or Apple instead"

    /** [signingIn] is false when connecting Google to an account that's already signed in: no other ways to offer. */
    fun message(error: GetCredentialException, signingIn: Boolean = true): String {
        val otherwise = if (signingIn) ", or $OTHER_WAYS" else ""
        return when (error) {
            is NoCredentialException ->
                "There's no Google account on this phone. Add one in Settings and try again$otherwise."
            is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException ->
                "Google sign-in isn't available on this phone. It needs an up-to-date, unmodified Google Play " +
                    "services, which phones that were rooted or run a custom system often don't have. Update Google " +
                    "Play services and try again$otherwise."
            is GetCredentialInterruptedException -> "Google sign-in was interrupted. Try again."
            else -> "Google sign-in didn't work on this phone (${error.type.substringAfterLast('.')}). " +
                "Try again$otherwise."
        }
    }
}
