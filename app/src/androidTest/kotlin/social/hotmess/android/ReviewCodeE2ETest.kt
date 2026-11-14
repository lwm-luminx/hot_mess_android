package social.hotmess.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI

/**
 * End to end against a LOCAL fixture API, signed in with an App Review code: Now, an event, a venue, Me and signing out,
 * and a wrong code that stays signed out.
 *
 * Skipped unless the Sign-in Test build points at a local API and a code is given:
 *
 *   ./gradlew :app:connectedSignInTestAndroidTest -Photmess.testApiUrl=http://127.0.0.1:3917 \
 *     -Pandroid.testInstrumentationRunnerArguments.reviewCode=<the local API's AK_REVIEW_CODE> \
 *     -Pandroid.testInstrumentationRunnerArguments.class=social.hotmess.android.ReviewCodeE2ETest
 *
 * after `adb reverse tcp:3917 tcp:3917` (or use http://10.0.2.2:3917 on an emulator). The API: audience_kit_api in
 * RAILS_ENV=test after `rails ui_tests:setup`, which puts the hotmess audience in App Review with The Test Bar. Never
 * api.next.audiencekit.com or api.audiencekit.com (next shares the production database), never the real App Store
 * review code. Nothing here deletes an account, blocks or reports anyone.
 */
@RunWith(AndroidJUnit4::class)
class ReviewCodeE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val code = InstrumentationRegistry.getArguments().getString("reviewCode").orEmpty()

    @Before fun localApiOnly() {
        assumeTrue("Pass the local API's review code as the reviewCode runner argument to run this", code.isNotBlank())
        val host = URI(BuildConfig.API_BASE_URL).host
        assertTrue("This build talks to ${BuildConfig.API_BASE_URL}; e2e runs only against a local API",
            host in setOf("127.0.0.1", "localhost", "10.0.2.2"))
    }

    private fun launch() {
        val context = instrumentation.targetContext
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        // Start signed out, so the code decides who's signed in (a run before may have left a session).
        val tabsOrSignIn = device.wait(Until.findObject(By.text("Venues")), 10_000)
        if (tabsOrSignIn != null && !device.hasObject(By.textContains("Have an App Review code?"))) signOut()
    }

    /** Answers the system's permission prompts: location while using the app, no notifications. */
    private fun answerSystemPrompts() {
        repeat(3) {
            val button = find(By.text("While using the app"), 1_500) ?: find(By.text("Don’t allow"), 500)
                ?: find(By.text("Don't allow"), 500) ?: return
            button.click()
            device.waitForIdle()
        }
    }

    private fun signOut() {
        tab("Me")
        assertNotNull("Me didn't load; on screen: ${screen()}", scrollTo("Sign out"))
        // Tap the row once the list has stopped moving (a swipe's fling can carry it away from a tap), and again if
        // the confirmation didn't open.
        repeat(3) {
            device.waitForIdle()
            find(By.text("Sign out"), 2_000)?.click()
            if (find(By.text("Sign out?"), 5_000) != null) {
                device.findObjects(By.text("Sign out")).last().click()
                return
            }
        }
        need("Sign out?")
    }

    /** What's on screen, for a failure message. */
    private fun screen(): String {
        val out = java.io.ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        return Regex("""(?:text|content-desc)="([^"]+)"""").findAll(out.toString()).map { it.groupValues[1] }
            .distinct().joinToString(" | ")
    }

    private fun find(selector: BySelector, timeout: Long = 20_000): UiObject2? = device.wait(Until.findObject(selector), timeout)

    private fun need(text: String, timeout: Long = 20_000): UiObject2 {
        val found = find(By.textContains(text), timeout)
        assertNotNull("\"$text\" didn't appear; on screen: ${screen()}", found)
        return found!!
    }

    /** Scrolls the screen until `text` shows. */
    private fun scrollTo(text: String): UiObject2? {
        repeat(8) {
            find(By.textContains(text), 1_500)?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 20)
            device.waitForIdle()
        }
        return find(By.textContains(text), 2_000)
    }

    private fun enterCode(code: String) {
        need("Have an App Review code?")
        // The age confirmation: tick "I confirm I am 18 or older" (Compose test tags aren't resource IDs here), and
        // answer the dialog that asks instead when it does.
        find(By.checkable(true), 2_000)?.takeIf { !it.isChecked }?.click()
        need("Have an App Review code?").click()
        find(By.text("I'm 18 or older"), 3_000)?.let { confirm ->
            confirm.click()
            if (find(By.clazz("android.widget.EditText"), 3_000) == null) need("Have an App Review code?").click()
        }
        val field = find(By.clazz("android.widget.EditText"))
        assertNotNull("The review code dialog didn't open", field)
        field!!.text = code
        need("Sign in").click()
    }

    private fun tab(name: String) {
        val tab = find(By.text(name))
        assertNotNull("The $name tab didn't appear", tab)
        tab!!.click()
        device.waitForIdle()
    }

    @Test fun aWrongCodeStaysSignedOut() {
        launch()
        enterCode("WRONG0WRONG0WRONG")
        need("We couldn't sign you in", 30_000)
        need("OK").click()
        assertNotNull("The sign-in screen went away", find(By.textContains("Have an App Review code?")))
        assertFalse(device.hasObject(By.text("Venues")))
    }

    @Test fun theReviewerJourney() {
        launch()
        enterCode(code)
        // Signed in: the tabs, once the location (and notification) prompts are answered.
        device.wait(Until.findObject(By.pkg("com.google.android.permissioncontroller")), 10_000)
        answerSystemPrompts()
        assertNotNull("The review code didn't sign in; on screen: ${screen()}", find(By.text("Venues"), 30_000))
        // An age confirmation can follow a code sign-in on a device that hasn't confirmed yet.
        find(By.text("I'm 18 or older"), 2_000)?.click()

        tab("Now")
        need("Ping")
        assertFalse("Now failed to load", device.hasObject(By.textContains("Something went wrong")))

        tab("Venues")
        val venue = scrollTo("The Test Bar")
        assertNotNull("The Test Bar isn't in Venues", venue)
        venue!!.click()
        assertNotNull("The venue's page showed nothing coming up", scrollTo("Coming up"))
        device.pressBack()

        tab("Events")
        // The fixture's sample nights start tonight at The Test Bar.
        assertFalse("Events failed to load", device.hasObject(By.textContains("Something went wrong")))

        signOut()
        assertNotNull("Signing out didn't return to sign-in", find(By.textContains("Have an App Review code?"), 30_000))
    }
}
