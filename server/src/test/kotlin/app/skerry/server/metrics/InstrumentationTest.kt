package app.skerry.server.metrics

import app.skerry.server.Services
import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.server.routes.changePassword
import app.skerry.server.routes.pushRecord
import app.skerry.server.routes.registerAccount
import app.skerry.server.routes.registerAccountResponse
import app.skerry.server.routes.srpLogin
import app.skerry.server.routes.srpLoginResponse
import app.skerry.server.routes.testServices
import app.skerry.sync.wire.PairingClaimRequest
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RecordDto
import app.skerry.sync.wire.TeamCreateRequest
import app.skerry.sync.wire.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Instrumentation is only worth its call sites if the numbers are right, so these tests assert the
 * exposed **values**, not merely that the response code survived the added counter. A counter that
 * silently stops incrementing looks exactly like a healthy server otherwise.
 */
class InstrumentationTest {

    private val alice = "alice@example.com"
    private val bob = "bob@example.com"
    private val password = "correct horse"

    /** A single WS session subscribes to three notifier channels (account, teams, membership). */
    private val wsSubscriptions = 3

    private fun withServer(
        extraEnv: Map<String, String> = emptyMap(),
        block: suspend ApplicationTestBuilder.(Services) -> Unit,
    ) = testApplication {
        val services = testServices(
            adminToken = "s3cret",
            extraEnv = mapOf("SKERRY_METRICS" to "open") + extraEnv,
        )
        application { configureServer(services) }
        try {
            block(services)
        } finally {
            services.metrics.close()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    /** Value of one exposed series; 0.0 when the series doesn't exist yet (a counter never touched). */
    private fun ServerMetrics.value(series: String): Double =
        scrape().lines().firstOrNull { it.startsWith("$series ") }?.substringAfterLast(' ')?.toDouble() ?: 0.0

    private fun ServerMetrics.has(prefix: String): Boolean = scrape().lines().any { it.startsWith(prefix) }

    // --- sync volume ---

    @Test
    fun `account push and pull count records and ciphertext bytes`() = withServer { services ->
        val client = jsonClient()
        val tokens = client.registerAccount(alice, password)
        val blobs = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6, 7, 8))

        client.put("/vault/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                PushRequest(
                    blobs.mapIndexed { i, blob ->
                        RecordDto("r$i", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, blob.b64())
                    },
                ),
            )
        }

        assertEquals(2.0, services.metrics.value("""skerry_sync_records_received_total{scope="account"}"""))
        // Bytes of ciphertext, not of the base64 envelope: 3 + 5.
        assertEquals(
            blobs.sumOf { it.size }.toDouble(),
            services.metrics.value("""skerry_sync_push_bytes_total{scope="account"}"""),
        )

        client.get("/vault/records?since=0") { bearerAuth(tokens.accessToken) }
        assertEquals(2.0, services.metrics.value("""skerry_sync_records_pulled_total{scope="account"}"""))

        // An empty delta pulls nothing and must not inflate the counter.
        client.get("/vault/records?since=999") { bearerAuth(tokens.accessToken) }
        assertEquals(2.0, services.metrics.value("""skerry_sync_records_pulled_total{scope="account"}"""))
    }

    @Test
    fun `team push and pull are counted under the team scope`() = withServer { services ->
        val client = jsonClient()
        val tokens = client.registerAccount(alice, password)
        client.post("/teams") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest("team-1"))
        }
        client.put("/teams/team-1/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                PushRequest(listOf(RecordDto("r1", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(9, 9).b64()))),
            )
        }
        client.get("/teams/team-1/records?since=0") { bearerAuth(tokens.accessToken) }

        assertEquals(1.0, services.metrics.value("""skerry_sync_records_received_total{scope="team"}"""))
        assertEquals(2.0, services.metrics.value("""skerry_sync_push_bytes_total{scope="team"}"""))
        assertEquals(1.0, services.metrics.value("""skerry_sync_records_pulled_total{scope="team"}"""))
        // The account scope must stay untouched by team traffic.
        assertEquals(0.0, services.metrics.value("""skerry_sync_records_received_total{scope="account"}"""))
    }

    // --- auth ---

    @Test
    fun `token issuance and refresh outcomes are counted`() = withServer { services ->
        val client = jsonClient()
        val tokens: TokenResponse = client.registerAccount(alice, password)
        assertEquals(1.0, services.metrics.value("""skerry_auth_tokens_issued_total{type="access"}"""))
        assertEquals(1.0, services.metrics.value("""skerry_auth_tokens_issued_total{type="refresh"}"""))

        client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(app.skerry.sync.wire.RefreshRequest(tokens.refreshToken))
        }
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="refresh",outcome="ok"}"""))
        assertEquals(2.0, services.metrics.value("""skerry_auth_tokens_issued_total{type="access"}"""))

        client.post("/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(app.skerry.sync.wire.RefreshRequest("not-a-token"))
        }
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="refresh",outcome="denied"}"""))
    }

    @Test
    fun `login and password rotation record both outcomes`() = withServer { services ->
        val client = jsonClient()
        client.registerAccount(alice, password)

        client.srpLogin(alice, password, "devB", "Phone B")
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="srp_verify",outcome="ok"}"""))
        client.srpLoginResponse(alice, "wrong", "devB", "Phone B")
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="srp_verify",outcome="denied"}"""))
        // Every challenge is counted the same way whether or not the account exists — the synthesized
        // challenge for an unknown account is what stops enumeration, and this must not undo it.
        client.srpLoginResponse("nobody@example.com", password, "devB", "Phone B")
        assertEquals(3.0, services.metrics.value("""skerry_auth_attempts_total{kind="srp_challenge",outcome="ok"}"""))

        client.changePassword(alice, password, "new password", byteArrayOf(2))
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="change_password",outcome="ok"}"""))
        client.changePassword(alice, "still wrong", "another", byteArrayOf(2))
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="change_password",outcome="denied"}"""))
    }

    @Test
    fun `pairing claims are counted per outcome`() = withServer { services ->
        val client = jsonClient()
        val tokens = client.registerAccount(alice, password)
        val start: app.skerry.sync.wire.PairingStartResponse = client.post("/pairing/start") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(app.skerry.sync.wire.PairingStartRequest(byteArrayOf(5).b64(), null))
        }.body()
        client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, "devB", "Phone B"))
        }
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="pairing_claim",outcome="ok"}"""))

        // The code is one-shot: replaying it is a denied claim.
        client.post("/pairing/claim") {
            contentType(ContentType.Application.Json)
            setBody(PairingClaimRequest(start.code, "devC", "Phone C"))
        }
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="pairing_claim",outcome="denied"}"""))
    }

    @Test
    fun `a wrong token type and a revoked device are distinguished`() = withServer { services ->
        val client = jsonClient()
        val tokens = client.registerAccount(alice, password, deviceId = "devA")

        // A refresh token presented as an access token: valid signature, wrong type.
        client.get("/devices") { bearerAuth(tokens.refreshToken) }
        assertEquals(1.0, services.metrics.value("""skerry_auth_jwt_rejected_total{reason="wrong_type"}"""))

        services.devices.revoke(alice, "devA")
        client.get("/devices") { bearerAuth(tokens.accessToken) }
        assertEquals(1.0, services.metrics.value("""skerry_auth_jwt_rejected_total{reason="device_revoked"}"""))
    }

    @Test
    fun `closed registration and the account cap are told apart`() {
        withServer(mapOf("SKERRY_REGISTRATION" to "closed")) { services ->
            jsonClient().registerAccountResponse(alice, password)
            assertEquals(1.0, services.metrics.value("""skerry_registration_rejected_total{reason="closed"}"""))
            assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="register",outcome="denied"}"""))
        }
        withServer(mapOf("SKERRY_MAX_ACCOUNTS" to "1")) { services ->
            val client = jsonClient()
            client.registerAccount(alice, password)
            client.registerAccountResponse(bob, password, deviceId = "devB")
            assertEquals(1.0, services.metrics.value("""skerry_registration_rejected_total{reason="cap_reached"}"""))
        }
    }

    @Test
    fun `an existing account gives a register error rather than a denial`() = withServer { services ->
        val client = jsonClient()
        client.registerAccount(alice, password)
        client.registerAccountResponse(alice, password)
        assertEquals(1.0, services.metrics.value("""skerry_auth_attempts_total{kind="register",outcome="error"}"""))
        assertEquals(0.0, services.metrics.value("""skerry_auth_attempts_total{kind="register",outcome="denied"}"""))
    }

    @Test
    fun `revocations are attributed to the user or the admin`() = withServer { services ->
        val client = jsonClient()
        val tokens = client.registerAccount(alice, password, deviceId = "devA")
        client.srpLogin(alice, password, "devB", "Phone B")

        client.delete("/devices/devB") { bearerAuth(tokens.accessToken) }
        assertEquals(1.0, services.metrics.value("""skerry_devices_revoked_total{by="user"}"""))

        client.delete("/admin/devices/devA?accountId=$alice") { header("X-Admin-Token", "s3cret") }
        assertEquals(1.0, services.metrics.value("""skerry_devices_revoked_total{by="admin"}"""))

        // A revoke that matched nothing must not be counted.
        client.delete("/admin/devices/ghost?accountId=$alice") { header("X-Admin-Token", "s3cret") }
        assertEquals(1.0, services.metrics.value("""skerry_devices_revoked_total{by="admin"}"""))
    }

    @Test
    fun `admin and metrics token failures are counted separately`() = withServer { services ->
        val client = jsonClient()
        client.get("/admin/stats")
        client.get("/metrics") // open mode: no failure expected here
        assertEquals(1.0, services.metrics.value("skerry_admin_auth_failures_total"))
        assertEquals(0.0, services.metrics.value("skerry_metrics_auth_failures_total"))
    }

    @Test
    fun `team access denials are labelled by reason`() = withServer { services ->
        val client = jsonClient()
        val aliceTokens = client.registerAccount(alice, password, deviceId = "devA")
        val bobTokens = client.registerAccount(bob, password, deviceId = "devB")
        client.post("/teams") {
            bearerAuth(aliceTokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest("team-1"))
        }

        // Bob is not a member at all.
        client.get("/teams/team-1/records?since=0") { bearerAuth(bobTokens.accessToken) }
        assertEquals(1.0, services.metrics.value("""skerry_team_authz_denied_total{reason="not_member"}"""))

        // Alice asks for a scope nobody granted her.
        client.get("/teams/team-1/records?since=0&scope=prod") { bearerAuth(aliceTokens.accessToken) }
        assertEquals(1.0, services.metrics.value("""skerry_team_authz_denied_total{reason="scope"}"""))
    }

    @Test
    fun `an oversized body is counted as a rejected request`() = withServer(mapOf("SKERRY_MAX_BODY_BYTES" to "64")) { services ->
        val client = jsonClient()
        client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat(500))
        }
        assertEquals(1.0, services.metrics.value("""skerry_http_rejected_requests_total{reason="body_too_large"}"""))
    }

    // --- WebSocket sessions ---

    @Test
    fun `a websocket session is counted open, closed by the client, and its frames tallied`() = withServer { services ->
        val client = createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
        val tokens: TokenResponse = client.registerAccount(alice, password, deviceId = "devA")

        client.webSocket("/sync", request = { bearerAuth(tokens.accessToken) }) {
            withTimeout(2_000) { services.notifier.subscriptions.first { it >= wsSubscriptions } }
            assertEquals(1.0, services.metrics.value("skerry_sync_ws_sessions"))
            assertEquals(1.0, services.metrics.value("skerry_sync_ws_sessions_opened_total"))

            services.notifier.publish(alice, 7)
            withTimeout(2_000) { incoming.receive() } as Frame.Text
            close(CloseReason(CloseReason.Codes.NORMAL, "done"))
        }
        withTimeout(2_000) { services.notifier.subscriptions.first { it == 0 } }

        assertEquals(0.0, services.metrics.value("skerry_sync_ws_sessions"), "the gauge must come back down")
        assertEquals(1.0, services.metrics.value("""skerry_sync_ws_frames_sent_total{kind="account"}"""))
        assertEquals(1.0, services.metrics.value("""skerry_sync_notify_published_total{kind="account"}"""))
        assertEquals(
            1.0,
            services.metrics.value("""skerry_sync_ws_sessions_closed_total{reason="client_close"}"""),
        )
        assertTrue(services.metrics.has("skerry_sync_ws_session_duration_seconds_count"))
    }

    @Test
    fun `a session dropped by revocation is closed under that reason`() = withServer { services ->
        val client = createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
        val tokens: TokenResponse = client.registerAccount(alice, password, deviceId = "devA")

        client.webSocket("/sync", request = { bearerAuth(tokens.accessToken) }) {
            withTimeout(2_000) { services.notifier.subscriptions.first { it >= wsSubscriptions } }
            services.devices.revoke(alice, "devA")
            services.notifier.publish(alice, 1)
            withTimeout(2_000) { closeReason.await() }
        }
        withTimeout(2_000) { services.notifier.subscriptions.first { it == 0 } }

        // First cause wins: the revoke decided this close, not the client's reaction to it.
        assertEquals(
            1.0,
            services.metrics.value("""skerry_sync_ws_sessions_closed_total{reason="device_revoked"}"""),
            services.metrics.scrape().lines().filter { "ws_sessions_closed" in it }.toString(),
        )
        assertEquals(0.0, services.metrics.value("""skerry_sync_ws_sessions_closed_total{reason="client_close"}"""))
    }

    // --- inventory and pool ---

    @Test
    fun `the inventory snapshot reports every field it claims`() = withServer { services ->
        val client = jsonClient()
        val tokens = client.registerAccount(alice, password, deviceId = "devA")
        client.srpLogin(alice, password, "devB", "Phone B")
        services.devices.revoke(alice, "devB")
        client.pushRecord(tokens.accessToken, RecordDto("live-1", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(1, 2, 3).b64()))
        client.pushRecord(tokens.accessToken, RecordDto("dead-1", "HOST", 2, "2026-07-26T00:00:00Z", "devA", true, byteArrayOf().b64()))
        client.post("/teams") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(TeamCreateRequest("team-1"))
        }
        client.put("/teams/team-1/records") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PushRequest(listOf(RecordDto("t1", "HOST", 1, "2026-07-26T00:00:00Z", "devA", false, byteArrayOf(7, 7, 7, 7).b64()))))
        }
        client.post("/pairing/start") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(app.skerry.sync.wire.PairingStartRequest(byteArrayOf(5).b64(), null))
        }

        services.inventory.collectOnce()
        val m = services.metrics

        assertEquals(1.0, m.value("skerry_accounts"))
        assertEquals(1.0, m.value("""skerry_devices{state="active"}"""))
        assertEquals(1.0, m.value("""skerry_devices{state="revoked"}"""))
        assertEquals(1.0, m.value("""skerry_records{state="live"}"""))
        assertEquals(1.0, m.value("""skerry_records{state="tombstone"}"""))
        assertEquals(3.0, m.value("""skerry_storage_bytes{scope="account"}"""))
        assertEquals(1.0, m.value("""skerry_team_records{state="live"}"""))
        assertEquals(0.0, m.value("""skerry_team_records{state="tombstone"}"""))
        assertEquals(4.0, m.value("""skerry_storage_bytes{scope="team"}"""))
        assertEquals(1.0, m.value("""skerry_pairing_sessions{state="pending"}"""))
        assertEquals(0.0, m.value("""skerry_pairing_sessions{state="expired"}"""))
        assertEquals(1.0, m.value("skerry_teams"))
        assertEquals(1.0, m.value("""skerry_team_members{status="active"}"""))
        assertEquals(0.0, m.value("""skerry_team_members{status="invited"}"""))
        assertTrue(m.value("skerry_activity_log_rows") > 0, "the audit log has events by now")
        assertTrue(m.value("skerry_db_file_bytes") > 0, "the SQLite file exists on disk")
        assertEquals(1.0, m.value("skerry_registration_open"))
    }

    /** The pool metrics only appear if the registry actually reached HikariCP in Db.connect. */
    @Test
    fun `hikari pool metrics reach the exposition`() = withServer { services ->
        jsonClient().registerAccount(alice, password)
        val body = services.metrics.scrape()
        assertTrue("hikaricp_connections" in body, "no HikariCP metrics: the registry never reached the pool")
        // The pool name is numbered per JVM, so match the series, not "HikariPool-1". SQLite is
        // single-writer: this instance's pool is deliberately one connection.
        val poolMax = body.lines().filter { it.startsWith("hikaricp_connections_max{") }
        assertEquals(1, poolMax.size, poolMax.toString())
        assertEquals(1.0, poolMax.single().substringAfterLast(' ').toDouble())
    }
}
