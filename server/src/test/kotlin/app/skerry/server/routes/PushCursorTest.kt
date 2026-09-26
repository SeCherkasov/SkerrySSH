package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminPurgeResponse
import app.skerry.server.model.b64
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The device cursor feeds the tombstone watermark, so it may only claim what the device has read.
 * A push returns the account cursor, which also covers other devices' writes this one never pulled.
 */
class PushCursorTest {

    private val accountId = "alice@example.com"
    private val password = "auth-key-hex-abc123"
    private val adminToken = "s3cret"

    private fun record(id: String, version: Long, deviceId: String, deleted: Boolean = false) =
        RecordDto(id, "HOST", version, "2026-09-26T00:00:00Z", deviceId, deleted, byteArrayOf(version.toByte()).b64())

    @Test
    fun `a push does not mark other devices' unread tombstones as seen`() = testApplication {
        val services = testServices(adminToken = adminToken)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount(accountId, password, deviceId = "devA")
        val devB = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")
        client.get("/vault/records?since=0") { bearerAuth(devA.accessToken) }.body<RecordsResponse>()

        // devB creates and deletes r1, and reads everything back.
        client.pushRecord(devB.accessToken, record("r1", 1, "devB"))
        client.pushRecord(devB.accessToken, record("r1", 2, "devB", deleted = true))
        client.get("/vault/records?since=0") { bearerAuth(devB.accessToken) }.body<RecordsResponse>()
        // devA, which never pulled the tombstone, pushes something unrelated.
        assertEquals(HttpStatusCode.OK, client.pushRecord(devA.accessToken, record("r2", 1, "devA")).status)

        val purged: AdminPurgeResponse = client.delete("/admin/accounts/$accountId/tombstones") {
            header("X-Admin-Token", adminToken)
        }.body()
        assertEquals(0, purged.purged, "the tombstone was purged before devA ever read it")
        val pulled: RecordsResponse = client.get("/vault/records?since=0") { bearerAuth(devA.accessToken) }.body()
        assertTrue(pulled.records.single { it.id == "r1" }.deleted)
    }

    @Test
    fun `a push from a caught-up device still advances its cursor`() = testApplication {
        val services = testServices(adminToken = adminToken)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount(accountId, password, deviceId = "devA")
        client.pushRecord(devA.accessToken, record("r1", 1, "devA"))
        client.get("/vault/records?since=0") { bearerAuth(devA.accessToken) }.body<RecordsResponse>()

        client.pushRecord(devA.accessToken, record("r2", 1, "devA"))

        val listed: AdminDevicesResponse = client.get("/admin/devices") { header("X-Admin-Token", adminToken) }.body()
        assertEquals(2L, listed.devices.single { it.id == "devA" }.syncVersion)
    }

    @Test
    fun `a device that never pulled claims nothing when it pushes into an account with history`() = testApplication {
        val services = testServices(adminToken = adminToken)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount(accountId, password, deviceId = "devA")
        client.pushRecord(devA.accessToken, record("r1", 1, "devA"))
        val devB = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")

        client.pushRecord(devB.accessToken, record("r2", 1, "devB"))

        val listed: AdminDevicesResponse = client.get("/admin/devices") { header("X-Admin-Token", adminToken) }.body()
        assertNull(listed.devices.single { it.id == "devB" }.syncVersion, "devB never read r1")
    }
}
