package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.db.PullPageLimits
import app.skerry.server.model.b64
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.RecordsResponse
import app.skerry.sync.wire.TeamCreateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pull routes page their delta, and the push routes hold a vault and a share space to a quota. */
class PullPagingRoutesTest {

    private val alice = "alice@example.com"
    private val password = "auth-key-hex-abc123"
    private val teamId = "team-0001"
    private val total = PullPageLimits.MAX_RECORDS * 2 + 7

    private fun record(id: String, size: Int = 2, version: Long = 1) =
        RecordDto(id, "HOST", version, "2026-09-27T00:00:00Z", "devA", false, ByteArray(size) { 1 }.b64())

    private suspend fun HttpClient.push(path: String, token: String, records: List<RecordDto>) =
        put(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(records))
        }

    /** Pulls [path] page by page from zero, the way the client's drain loop does. */
    private suspend fun HttpClient.drain(path: String, token: String): List<RecordsResponse> {
        val pages = mutableListOf<RecordsResponse>()
        var since = 0L
        while (true) {
            val page: RecordsResponse = get("$path?since=$since") { bearerAuth(token) }.body()
            if (page.records.isEmpty()) return pages
            pages += page
            assertTrue(page.cursor > since, "a page that does not advance the cursor loops the client forever")
            since = page.cursor
        }
    }

    @Test
    fun `a vault pull from zero arrives in pages that together hold every record once`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerAccount(alice, password).accessToken
        (1..total).chunked(400).forEach { ids ->
            assertEquals(HttpStatusCode.OK, client.push("/vault/records", token, ids.map { record("r$it") }).status)
        }

        val pages = client.drain("/vault/records", token)

        assertTrue(pages.all { it.records.size <= PullPageLimits.MAX_RECORDS }, "sizes: ${pages.map { it.records.size }}")
        assertEquals((1..total).map { "r$it" }, pages.flatMap { it.records }.map { it.id })
    }

    @Test
    fun `a team space pull from zero arrives in pages that together hold every record once`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerAccount(alice, password).accessToken
        client.post("/teams") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest(teamId))
        }
        (1..total).chunked(400).forEach { ids ->
            assertEquals(HttpStatusCode.OK, client.push("/teams/$teamId/records", token, ids.map { record("r$it") }).status)
        }

        val pages = client.drain("/teams/$teamId/records", token)

        assertTrue(pages.all { it.records.size <= PullPageLimits.MAX_RECORDS }, "sizes: ${pages.map { it.records.size }}")
        assertEquals((1..total).map { "r$it" }, pages.flatMap { it.records }.map { it.id })
    }

    @Test
    fun `a push past the account quota is refused with 413 and says why`() = testApplication {
        val services = testServices(extraEnv = mapOf("SKERRY_MAX_ACCOUNT_BYTES" to "1000"))
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerAccount(alice, password).accessToken
        assertEquals(HttpStatusCode.OK, client.push("/vault/records", token, listOf(record("r1", size = 600))).status)

        val refused = client.push("/vault/records", token, listOf(record("r2", size = 600)))

        assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
        // The owner's teams count towards it, so the answer has to say which quota is full.
        assertTrue("account storage quota" in refused.bodyAsText(), refused.bodyAsText())
        val rejected = services.metrics.scrape().lines()
            .firstOrNull { it.startsWith("skerry_http_rejected_requests_total{reason=\"storage_quota\"} ") }
        assertEquals("1.0", rejected?.substringAfterLast(' '), "a quota refusal must show on /metrics")
    }

    @Test
    fun `a push past the team space quota is refused with 413`() = testApplication {
        val services = testServices(extraEnv = mapOf("SKERRY_MAX_SCOPE_BYTES" to "1000"))
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val token = client.registerAccount(alice, password).accessToken
        client.post("/teams") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest(teamId))
        }
        assertEquals(HttpStatusCode.OK, client.push("/teams/$teamId/records", token, listOf(record("r1", size = 600))).status)

        val refused = client.push("/teams/$teamId/records", token, listOf(record("r2", size = 600)))

        assertEquals(HttpStatusCode.PayloadTooLarge, refused.status)
        assertTrue("space storage quota" in refused.bodyAsText(), refused.bodyAsText())
    }
}
