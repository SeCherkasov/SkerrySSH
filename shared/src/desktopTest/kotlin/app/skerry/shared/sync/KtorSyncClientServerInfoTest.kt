package app.skerry.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the Teams screen's Server card and member table read off the wire. Both fields are optional
 * on the server side (an instance older than them simply omits them), so the client's contract is
 * that a missing field degrades to "unknown" rather than failing the whole call.
 */
class KtorSyncClientServerInfoTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(body: String): KtorSyncClient {
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return KtorSyncClient(
            serverUrl = "https://sync.example.com",
            http = HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } },
        )
    }

    private val session = SyncSession("a@example.com", "at", "rt")

    @Test
    fun `the account summary carries the instance version and its device count`() = runTest {
        val summary = client(
            """{"accountId":"a@example.com","createdAt":1,"syncSeq":7,"devices":9,"activeDevices":8,
                "records":41,"tombstones":2,"storageBytes":4096,"lastSeenAt":1754300000000,"serverVersion":"0.2.1"}""",
        ).accountSummary(session)

        assertEquals("/account/summary", requests.single().url.encodedPath)
        assertEquals(9, summary.devices)
        assertEquals(8, summary.activeDevices)
        assertEquals(4096L, summary.storageBytes)
        assertEquals("0.2.1", summary.serverVersion)
    }

    @Test
    fun `a server that reports no version yields an empty one instead of failing`() = runTest {
        val summary = client(
            """{"accountId":"a@example.com","createdAt":1,"syncSeq":0,"devices":1,"activeDevices":1,
                "records":0,"tombstones":0,"storageBytes":0,"lastSeenAt":null}""",
        ).accountSummary(session)

        assertEquals("", summary.serverVersion)
        assertEquals(1, summary.devices)
    }

    @Test
    fun `members carry the last time each was seen, and null on a server that doesn't track it`() = runTest {
        val members = client(
            """{"members":[
                {"accountId":"a@example.com","role":"owner","status":"active","createdAt":1,"lastSeenAt":1754300000000},
                {"accountId":"b@example.com","role":"viewer","status":"invited","createdAt":2}]}""",
        ).members(session, "team-1")

        assertEquals(1754300000000L, members.first().lastSeenAt)
        assertNull(members.last().lastSeenAt)
    }
}
