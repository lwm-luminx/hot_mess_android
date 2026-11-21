package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.GraphQLCachePolicy
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryGraphQLCache
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Venues screen draws the last list at once (cachedVenues) while a slow API answers. */
class CachedVenuesTest {
    private val body = """{"data":{"venues":{"list":{"nodes":[
        {"id":"v2","name":"The Globe","order":2,"locale":{"id":"l1","name":"Spokane"}},
        {"id":"v1","name":"Nyne","order":1,"locale":{"id":"l1","name":"Spokane"}}]}}}}"""

    private fun api(cache: InMemoryGraphQLCache, transport: HttpTransport) = HotMessApi(
        AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
            cache = cache,
            defaultCachePolicy = GraphQLCachePolicy.NetworkFirst(),
        ),
    )

    @Test
    fun keepsTheLastListForTheNextLaunch() = runTest {
        val cache = InMemoryGraphQLCache()
        val online = api(cache, HttpTransport { HttpResponse(200, body.toByteArray()) })
        assertNull(online.cachedVenues("l1"))
        val fetched = online.venues("l1")

        var asked = 0
        val offline = api(cache, HttpTransport { asked++; throw IOException("offline") })
        assertEquals(fetched.map { it.id }, offline.cachedVenues("l1")?.map { it.id })
        assertEquals(0, asked)
    }
}
