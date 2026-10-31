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
import kotlin.test.assertTrue

class VenueOrderingTest {
    private fun api(ordering: String): Pair<HotMessApi, MutableList<String>> {
        val bodies = mutableListOf<String>()
        val transport = HttpTransport { request ->
            bodies += request.body?.toString(Charsets.UTF_8).orEmpty()
            HttpResponse(200, """{"data":{"venues":{"venue":{"id":"v-1","name":"Stonewall"$ordering}}}}""".toByteArray())
        }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client) to bodies
    }

    @Test
    fun aVenueWithAnOrderingPageGetsAnOrderButton() = runTest {
        val (api, bodies) = api(""","ordering":{"url":"https://order.toasttab.com/online/stonewall"}""")

        assertEquals("https://order.toasttab.com/online/stonewall", api.venueOverview("v-1").orderingUrl)
        assertTrue(bodies.single().contains("ordering { url }"))
    }

    @Test
    fun noOrderingPageOrOneThatIsNotHttpsMeansNoOrderButton() = runTest {
        assertNull(api("").first.venueOverview("v-1").orderingUrl)
        assertNull(api(""","ordering":null""").first.venueOverview("v-1").orderingUrl)
        assertNull(api(""","ordering":{"url":"http://example.com/order"}""").first.venueOverview("v-1").orderingUrl)
    }
}
