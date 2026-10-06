package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.db.IncomingRecord
import app.skerry.server.db.sha256Hex
import app.skerry.server.db.signedPolicyFixture
import app.skerry.server.model.b64
import app.skerry.sync.wire.RecordingBulkDeleteRequest
import app.skerry.sync.wire.RecordingBulkDeleteResponse
import app.skerry.sync.wire.RecordingChunkDto
import app.skerry.sync.wire.RecordingListResponse
import app.skerry.sync.wire.RecordingPolicyDto
import app.skerry.sync.wire.RecordingReserveRequest
import app.skerry.sync.wire.TeamCreateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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

class TeamRecordingRoutesTest {
    @Test
    fun `opaque chunk becomes visible only after complete and foreign member sees nothing`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val alice = client.registerAccount("alice@example.com", "auth-key-abc")
        val bob = client.registerAccount("bob@example.com", "auth-key-abc", deviceId = "bob-dev")
        val a = alice.accessToken
        val b = bob.accessToken
        assertEquals(HttpStatusCode.Created, client.post("/teams") {
            bearerAuth(a); contentType(ContentType.Application.Json); setBody(TeamCreateRequest("team-1"))
        }.status)
        services.teamRecords.upsert("team-1", "", listOf(
            IncomingRecord("host-1", "HOST", 1, "2026-07-04T00:00:00Z", "dev-a", false, byteArrayOf(1)),
        ))
        val policy = signedPolicyFixture(services.teams, "alice@example.com", "team-1")
        assertEquals(HttpStatusCode.OK, client.put("/teams/team-1/recording-policy") {
            bearerAuth(a); contentType(ContentType.Application.Json)
            setBody(RecordingPolicyDto(1, 0, 30, policy.ciphertext.b64(), policy.signature.b64()))
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/team-1/recording-policy") { bearerAuth(b) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/teams/team-1/recordings/rec-1") { bearerAuth(b) }.status)
        val bytes = ByteArray(40) { it.toByte() }
        val reserve = RecordingReserveRequest("rec-1", "host-1", 0, ByteArray(40).b64(),
            ByteArray(40).b64(), listOf(RecordingChunkDto(0, bytes.size, sha256Hex(bytes))), 9)
        assertEquals(HttpStatusCode.Created, client.post("/teams/team-1/recordings") {
            bearerAuth(a); contentType(ContentType.Application.Json); setBody(reserve)
        }.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/teams/team-1/recordings/rec-1/complete") { bearerAuth(a) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/teams/team-1/recordings") {
            bearerAuth(a); contentType(ContentType.Application.Json); setBody(reserve.copy(recordingId = "rec-2", hostId = "missing"))
        }.status)
        assertEquals(HttpStatusCode.Conflict, client.post("/teams/team-1/recordings") {
            bearerAuth(a); contentType(ContentType.Application.Json); setBody(reserve.copy(durationSec = 100))
        }.status)
        assertEquals(HttpStatusCode.PayloadTooLarge, client.put("/teams/team-1/recordings/rec-1/chunks/0") {
            bearerAuth(a); contentType(ContentType.Application.OctetStream); setBody(ByteArray(39))
        }.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/teams/team-1/recordings/rec-1/chunks/0") {
            bearerAuth(a); contentType(ContentType.Text.Plain); setBody(bytes)
        }.status)
        assertEquals(0, client.get("/teams/team-1/recordings") { bearerAuth(a) }
            .body<RecordingListResponse>().recordings.size)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/team-1/recordings/rec-1") { bearerAuth(b) }.status)
        assertEquals(HttpStatusCode.Created, client.put("/teams/team-1/recordings/rec-1/chunks/0") {
            bearerAuth(a); contentType(ContentType.Application.OctetStream); setBody(bytes)
        }.status)
        assertEquals(HttpStatusCode.OK, client.post("/teams/team-1/recordings/rec-1/complete") { bearerAuth(a) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/teams/team-1/recordings/rec-1/complete") { bearerAuth(a) }.status)
        val listed = client.get("/teams/team-1/recordings") { bearerAuth(a) }.body<RecordingListResponse>().recordings
        assertEquals("alice@example.com", listed.single().actorId)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/team-1/recordings/rec-1") { bearerAuth(b) }.status)
        verifyBulkDelete(client, a, b)
    }

    private suspend fun verifyBulkDelete(client: HttpClient, a: String, b: String) {
        assertEquals(HttpStatusCode.BadRequest, client.post("/teams/team-1/recordings/delete") {
            bearerAuth(a); contentType(ContentType.Application.Json); setBody(RecordingBulkDeleteRequest(emptyList()))
        }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/teams/team-1/recordings/delete") {
            bearerAuth(b); contentType(ContentType.Application.Json); setBody(RecordingBulkDeleteRequest(listOf("rec-1")))
        }.status)
        assertEquals(1, client.post("/teams/team-1/recordings/delete") {
            bearerAuth(a); contentType(ContentType.Application.Json); setBody(RecordingBulkDeleteRequest(listOf("rec-1", "missing")))
        }.body<RecordingBulkDeleteResponse>().deleted)
        assertEquals(HttpStatusCode.NotFound, client.get("/teams/team-1/recordings/rec-1/chunks/0") { bearerAuth(a) }.status)
    }
}
