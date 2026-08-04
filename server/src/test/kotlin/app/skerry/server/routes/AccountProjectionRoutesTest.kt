package app.skerry.server.routes

import app.skerry.server.SERVER_VERSION
import app.skerry.server.configureServer
import app.skerry.server.model.AccountActivityResponse
import app.skerry.server.model.HealthResponse
import app.skerry.server.model.VaultEnvelopesResponse
import app.skerry.server.model.b64
import app.skerry.sync.wire.AccountSummaryResponse
import app.skerry.sync.wire.RecordDto
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What the account zone is allowed to read about itself: its own totals, its own audit rows, and the
 * envelopes of its own records. Metadata by construction — the ciphertext preview is a demonstration
 * that the server cannot read what it stores, so it must stay a preview.
 */
class AccountProjectionRoutesTest {

    private val account = "alice@example.com"
    private val password = "pw-hex"

    /** Longer than any preview, so a route that ships the whole blob is caught by length alone. */
    private val bigBlob = ByteArray(512) { (it % 251).toByte() }

    @Test
    fun `the summary counts this account's records, tombstones and ciphertext`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(account, password)
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-07-31T00:00:00Z", "devA", false, ByteArray(100).b64()))
        client.pushRecord(tokens.accessToken, RecordDto("r2", "HOST", 1, "2026-07-31T00:00:00Z", "devA", true, ByteArray(40).b64()))

        val summary: AccountSummaryResponse = client.get("/account/summary") { bearerAuth(tokens.accessToken) }.body()

        assertEquals(account, summary.accountId)
        assertEquals(2, summary.records)
        assertEquals(1, summary.tombstones)
        assertEquals(140L, summary.storageBytes)
        assertEquals(1, summary.devices)
        assertEquals(1, summary.activeDevices)
        assertNotNull(summary.lastSeenAt)
    }

    @Test
    fun `the summary names the server version`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(account, password)

        val summary: AccountSummaryResponse = client.get("/account/summary") { bearerAuth(tokens.accessToken) }.body()

        // The client shows it in the Teams screen's Server card; /admin/health is admin-only, so
        // this is the only place an ordinary member can learn which server they are talking to.
        assertEquals(SERVER_VERSION, summary.serverVersion)
    }

    @Test
    fun `the summary describes the caller, not the instance`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        // The caller registers second on purpose: an unscoped query capped at one row would return
        // the oldest account and every assertion below would still pass by accident.
        val other = client.registerAccount("bob@example.com", password, deviceId = "devB", deviceName = "Laptop B")
        val mine = client.registerAccount(account, password)
        client.pushRecord(other.accessToken, RecordDto("r9", "HOST", 1, "2026-07-31T00:00:00Z", "devB", false, ByteArray(999).b64()))

        val summary: AccountSummaryResponse = client.get("/account/summary") { bearerAuth(mine.accessToken) }.body()

        assertEquals(account, summary.accountId)
        assertEquals(0, summary.records)
        assertEquals(0L, summary.storageBytes)
    }

    @Test
    fun `the account log carries this account's own events and no team rows`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(account, password)
        client.registerAccount("bob@example.com", password, deviceId = "devB", deviceName = "Laptop B")
        services.activity.record(account, "team.invite", "bob@example.com", teamId = "ops-core")

        val log: AccountActivityResponse = client.get("/account/activity") { bearerAuth(tokens.accessToken) }.body()

        assertTrue(log.events.any { it.event == "auth.register" }, "${log.events}")
        // The team feed has its own endpoint; mixing it in here would show a member events that
        // belong to a team's history, filtered by nothing but who happened to act.
        assertFalse(log.events.any { it.event == "team.invite" }, "${log.events}")
        // Nothing about anyone else's account.
        assertEquals(1, log.total)
    }

    @Test
    fun `envelopes are metadata plus a preview, never the record`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(account, password)
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 3, "2026-07-31T00:00:00Z", "devA", false, bigBlob.b64()))

        val body: VaultEnvelopesResponse = client.get("/vault/envelopes") { bearerAuth(tokens.accessToken) }.body()

        val envelope = body.records.single()
        assertEquals("r1", envelope.id)
        assertEquals("HOST", envelope.type)
        assertEquals(3, envelope.version)
        assertEquals(bigBlob.size, envelope.blobBytes)
        // The mapper builds the DTO positionally, and four of its fields are the same shape — a
        // swapped pair would print a device id as a timestamp and pass every assertion above.
        assertEquals("2026-07-31T00:00:00Z", envelope.updatedAt)
        assertEquals("devA", envelope.deviceId)
        assertFalse(envelope.deleted)
        assertEquals(1L, envelope.serverSeq)
        // 16 bytes as "xx " pairs — the console's own preview length. Anything longer is a leak of
        // ciphertext the browser has no business holding.
        assertEquals(16, envelope.previewHex.split(" ").size)
    }

    @Test
    fun `a tombstone is an envelope like any other`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(account, password)
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 1, "2026-07-31T00:00:00Z", "devA", false, ByteArray(64).b64()))
        client.pushRecord(tokens.accessToken, RecordDto("r1", "HOST", 2, "2026-07-31T01:00:00Z", "devA", true, ByteArray(8).b64()))

        val body: VaultEnvelopesResponse = client.get("/vault/envelopes") { bearerAuth(tokens.accessToken) }.body()

        // Deletion markers are part of what the server holds, and the summary counts them; hiding
        // them here would make the Storage section disagree with its own totals.
        assertTrue(body.records.single().deleted, "${body.records}")
    }

    @Test
    fun `deleting the account closes the browser session it was holding`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(account, password)
        assertEquals(HttpStatusCode.OK, client.get("/account/summary") { bearerAuth(tokens.accessToken) }.status)

        // Deleted by an operator while the session was open. The delete takes the devices with it,
        // so the JWT check refuses the token before any route sees it — the projections never get a
        // chance to answer with somebody else's numbers, or with a 500.
        services.admin.deleteAccount(account)

        listOf("/account/summary", "/account/activity", "/vault/envelopes").forEach {
            assertEquals(HttpStatusCode.Unauthorized, client.get(it) { bearerAuth(tokens.accessToken) }.status, it)
        }
    }

    @Test
    fun `the projections are closed without a token`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        listOf("/account/summary", "/account/activity", "/vault/envelopes").forEach {
            assertEquals(HttpStatusCode.Unauthorized, client.get(it).status, it)
        }
    }

    @Test
    fun `the front page cannot exhaust the admin token's budget by being visited`() = testApplication {
        val services = testServices(adminToken = "admin-token")
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // The admin bucket is 30/minute per client; the public page reads health on every load, and
        // behind a proxy every visitor is one client. Health must not spend that budget — otherwise
        // a crawler on `/` locks the operator out of the console and the page calls a live instance
        // unavailable.
        repeat(40) { assertEquals(HttpStatusCode.OK, client.get("/admin/health").status, "visit $it") }
    }

    @Test
    fun `health states whether registration is open`() = testApplication {
        val services = testServices(extraEnv = mapOf("SKERRY_REGISTRATION" to "closed"))
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        val health: HealthResponse = client.get("/admin/health").body()
        assertFalse(health.registrationOpen)
    }

    @Test
    fun `health says so on the wire even when registration is open`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        // Deserializing and reading the field proves nothing here: it would fall back to the default
        // and report `true` for a response that never carried the field at all. The open case is the
        // default case, so it is exactly the one that disappears — read the bytes.
        val raw = client.get("/admin/health").bodyAsText()
        assertTrue("\"registrationOpen\":true" in raw, raw)
    }
}
