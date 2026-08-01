package app.skerry.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's half of pairing. `platform` is optional on the wire, so dropping it from the request
 * still compiles and still pairs — the device simply enrolls without one and both web zones list it
 * as "—". Only an assertion on the bytes actually sent catches that.
 */
class KtorSyncClientPairingTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(): KtorSyncClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = """{"accountId":"a@example.com","encryptedDataKey":"AQID","accessToken":"at","refreshToken":"rt"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return KtorSyncClient(
            serverUrl = "https://sync.example.com",
            http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        )
    }

    private fun sentBody(): String = (requests.single().body as TextContent).text

    @Test
    fun `claiming a pairing reports the platform of this device`() = runTest {
        client().claimPairing("ABC123", DeviceInfo("dev-b", "Phone B", "Android"))

        val body = sentBody()
        assertEquals("/pairing/claim", requests.single().url.encodedPath)
        assertTrue("\"platform\":\"Android\"" in body, "the claim must carry the platform: $body")
    }

    @Test
    fun `a device without a platform claims without inventing one`() = runTest {
        client().claimPairing("ABC123", DeviceInfo("dev-b", "Phone B"))

        val body = sentBody()
        assertFalse("platform" in body, "an absent platform must stay absent, not become a literal: $body")
    }
}
