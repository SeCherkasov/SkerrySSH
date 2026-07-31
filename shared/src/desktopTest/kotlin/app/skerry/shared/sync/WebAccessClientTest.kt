package app.skerry.shared.sync

import app.skerry.sync.wire.WebPasswordRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's half of the web-password contract (`/auth/web-password`): read the state, set it, clear
 * it. The screen that drives this is the only place the credential can be set at all, so a request
 * that silently doesn't say what it means — a clear the server reads as "no change", a refusal that
 * returns quietly — leaves the account in a state the user believes they changed.
 */
class WebAccessClientTest {

    private val session = SyncSession(accountId = "a@example.com", accessToken = "access", refreshToken = "refresh")

    private val requests = mutableListOf<HttpRequestData>()

    private fun clientAnswering(
        status: HttpStatusCode = HttpStatusCode.NoContent,
        body: String = "",
    ): KtorSyncClient {
        val engine = MockEngine { request ->
            requests += request
            if (body.isEmpty()) {
                respond(content = "", status = status)
            } else {
                respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
        return KtorSyncClient(
            serverUrl = "https://sync.example.com",
            http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        )
    }

    private fun sentBody(): String = (requests.single().body as TextContent).text

    @Test
    fun `the state comes off the wire, not from a default`() = runTest {
        assertTrue(clientAnswering(HttpStatusCode.OK, """{"enabled":true}""").webAccessEnabled(session))
        requests.clear()
        assertFalse(clientAnswering(HttpStatusCode.OK, """{"enabled":false}""").webAccessEnabled(session))

        val read = requests.single()
        assertEquals(HttpMethod.Get, read.method)
        assertEquals("/auth/web-password", read.url.encodedPath)
        assertEquals("Bearer access", read.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `setting posts the password over the app session`() = runTest {
        clientAnswering().setWebPassword(session, "web-pw-123".toCharArray())

        val sent = requests.single()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("/auth/web-password", sent.url.encodedPath)
        assertEquals("Bearer access", sent.headers[HttpHeaders.Authorization])
        // The credential travels in the body, never in a URL a proxy or a log would keep.
        assertEquals("web-pw-123", Json.decodeFromString<WebPasswordRequest>(sentBody()).password)
        assertFalse(sent.url.toString().contains("web-pw-123"))
    }

    @Test
    fun `clearing sends what the server reads as a clear`() = runTest {
        clientAnswering().clearWebPassword(session)

        // Asserted on the bytes, not on a round-trip decode: `password` has a default, so kotlinx
        // omits it and decoding an empty object yields null however the body was actually shaped.
        // What the server must receive is a body with no password in it — an empty string would be
        // a password it refuses on length, leaving the browser session open.
        assertEquals("{}", sentBody())
        assertEquals(null, Json.decodeFromString<WebPasswordRequest>(sentBody()).password)
    }

    @Test
    fun `a refusal is an exception, not a quiet no-op`() = runTest {
        // 400: the server's length bounds. The screen must be able to say the password wasn't set.
        assertEquals(
            SyncException.Kind.PROTOCOL,
            assertFailsWith<SyncException> {
                clientAnswering(HttpStatusCode.BadRequest).setWebPassword(session, "short".toCharArray())
            }.kind,
        )
        assertEquals(
            SyncException.Kind.UNAUTHORIZED,
            assertFailsWith<SyncException> {
                clientAnswering(HttpStatusCode.Unauthorized).clearWebPassword(session)
            }.kind,
        )
        assertEquals(
            SyncException.Kind.UNAUTHORIZED,
            assertFailsWith<SyncException> {
                clientAnswering(HttpStatusCode.Unauthorized).webAccessEnabled(session)
            }.kind,
        )
    }

    @Test
    fun `a web session is refused by the server, and that reaches the caller`() = runTest {
        // The guard on the server answers 403 to a browser trying to rotate the credential. It
        // cannot happen from the app, but the kind must not be flattened into "protocol error".
        val e = assertFailsWith<SyncException> {
            clientAnswering(HttpStatusCode.Forbidden).setWebPassword(session, "web-pw-123".toCharArray())
        }
        assertEquals(SyncException.Kind.FORBIDDEN, e.kind)
    }

    @Test
    fun `a 2xx that is not 204 is still success`() = runTest {
        // The route answers 204; treating anything but that exact code as a failure would make the
        // screen report an error for a change that landed.
        val client = KtorSyncClient(
            serverUrl = "https://sync.example.com",
            http = HttpClient(MockEngine { requests += it; respondOk() }) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )
        client.setWebPassword(session, "web-pw-123".toCharArray())
    }
}
