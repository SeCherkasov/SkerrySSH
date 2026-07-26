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

    private fun clientRespondingWith(status: HttpStatusCode, body: String = ""): KtorSyncClient =
        KtorSyncClient(
            serverUrl = "https://sync.example.com",
            http = HttpClient(MockEngine { respond(content = body, status = status) }),
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

    /**
     * A refusal the server chose deliberately (closed registration, a blocked account id) is not a
     * protocol error, and the reason only exists in the server's own message — so it is carried in
     * the exception instead of being flattened to "server responded 403".
     */
    @Test
    fun `a deliberate refusal is its own kind and keeps the server's message`() = runTest {
        assertEquals(SyncException.Kind.FORBIDDEN, kindFor(HttpStatusCode.Forbidden))

        val client = clientRespondingWith(
            HttpStatusCode.Forbidden,
            body = """{"error":"this account id is blocked on this server"}""",
        )
        val failure = assertFailsWith<SyncException> { client.pull(session, since = 0) }
        assertEquals("this account id is blocked on this server", failure.message)
    }

    /**
     * The refusal message is the one response body this client reads, and the server it points at
     * is user-configured — a hostile or broken one must not be able to hand over a megabyte of
     * "message" to hold in memory and paste into the UI.
     */
    @Test
    fun `an oversized refusal body is not read into the message`() = runTest {
        val huge = """{"error":"${"A".repeat(200_000)}"}"""
        val failure = assertFailsWith<SyncException> {
            clientRespondingWith(HttpStatusCode.Forbidden, body = huge).pull(session, since = 0)
        }
        assertEquals(SyncException.Kind.FORBIDDEN, failure.kind)
        assertEquals("server responded 403", failure.message)
    }

    /** A long-but-plausible message is still shown, trimmed to something a UI line can carry. */
    @Test
    fun `a long refusal message is truncated rather than dropped`() = runTest {
        val long = """{"error":"${"blocked ".repeat(80)}"}"""
        val failure = assertFailsWith<SyncException> {
            clientRespondingWith(HttpStatusCode.Forbidden, body = long).pull(session, since = 0)
        }
        assertEquals(SyncClientLimits.MAX_ERROR_MESSAGE_CHARS, failure.message?.length)
    }

    /** A 403 without a decodable body must still fail with a message, not with a parse crash. */
    @Test
    fun `a refusal without a readable body falls back to the status`() = runTest {
        val failure = assertFailsWith<SyncException> {
            clientRespondingWith(HttpStatusCode.Forbidden, body = "<html>nginx</html>").pull(session, since = 0)
        }
        assertEquals(SyncException.Kind.FORBIDDEN, failure.kind)
        assertEquals("server responded 403", failure.message)
    }

    @Test
    fun `anything else stays protocol`() = runTest {
        assertEquals(SyncException.Kind.PROTOCOL, kindFor(HttpStatusCode.BadRequest))
        assertEquals(SyncException.Kind.PROTOCOL, kindFor(HttpStatusCode.MethodNotAllowed))
    }
}
