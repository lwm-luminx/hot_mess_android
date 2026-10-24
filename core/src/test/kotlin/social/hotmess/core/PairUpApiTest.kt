package social.hotmess.core

import com.audiencekit.AudienceKitClient
import com.audiencekit.AudienceKitConfiguration
import com.audiencekit.HttpResponse
import com.audiencekit.HttpTransport
import com.audiencekit.InMemoryTokenStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PairUpApiTest {
    private fun api(response: String): HotMessApi {
        val transport = HttpTransport { HttpResponse(200, response.toByteArray()) }
        val client = AudienceKitClient(
            AudienceKitConfiguration(baseUrl = "https://api.example", audienceId = "aud-1"),
            InMemoryTokenStore("token"),
            transport,
        )
        return HotMessApi(client)
    }

    @Test
    fun aRefusedJoinIsShownAsWritten() = runTest {
        // What the API sends someone who hasn't agreed to the terms: the refusal, and the mutation's field nulled.
        val api = api(
            """{"errors":[{"message":"Agree to the community terms before you post.","path":["joinPairUp"],
              "extensions":{"code":"FORBIDDEN","reason":"terms_not_accepted"}}],"data":{"joinPairUp":null}}""",
        )
        val error = assertFailsWith<ApiError.Rejected> { api.joinPairUp("v-1", "red jacket") }
        assertEquals(ApiError.FORBIDDEN, error.code)
        assertEquals("Agree to the community terms before you post.", error.message)
    }
}
