package app.skerry.shared.sync

import app.skerry.shared.io.ResponseSizeLimit
import app.skerry.shared.sync.KtorSyncClient.Companion.syncClientConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The sync server is user-configured and not trusted. An oversized answer must fail as a protocol
 * error rather than be held in memory whole, and it must not read as "network down".
 */
class SyncResponseSizeTest {

    private val session = SyncSession(accountId = "a@example.com", accessToken = "t", refreshToken = "r")

    private fun clientAnswering(status: HttpStatusCode, body: String) = KtorSyncClient(
        serverUrl = "https://sync.example.com",
        http = HttpClient(
            MockEngine {
                respond(
                    // A channel, not a string: MockEngine would otherwise declare the length.
                    content = ByteReadChannel(body.encodeToByteArray()),
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        ) { syncClientConfig(maxResponseBytes = LIMIT) },
    )

    @Test
    fun `a pull past the response cap is a protocol error`() = runTest {
        // A well-formed pull, padded past the cap with a field the client ignores.
        val body = """{"records":[],"cursor":1,"padding":"${"A".repeat(LIMIT.toInt())}"}"""
        val failure = assertFailsWith<SyncException> { clientAnswering(HttpStatusCode.OK, body).pull(session, since = 0) }
        assertEquals(SyncException.Kind.PROTOCOL, failure.kind)
    }

    @Test
    fun `an oversized refusal is a protocol error too`() = runTest {
        val body = """{"error":"${"A".repeat(LIMIT.toInt())}"}"""
        val failure = assertFailsWith<SyncException> { clientAnswering(HttpStatusCode.Forbidden, body).pull(session, since = 0) }
        assertEquals(SyncException.Kind.PROTOCOL, failure.kind)
    }

    @Test
    fun `an oversized share listing is a protocol error too`() = runTest {
        val body = """{"shares":[],"padding":"${"A".repeat(LIMIT.toInt())}"}"""
        val failure = assertFailsWith<SyncException> { clientAnswering(HttpStatusCode.OK, body).listShares(session, "team") }
        assertEquals(SyncException.Kind.PROTOCOL, failure.kind)
    }

    @Test
    fun `a status refusal carries its status and an oversized answer does not`() = runTest {
        val refused = assertFailsWith<SyncException> { clientAnswering(HttpStatusCode.BadRequest, "{}").pull(session, since = 0) }
        assertEquals(400, refused.status)
        val oversized = """{"records":[],"cursor":1,"padding":"${"A".repeat(LIMIT.toInt())}"}"""
        assertEquals(null, assertFailsWith<SyncException> { clientAnswering(HttpStatusCode.OK, oversized).pull(session, since = 0) }.status)
    }

    @Test
    fun `a pull within the cap goes through`() = runTest {
        val result = clientAnswering(HttpStatusCode.OK, """{"records":[],"cursor":7}""").pull(session, since = 0)
        assertEquals(7, result.cursor)
    }

    @Test
    fun `the default client carries the cap`() {
        val http = KtorSyncClient.defaultHttpClient()
        try {
            assertNotNull(http.pluginOrNull(ResponseSizeLimit))
        } finally {
            http.close()
        }
    }

    private companion object {
        const val LIMIT = 4096L
    }
}
