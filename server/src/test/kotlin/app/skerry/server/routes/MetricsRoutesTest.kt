package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.configureServer
import app.skerry.server.guardConfig
import app.skerry.server.config.ServerConfig
import app.skerry.sync.wire.RecordDto
import app.skerry.server.model.b64
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The metrics endpoint carries instance metadata, which on a zero-knowledge server is the whole
 * attack surface — so these tests are as much about what the exposition must *not* contain (account
 * ids, per-request path values) as about what it does.
 */
class MetricsRoutesTest {

    private val accountId = "alice@example.com"
    private val password = "correct horse"

    private fun withServer(
        metrics: String,
        metricsToken: String = "",
        adminToken: String = "s3cret",
        block: suspend ApplicationTestBuilder.(Services) -> Unit,
    ) = testApplication {
        val services = testServices(
            adminToken = adminToken,
            extraEnv = mapOf("SKERRY_METRICS" to metrics, "SKERRY_METRICS_TOKEN" to metricsToken),
        )
        application { configureServer(services) }
        // JvmGcMetrics registers a GC notification listener; the suite starts dozens of servers in one
        // JVM, so each one has to give it back.
        try {
            block(services)
        } finally {
            services.metrics.close()
        }
    }

    @Test
    fun `metrics are absent when disabled`() = withServer(metrics = "off") {
        // 404, not 401: a disabled endpoint should not announce that it exists.
        assertEquals(HttpStatusCode.NotFound, client.get("/metrics").status)
    }

