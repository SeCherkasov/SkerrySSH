package app.skerry.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Status → [SyncException.Kind] mapping. Everything the client can't name lands in PROTOCOL, which
 * the UI renders as "protocol error" — useless for the two failures a self-hosted user actually
 * hits: the server's own rate limiter (429 on register/SRP/pairing) and a restart behind a reverse
 * proxy (502/503). Both must be told apart so the message can say "wait" or "server is down".
 */
class SyncClientStatusMappingTest {

    private fun clientRespondingWith(status: HttpStatusCode): KtorSyncClient =
        KtorSyncClient(
            serverUrl = "https://sync.example.com",
            http = HttpClient(MockEngine { respond(content = "", status = status) }),
        )

    private val session = SyncSession(accountId = "a@example.com", accessToken = "t", refreshToken = "r")

    /** Any request goes through the same status mapping; pull is the simplest one. */
    private suspend fun kindFor(status: HttpStatusCode): SyncException.Kind =
        assertFailsWith<SyncException> { clientRespondingWith(status).pull(session, since = 0) }.kind

    @Test
    fun `rate limiting is its own kind`() = runTest {
        assertEquals(SyncException.Kind.TOO_MANY_REQUESTS, kindFor(HttpStatusCode.TooManyRequests))
    }

    @Test
    fun `the whole 5xx range is one kind`() = runTest {
        // 500 — the server itself; 502/503/504 — a reverse proxy in front of a restarting or absent
        // server. The range is taken whole: a proxy can answer with codes the server never emits
        // (507, 508), and for the user they all mean the same thing — not your fault, retry later.
        assertEquals(SyncException.Kind.SERVER_ERROR, kindFor(HttpStatusCode.InternalServerError))
        assertEquals(SyncException.Kind.SERVER_ERROR, kindFor(HttpStatusCode.BadGateway))
        assertEquals(SyncException.Kind.SERVER_ERROR, kindFor(HttpStatusCode.ServiceUnavailable))
        assertEquals(SyncException.Kind.SERVER_ERROR, kindFor(HttpStatusCode.GatewayTimeout))
        assertEquals(SyncException.Kind.SERVER_ERROR, kindFor(HttpStatusCode.InsufficientStorage))
    }

    @Test
    fun `named statuses keep their existing kinds`() = runTest {
        assertEquals(SyncException.Kind.UNAUTHORIZED, kindFor(HttpStatusCode.Unauthorized))
        assertEquals(SyncException.Kind.NOT_FOUND, kindFor(HttpStatusCode.NotFound))
        assertEquals(SyncException.Kind.CONFLICT, kindFor(HttpStatusCode.Conflict))
        assertEquals(SyncException.Kind.GONE, kindFor(HttpStatusCode.Gone))
    }

    @Test
    fun `anything else stays protocol`() = runTest {
        assertEquals(SyncException.Kind.PROTOCOL, kindFor(HttpStatusCode.BadRequest))
        assertEquals(SyncException.Kind.PROTOCOL, kindFor(HttpStatusCode.MethodNotAllowed))
    }
}
