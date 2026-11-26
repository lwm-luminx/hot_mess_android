package social.hotmess.android.session

import com.audiencekit.Branding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookPermissionsTest {
    @Test
    fun playBuildsAskOnlyForApprovedPermissionsIncludingEmail() {
        assertEquals(listOf("public_profile", "openid", "email"), SessionStore.permissions(asksForReviewed = false))
    }

    @Test
    fun debugAndEmulatorBuildsAlsoAskForFriends() {
        assertEquals(
            listOf("public_profile", "openid", "email", "user_friends"),
            SessionStore.permissions(asksForReviewed = true),
        )
    }

    @Test
    fun asksForWhatBrandingServesForTheBuild() {
        val served = Branding.FacebookScopes(production = listOf("public_profile"), nonProduction = listOf("email"))
        assertEquals(listOf("public_profile"), SessionStore.permissions(served, asksForReviewed = false))
        assertEquals(listOf("email"), SessionStore.permissions(served, asksForReviewed = true))
    }

    @Test
    fun fallsBackToTheBuiltInListsWithoutBranding() {
        assertEquals(
            listOf("public_profile", "openid", "email"),
            SessionStore.permissions(null, asksForReviewed = false),
        )
        assertEquals(
            listOf("public_profile", "openid", "email", "user_friends"),
            SessionStore.permissions(Branding.FacebookScopes(), asksForReviewed = true),
        )
    }

    @Test
    fun recognizesTheEmulator() {
        assertTrue(
            SessionStore.isEmulator(
                "google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.005/12228598:userdebug/dev-keys", "ranchu",
                "sdk_gphone64_arm64",
            ),
        )
        assertTrue(SessionStore.isEmulator("generic/sdk/generic:9/x:eng/test-keys", "goldfish", "sdk"))
    }

    @Test
    fun aPhoneIsNotTheEmulator() {
        assertFalse(
            SessionStore.isEmulator(
                "google/mustang/mustang:16/BP3A.250905.014/13873947:user/release-keys", "mustang", "mustang",
            ),
        )
        assertFalse(SessionStore.isEmulator(null, null, null))
    }
}