    @Test
    fun `token mode rejects a missing or wrong bearer token`() = withServer("token", metricsToken = "scrape-me") {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer nope") }.status,
        )
        // The admin token must not open it: that credential also authorizes DELETE /admin/accounts.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/metrics") { header("X-Admin-Token", "s3cret") }.status,
        )
    }

    @Test
    fun `token mode serves the exposition and counts rejected scrapes`() = withServer("token", metricsToken = "scrape-me") {
        client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer nope") }
        val response = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer scrape-me") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("skerry_build_info" in body, body.take(400))
        assertTrue("jvm_memory_used_bytes" in body, "JVM binders should be attached when metrics are on")
        assertTrue("skerry_metrics_auth_failures_total 1.0" in body, body.lines().filter { "auth_failures" in it }.toString())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `open mode needs no credential`() = withServer("open") {
        assertEquals(HttpStatusCode.OK, client.get("/metrics").status)
    }

    @Test
    fun `token mode without a token refuses to start`() {
        val config = ServerConfig.fromEnv(mapOf("SKERRY_METRICS" to "token", "SKERRY_JWT_SECRET" to "x"))
        val failure = assertFailsWith<IllegalStateException> { guardConfig(config, emptyMap()) }
        assertTrue("SKERRY_METRICS_TOKEN" in (failure.message ?: ""), failure.message ?: "")
    }

    /**
     * The whole point of the label rules: no account id, device id or record id may reach the
     * exposition, whether as a label value or inside a route label.
     */
    @Test
    fun `exposition never contains account device or record identifiers`() = withServer("open") { services ->
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password, deviceId = "devA", platform = "linux")
        client.pushRecord(tokens.accessToken, RecordDto("secret-record-id", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(7).b64()))
        client.get("/vault/records?since=0") { header(HttpHeaders.Authorization, "Bearer ${tokens.accessToken}") }
        client.delete("/admin/devices/devA?accountId=$accountId") { header("X-Admin-Token", "s3cret") }
        services.inventory.collectOnce()

        val body = services.metrics.scrape()
        assertFalse(accountId in body, "accountId leaked into the exposition")
        assertFalse("devA" in body, "deviceId leaked into the exposition")
        assertFalse("secret-record-id" in body, "recordId leaked into the exposition")
        // The path parameter must appear as a template, never as its value.
        assertTrue("""route="/admin/devices/{id}"""" in body, body.lines().filter { "admin_devices" in it || "route=" in it }.take(8).toString())
    }

    /**
     * Cardinality guard. `/assets` is a tailcard route and `/anything` doesn't match at all, both
     * reachable without a credential — if either produced one series per request, an attacker could
     * grow the registry until the process dies.
     */
    @Test
    fun `unmatched and static paths do not create a series per request`() = withServer("open") { services ->
        client.get("/assets/one")
        client.get("/nope-one")
        val baseline = services.metrics.scrape().lines().count { it.startsWith("skerry_http_server_requests") }

        repeat(50) { i ->
            client.get("/assets/junk-$i")
            client.get("/nope-$i")
        }
        val after = services.metrics.scrape().lines().count { it.startsWith("skerry_http_server_requests") }
        assertEquals(baseline, after, "series count grew with the number of distinct paths")
    }

    /**
     * Explicit SLO buckets, not Micrometer's percentile histogram: the default emits ~70 buckets per
     * series, which is thousands of series for a handful of routes.
     */
    @Test
    fun `request timer uses the explicit slo buckets`() = withServer("open") { services ->
        client.get("/healthz")
        val buckets = services.metrics.scrape().lines()
            .filter { it.startsWith("skerry_http_server_requests_seconds_bucket") && """route="/healthz"""" in it }
        assertEquals(7, buckets.size, buckets.toString()) // 6 objectives + +Inf
        assertTrue(buckets.any { """le="0.25"""" in it }, buckets.toString())
    }

    @Test
    fun `inventory gauges stay unknown until the first collection`() = withServer("open") { services ->
        val before = services.metrics.scrape().lines().first { it.startsWith("skerry_accounts ") }
        assertTrue("NaN" in before, before)

        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(accountId, password)
        services.inventory.collectOnce()

        val after = services.metrics.scrape().lines().first { it.startsWith("skerry_accounts ") }
        assertEquals("skerry_accounts 1.0", after)
        assertTrue(
            services.metrics.scrape().lines().any { it.startsWith("skerry_inventory_last_success_time_seconds") && !it.endsWith(" 0.0") },
        )
    }

    @Test
    fun `admin token failures are counted`() = withServer("open") { services ->
        client.get("/admin/stats") // no token
        client.get("/admin/stats") { header("X-Admin-Token", "wrong") }
        val body = services.metrics.scrape()
        assertTrue("skerry_admin_auth_failures_total 2.0" in body, body.lines().filter { "admin_auth" in it }.toString())
    }

    @Test
    fun `failed logins are counted by outcome`() = withServer("open") { services ->
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(accountId, password)
        client.srpLoginResponse(accountId, "wrong password", "devB", "Phone B")

        val body = services.metrics.scrape()
        assertTrue("""skerry_auth_attempts_total{kind="register",outcome="ok"} 1.0""" in body, body.lines().filter { "auth_attempts" in it }.toString())
        assertTrue(
            body.lines().any { it.startsWith("""skerry_auth_attempts_total{kind="srp_verify",outcome="denied"}""") },
            body.lines().filter { "auth_attempts" in it }.toString(),
        )
    }

    /**
     * The console needs to show whether monitoring is actually wired up; this endpoint is the only
     * place it can learn that, and it stays behind the admin token like the rest of /admin.
     */
    @Test
    fun `observability status is admin-gated and reports the metrics mode`() = withServer("token", metricsToken = "scrape-me") { services ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/admin/observability").status)

        val body = client.get("/admin/observability") { header("X-Admin-Token", "s3cret") }.bodyAsText()
        assertTrue(""""metrics":"token"""" in body, body)
        assertTrue(""""ready":true""" in body, body)
        // Never collected yet: the age must be absent, not zero — zero would read as "just now".
        assertTrue(""""inventoryAgeSeconds":null""" in body, body)

        services.inventory.collectOnce()
        val after = client.get("/admin/observability") { header("X-Admin-Token", "s3cret") }.bodyAsText()
        assertFalse(""""inventoryAgeSeconds":null""" in after, after)
    }

    /**
     * Readiness through the real route, driven by the service's own probe: three consecutive failures
     * must answer 503, and `/healthz` must keep answering 200 on that same instance — the container
     * healthcheck and every client's availability ping hang off it, so tying it to the database would
     * turn a slow transaction into a restart loop and a client-wide "unreachable" storm.
     */
    @Test
    fun `readiness turns 503 after repeated failures while healthz keeps answering`() = testApplication {
        var databaseDown = false
        val services = testServices(adminToken = "s3cret", dbCheck = { if (databaseDown) error("locked") })
        application { configureServer(services) }

        services.dbProbe.probeOnce()
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
        assertTrue("\"status\":\"ready\"" in client.get("/readyz").bodyAsText())

        databaseDown = true
        repeat(2) { services.dbProbe.probeOnce() }
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status, "two failures must not flip readiness")

        services.dbProbe.probeOnce()
        val notReady = client.get("/readyz")
        assertEquals(HttpStatusCode.ServiceUnavailable, notReady.status)
        val body = notReady.bodyAsText()
        assertTrue("\"status\":\"not_ready\"" in body && "\"db\":\"down\"" in body, body)
        // Liveness is deliberately independent of the database.
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals("ok", client.get("/healthz").bodyAsText())

        databaseDown = false
        services.dbProbe.probeOnce()
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status, "one success should restore readiness")
    }

    /** The console's only window into whether monitoring is wired up must follow the probe too. */
    @Test
    fun `observability status follows the probe`() = testApplication {
        var databaseDown = false
        val services = testServices(adminToken = "s3cret", dbCheck = { if (databaseDown) error("locked") })
        application { configureServer(services) }

        databaseDown = true
        repeat(3) { services.dbProbe.probeOnce() }
        val body = client.get("/admin/observability") { header("X-Admin-Token", "s3cret") }.bodyAsText()
        assertTrue("\"ready\":false" in body, body)
    }
}
