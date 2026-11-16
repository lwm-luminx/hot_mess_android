package social.hotmess.android

import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Google sign-in must never spin for ever, and a failure on the phone must say what to do instead. */
class GoogleSignInTest {
    @Test fun anAnswerFromARecreatedActivityStillReachesTheSignIn() = runTest {
        val wait = GoogleConsent.begin()
        val token = async { GoogleConsent.await(wait) }
        // The activity that launched the consent screen is gone; the new one receives the answer.
        GoogleConsent.finish("emails-token")
        assertEquals("emails-token", token.await())
    }

    @Test fun declinedConsentCarriesOnWithoutEmails() = runTest {
        val wait = GoogleConsent.begin()
        GoogleConsent.finish(null)
        assertNull(GoogleConsent.await(wait))
    }

    @Test fun aConsentScreenThatNeverAnswersGivesUp() = runTest {
        val wait = GoogleConsent.begin()
        assertNull(GoogleConsent.await(wait, timeout = 3.minutes))
        assertTrue(wait.isCompleted)
    }

    @Test fun aNewSignInEndsAnOldWaitThatNeverGotAnAnswer() = runTest {
        val old = GoogleConsent.begin()
        val new = GoogleConsent.begin()
        assertNull(old.await())
        GoogleConsent.finish("t")
        assertEquals("t", new.await())
    }

    @Test fun aPhoneWithoutWorkingPlayServicesIsToldToUseAnotherWay() {
        for (e in listOf(GetCredentialProviderConfigurationException("x"), GetCredentialUnsupportedException("x"))) {
            val message = GoogleSignInError.message(e)
            assertTrue(message, message.contains("Google Play services"))
            assertTrue(message, message.contains(GoogleSignInError.OTHER_WAYS))
        }
    }

    @Test fun noGoogleAccountSaysToAddOne() {
        assertTrue(GoogleSignInError.message(NoCredentialException("x")).contains("Add one in Settings"))
    }

    @Test fun connectingOffersNoOtherSignInWays() {
        assertFalse(GoogleSignInError.message(NoCredentialException("x"), signingIn = false).contains("Facebook"))
    }

    @Test fun anythingElseNamesTheFailure() {
        assertTrue(GoogleSignInError.message(GetCredentialUnknownException("x")).contains("UNKNOWN"))
    }
}
