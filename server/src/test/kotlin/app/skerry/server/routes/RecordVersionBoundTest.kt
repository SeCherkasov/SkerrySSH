package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import app.skerry.sync.wire.TeamCreateRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A record stored at `Long.MAX_VALUE` wins every later write: the next edit is `version + 1`, which
 * overflows to a negative number and loses last-writer-wins for good. Such a record is left out of
 * the push — and only it: refusing the whole batch would let one pinned record stall every later
 * push of the device that edits it.
 */
class RecordVersionBoundTest {

    private val pw = "auth-key-hex-abc123"

    private fun record(id: String, version: Long) =
        RecordDto(id, "HOST", version, "2026-09-26T00:00:00Z", "devA", false, byteArrayOf(1).b64())

    @Test
    fun `a version no edit can follow is left out of an account push`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount("alice@example.com", pw, deviceId = "devA")

        val pushed = client.put("/vault/records") {
            bearerAuth(devA.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(record("pinned", Long.MAX_VALUE), record("edge", MAX_RECORD_VERSION), record("r1", 1))))
        }

        assertEquals(HttpStatusCode.OK, pushed.status)
        val pulled: RecordsResponse = client.get("/vault/records?since=0") { bearerAuth(devA.accessToken) }.body()
        assertEquals(setOf("edge", "r1"), pulled.records.map { it.id }.toSet())
    }

    @Test
    fun `a version no edit can follow is left out of a team push`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val owner = client.registerAccount("owner@x.io", pw, deviceId = "devA")
        client.post("/teams") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest("team-1"))
        }

        val pushed = client.put("/teams/team-1/records") {
            bearerAuth(owner.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(record("pinned", Long.MAX_VALUE), record("r1", 1))))
        }

        assertEquals(HttpStatusCode.OK, pushed.status)
        val pulled: RecordsResponse = client.get("/teams/team-1/records?since=0") { bearerAuth(owner.accessToken) }.body()
        assertEquals(listOf("r1"), pulled.records.map { it.id })
    }
}
