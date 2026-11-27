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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Venues list a page at a time (venuesPage), its kept first page, and an API from before paging. */
class VenuesPageTest {
    private companion object {
        const val SPOKANE = "5d1c3a52-6f0e-4b8a-9e3f-2a7b9c1d4e60"
    }

    private fun page(vararg ids: String, next: String? = null) = """{"data":{"venues":{"list":{
        "nodes":[${ids.joinToString(",") { """{"id":"$it","name":"Venue $it","locale":{"id":"l1","name":"Spokane"}}""" }}],
        "pageInfo":{"hasNextPage":${next != null},"endCursor":${next?.let { "\"$it\"" } ?: "\"last\""}}}}}}"""

    private val fullList = """{"data":{"venues":{"list":{"nodes":[
        {"id":"v1","name":"Nyne","order":1,"locale":{"id":"$SPOKANE","name":"Spokane"}},
        {"id":"v2","name":"Hidden","hidden":true,"locale":{"id":"$SPOKANE","name":"Spokane"}},
        {"id":"v3","name":"Neighbours","locale":{"id":"l2","name":"Seattle"}}]}}}}"""

    private val refused = """{"errors":[{"message":"Field 'list' doesn't accept argument 'localeId'"}]}"""

    private fun api(cache: InMemoryGraphQLCache = InMemoryGraphQLCache(), transport: HttpTransport) = HotMessApi(
        AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
            cache = cache,
            defaultCachePolicy = GraphQLCachePolicy.NetworkFirst(),
        ),
    )

    @Test
    fun asksAPageAtATimeForTheCity() = runTest {
        val bodies = mutableListOf<String>()
        val api = api(transport = HttpTransport { request ->
            val body = request.body!!.toString(Charsets.UTF_8)
            bodies += body
            HttpResponse(200, (if ("\"after\":null" in body) page("v1", "v2", next = "c2") else page("v3")).toByteArray())
        })

        val first = api.venuesPage("l1")
        assertEquals(listOf("v1", "v2"), first.venues.map { it.id })
        assertEquals("c2", first.nextCursor)
        val second = api.venuesPage("l1", after = first.nextCursor)
        assertEquals(listOf("v1", "v2", "v3"), (first + second).venues.map { it.id })
        assertNull(second.nextCursor)

        assertTrue(bodies.all { "VenuesPage" in it && "\"localeId\":\"l1\"" in it && "\"first\":$VENUES_PAGE_SIZE" in it })
        assertTrue("\"after\":\"c2\"" in bodies[1])
    }

    @Test
    fun keepsEachFirstPageForTheNextLaunch() = runTest {
        val cache = InMemoryGraphQLCache()
        val online = api(cache, HttpTransport { HttpResponse(200, page("v1", next = "c2").toByteArray()) })
        assertNull(online.cachedVenuesPage("l1"))
        online.venuesPage("l1")

        val offline = api(cache, HttpTransport { throw IOException("offline") })
        assertEquals(listOf("v1"), offline.cachedVenuesPage("l1")?.venues?.map { it.id })
        assertEquals("c2", offline.cachedVenuesPage("l1")?.nextCursor)
        // Everywhere's first page is its own.
        assertNull(offline.cachedVenuesPage(null))
    }

    @Test
    fun anApiFromBeforePagingSendsTheWholeListOnce() = runTest {
        val api = api(transport = HttpTransport { request ->
            val body = request.body!!.toString(Charsets.UTF_8)
            HttpResponse(200, (if ("VenuesPage" in body) refused else fullList).toByteArray())
        })

        val city = api.venuesPage(SPOKANE)
        assertEquals(listOf("v1"), city.venues.map { it.id })
        assertNull(city.nextCursor)
        assertEquals(listOf("v1", "v3"), api.venuesPage(null).venues.map { it.id })
    }

    @Test
    fun aFailingApiIsNotAskedForTheWholeList() = runTest {
        var asked = 0
        val api = api(transport = HttpTransport { asked++; HttpResponse(500, "oops".toByteArray()) })
        assertFailsWith<ApiError> { api.venuesPage("l1") }
        assertEquals(1, asked)
    }

    @Test
    fun looksUpTheVenuesACardPointsAt() = runTest {
        val api = api(transport = HttpTransport {
            HttpResponse(
                200,
                """{"data":{"nodes":[{"id":"v9","name":"Far"},{},null,{"id":"v8","name":"Gone","hidden":true}]}}""".toByteArray(),
            )
        })
        assertEquals(listOf("v9"), api.venuesById(listOf("v9", "e1", "x", "v8")).map { it.id })
        assertEquals(emptyList(), api.venuesById(emptyList()))
    }

    @Test
    fun findsTheCardVenuesNotPagedIn() {
        val id = "0aa6921c-0000-4000-8000-000000000001"
        val have = listOf(Venue(id = id), Venue(id = "v2"))
        assertEquals(
            listOf("v3"),
            missingVenueIds(listOf(id.uppercase(), "V2", "v3", "v3", "v4"), have, asked = setOf("v4")),
        )
    }

    @Test
    fun keepsEachVenueOnceAcrossPages() {
        val merged = VenuePage(listOf(Venue(id = "v1"), Venue(id = "v2")), "c2") + VenuePage(listOf(Venue(id = "V2"), Venue(id = "v3")), null)
        assertEquals(listOf("v1", "v2", "v3"), merged.venues.map { it.id })
        assertNull(merged.nextCursor)
    }
}
