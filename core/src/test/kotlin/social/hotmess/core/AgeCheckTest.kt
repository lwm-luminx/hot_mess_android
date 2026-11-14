package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgeCheckTest {
    private fun api(response: String, status: Int = 200): HotMessApi {
        val transport = HttpTransport { HttpResponse(status, response.toByteArray()) }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client)
    }

    private val needed = """{"required":true,"region":"GB","state":"PURCHASE_NEEDED","enforced":false,
        "productIds":["social.hotmess.ios.agecheck","social.hotmess.android.app.agecheck"],
        "explainer":{"title":"One quick age check","body":"The law in the United Kingdom asks.","points":["Once."]}}"""

    @Test
    fun aUkStoreIsAskedToBuyTheCheckForThisApp() = runTest {
        val check = api("""{"data":{"reportStoreCountry":{"ageCheck":$needed}}}""").reportStoreCountry("GB")
        assertEquals(AgeCheck.Step.EXPLAIN_AND_BUY, check.step)
        assertEquals("social.hotmess.android.app.agecheck", check.productId("social.hotmess.android.app"))
        assertNull(check.productId("social.hotmess.android.app.next"))
        assertEquals("One quick age check", check.explainer?.title)
    }

    @Test
    fun startingTheCheckHandsBackTheVendorsPage() = runTest {
        val session = api(
            """{"data":{"startAgeCheck":{"url":"https://verify.stripe.com/start/x","provider":"stripe",
              "ageCheck":{"required":true,"region":"AU","state":"IN_PROGRESS"}}}}""",
        ).startAgeCheck("hotmess://age-check")
        assertEquals("https://verify.stripe.com/start/x", session.url)
        assertEquals(AgeCheck.Step.WAIT, session.ageCheck.step)
    }

    @Test
    fun everyStateHasAStepAndAnUnknownOneAsksNothing() {
        val steps = listOf("NOT_REQUIRED", "PURCHASE_NEEDED", "READY", "IN_PROGRESS", "PASSED", "LATER_ADDITION")
            .map { AgeCheck(state = it).step }
        assertEquals(
            listOf(
                AgeCheck.Step.NONE, AgeCheck.Step.EXPLAIN_AND_BUY, AgeCheck.Step.START, AgeCheck.Step.WAIT,
                AgeCheck.Step.DONE, AgeCheck.Step.NONE,
            ),
            steps,
        )
    }

    @Test
    fun anApiFromBeforeAgeChecksAsksNothing() = runTest {
        val old = api("""{"errors":[{"message":"Field 'ageCheck' doesn't exist on type 'UserState'"}]}""")
        assertNull(old.ageCheck())
    }
}
