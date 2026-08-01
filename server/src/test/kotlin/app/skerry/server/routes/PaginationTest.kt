package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.AdminAccountsResponse
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminRecordsResponse
import app.skerry.server.model.AccountActivityResponse
import app.skerry.server.model.VaultEnvelopesResponse
import app.skerry.server.model.b64
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.TeamActivityResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paging over the long lists. Every one of them used to answer with "the newest N" and nothing else:
 * a reader could reach the first page and no further, and the page could not say how much it was not
 * showing. These tests pin the two halves of the contract — `offset` walks past the first page, and
 * `total` counts the whole list rather than the page.
 */
class PaginationTest {

    private val accountId = "pager@example.com"
    private val password = "auth-key-hex-abc123"
    private val admin = "s3cret"

    /** Pushes [n] records so a list has something to page through; ids are ordered r-000, r-001, … */
    private suspend fun io.ktor.client.HttpClient.pushRecords(token: String, n: Int, from: Int = 0) {
        val records = (from until from + n).map {
            RecordDto(
                id = "r-%03d".format(it),
                type = "HOST",
                version = 1,
                updatedAt = "2026-01-01T00:00:00Z",
                deviceId = "devA",
                deleted = false,
                blob = byteArrayOf(it.toByte(), 1, 2, 3).b64(),
            )
        }
        put("/vault/records") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(records))
        }
    }

    @Test
    fun `vault envelopes page by offset and report the whole count`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)
        client.pushRecords(tokens.accessToken, 7)

        val first: VaultEnvelopesResponse =
            client.get("/vault/envelopes?limit=3") { bearerAuth(tokens.accessToken) }.body()
        val second: VaultEnvelopesResponse =
            client.get("/vault/envelopes?limit=3&offset=3") { bearerAuth(tokens.accessToken) }.body()
        val last: VaultEnvelopesResponse =
            client.get("/vault/envelopes?limit=3&offset=6") { bearerAuth(tokens.accessToken) }.body()

        assertEquals(3, first.records.size)
        assertEquals(3, second.records.size)
        assertEquals(1, last.records.size)
        assertEquals(7L, first.total, "total counts the list, not the page")
        assertEquals(7L, second.total)
        // The pages partition the list: no row appears twice and none is skipped.
        val paged = (first.records + second.records + last.records).map { it.id }
        assertEquals(7, paged.toSet().size)
    }

    @Test
    fun `account log pages by offset`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)
        // Distinct records: re-pushing the same one is a no-op and writes no event to page through.
        repeat(5) { i -> client.pushRecords(tokens.accessToken, 1, from = i) }

        val first: AccountActivityResponse =
            client.get("/account/activity?limit=2") { bearerAuth(tokens.accessToken) }.body()
        val second: AccountActivityResponse =
            client.get("/account/activity?limit=2&offset=2") { bearerAuth(tokens.accessToken) }.body()

        assertEquals(2, first.events.size)
        assertEquals(2, second.events.size)
        assertTrue(first.total >= 6, "register plus five pushes")
        assertTrue(
            first.events.map { it.createdAt } != second.events.map { it.createdAt } ||
                first.events.map { it.detail } != second.events.map { it.detail },
            "the second page is not the first one again",
        )
    }

    @Test
    fun `console lists page by offset`() = testApplication {
        val services = testServices(adminToken = admin)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)
        client.registerAccount("second@example.com", password, deviceId = "devB")
        client.registerAccount("third@example.com", password, deviceId = "devC")
        client.pushRecords(tokens.accessToken, 5)

        val accounts: AdminAccountsResponse =
            client.get("/admin/accounts?limit=2&offset=2") { header("X-Admin-Token", admin) }.body()
        assertEquals(1, accounts.accounts.size, "third page of three accounts holds the last one")
        assertEquals(3L, accounts.total)

        val devices: AdminDevicesResponse =
            client.get("/admin/devices?limit=2&offset=1") { header("X-Admin-Token", admin) }.body()
        assertEquals(2, devices.devices.size)
        assertEquals(3L, devices.total)

        val events: AdminActivityResponse =
            client.get("/admin/activity?limit=2&offset=2") { header("X-Admin-Token", admin) }.body()
        assertEquals(2, events.events.size)

        val records: AdminRecordsResponse =
            client.get("/admin/accounts/$accountId/records?limit=2&offset=3") { header("X-Admin-Token", admin) }.body()
        assertEquals(2, records.records.size)
        assertEquals(5L, records.total)
    }

    @Test
    fun `an empty page still carries its total on the wire`() = testApplication {
        // kotlinx omits a property equal to its default, so a `total` of 0 vanishes from the
        // response unless the field is annotated. The browser then reads `undefined`, the range
        // renders as NaN and the next-page step never disables — a page that lies about being
        // paged. Asserted on the raw bytes: a deserialized object would report the default for a
        // field that never travelled.
        val services = testServices(adminToken = admin)
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)

        val envelopes = client.get("/vault/envelopes") { bearerAuth(tokens.accessToken) }.bodyAsText()
        assertTrue("\"total\":0" in envelopes, "empty storage page must still state its total: $envelopes")

        val records = client.get("/admin/accounts/$accountId/records") {
            header("X-Admin-Token", admin)
        }.bodyAsText()
        assertTrue("\"total\":0" in records, "empty records page must still state its total: $records")
    }

    @Test
    fun `an empty team activity page states its total`() {
        // Same annotation, checked at the contract rather than through a route: a team's log is
        // empty only before its first event, which no route can reach without writing one.
        val json = Json.encodeToString(TeamActivityResponse(emptyList()))
        assertTrue("\"total\":0" in json, json)
    }

    @Test
    fun `an offset past the end is an empty page, not an error`() = testApplication {
        // The reader can hold a stale page number after rows are purged; that must read as "nothing
        // here", never as a 500 from a negative or oversized SQL offset.
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)
        client.pushRecords(tokens.accessToken, 2)

        val past: VaultEnvelopesResponse =
            client.get("/vault/envelopes?limit=10&offset=999") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(0, past.records.size)
        assertEquals(2L, past.total)

        val negative: VaultEnvelopesResponse =
            client.get("/vault/envelopes?limit=10&offset=-5") { bearerAuth(tokens.accessToken) }.body()
        assertEquals(2, negative.records.size, "a negative offset clamps to the first page")
    }
}
