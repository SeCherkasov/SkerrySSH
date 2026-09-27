package app.skerry.shared.update

import app.skerry.shared.io.ResponseSizeLimit
import app.skerry.shared.io.responseTooLarge
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GithubReleaseClientTest {

    private lateinit var lastRequest: HttpRequestData

    private fun client(status: HttpStatusCode, body: String): HttpClient =
        HttpClient(MockEngine { req ->
            lastRequest = req
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

    private val payload = """{"tag_name": "v0.2.0", "html_url": "https://github.com/SeCherkasov/SkerrySSH/releases/tag/v0.2.0"}"""

    @Test
    fun `fetches and parses the latest release`() = runTest {
        val release = GithubReleaseClient(client(HttpStatusCode.OK, payload)).fetchLatest()

        assertEquals("https://api.github.com/repos/SeCherkasov/SkerrySSH/releases/latest", lastRequest.url.toString())
        assertEquals("application/vnd.github+json", lastRequest.headers[HttpHeaders.Accept])
        assertEquals(ReleaseInfo("v0.2.0", "https://github.com/SeCherkasov/SkerrySSH/releases/tag/v0.2.0"), release)
    }

    @Test
    fun `returns null on a non-success status`() = runTest {
        assertNull(GithubReleaseClient(client(HttpStatusCode.NotFound, """{"message": "Not Found"}""")).fetchLatest())
        assertNull(GithubReleaseClient(client(HttpStatusCode.Forbidden, """{"message": "rate limited"}""")).fetchLatest())
    }

    @Test
    fun `an oversized release answer fails on the cap`() = runTest {
        val http = HttpClient(MockEngine { respond(ByteReadChannel(ByteArray(8192)), HttpStatusCode.OK) }) {
            install(ResponseSizeLimit) { maxBytes = 4096 }
        }
        assertNotNull(assertFails { GithubReleaseClient(http).fetchLatest() }.responseTooLarge())
    }

    @Test
    fun `the default client carries the response cap`() {
        GithubReleaseClient.defaultHttpClient().use { assertNotNull(it.pluginOrNull(ResponseSizeLimit)) }
    }

    @Test
    fun `returns null on a malformed body`() = runTest {
        assertNull(GithubReleaseClient(client(HttpStatusCode.OK, "<html>proxy error</html>")).fetchLatest())
    }
}
