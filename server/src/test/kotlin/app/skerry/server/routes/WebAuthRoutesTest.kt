package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.db.WebSession
import app.skerry.server.model.b64
import app.skerry.sync.wire.DevicesResponse
import app.skerry.sync.wire.PairingStartRequest
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.RefreshRequest
import app.skerry.sync.wire.TokenResponse
import app.skerry.sync.wire.WebLoginRequest
import app.skerry.sync.wire.WebPasswordRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signing a browser in to the account zone. The credential is a separate web password set from the
 * app — never the master password, which the browser must never see. The session it opens is an
 * ordinary device: listed, revocable, and closed for good when the password is cleared.
 */
class WebAuthRoutesTest {

    private val account = "alice@example.com"
    private val password = "pw-hex"
    private val webPassword = "web-pw-123"

    private suspend fun HttpClient.setWebPassword(token: String, value: String?): HttpResponse =
        post("/auth/web-password") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(WebPasswordRequest(value))
        }

    private suspend fun HttpClient.webLogin(id: String = account, value: String = webPassword): HttpResponse =
        post("/auth/web-login") {
            contentType(ContentType.Application.Json)
            setBody(WebLoginRequest(id, value))
        }

    @Test
    fun `a set web password signs a browser in and registers exactly one web device`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)

        assertEquals(HttpStatusCode.NoContent, client.setWebPassword(app.accessToken, webPassword).status)

        val first: TokenResponse = client.webLogin().body()
        val second: TokenResponse = client.webLogin().body()

        // Two sign-ins, one row: the server names the web device itself, so a browser can't mint an
        // unbounded device list — nor claim the id of a device it isn't.
        val devices: DevicesResponse = client.get("/devices") { bearerAuth(first.accessToken) }.body()
        assertEquals(1, devices.devices.count { it.id == WebSession.DEVICE_ID })
        assertEquals(WebSession.PLATFORM, services.devices.find(account, WebSession.DEVICE_ID)?.platform)

        // Both token pairs are live — rotation of tokens, not of identity.
        assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(second.accessToken) }.status)
        assertTrue(services.activity.recent(50).any { it.event == "auth.web_login" })
    }

    @Test
    fun `the device list names each platform, so the browser row is recognisable`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password, platform = "Linux")
        client.setWebPassword(app.accessToken, webPassword)
        val web: TokenResponse = client.webLogin().body()

        val devices: DevicesResponse = client.get("/devices") { bearerAuth(web.accessToken) }.body()

        // The account zone shows a device's platform next to its name, and the web session must be
        // tellable from the rest — it is the row the person reading the page is sitting in.
        assertEquals("Linux", devices.devices.single { it.id == "devA" }.platform)
        assertEquals(WebSession.PLATFORM, devices.devices.single { it.id == WebSession.DEVICE_ID }.platform)
    }

    @Test
    fun `a wrong password and an unset one are the same answer`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)

        // No web password yet: 401, not 404 and not 500 — an unset account must not announce itself.
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin().status)
        // Nor may a nonexistent account be distinguishable from an existing one.
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin(id = "nobody@example.com").status)

        client.setWebPassword(app.accessToken, webPassword)
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin(value = "wrong").status)
        assertEquals(HttpStatusCode.OK, client.webLogin().status)
    }

    @Test
    fun `the master password is not a web password`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)

        assertEquals(HttpStatusCode.Unauthorized, client.webLogin(value = password).status)
    }

    @Test
    fun `clearing the password revokes the live web session`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        val web: TokenResponse = client.webLogin().body()
        assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(web.accessToken) }.status)

        assertEquals(HttpStatusCode.NoContent, client.setWebPassword(app.accessToken, null).status)

        // Removing the password must close the door that is already open: the access token is dead,
        // and so is the refresh token behind it.
        assertEquals(HttpStatusCode.Unauthorized, client.get("/devices") { bearerAuth(web.accessToken) }.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(RefreshRequest(web.refreshToken))
            }.status,
            "a revoked web session must not be able to mint itself a fresh token",
        )
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin().status)
        // The app's own session is untouched.
        assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(app.accessToken) }.status)
        assertTrue(services.activity.recent(50).any { it.event == "device.revoked" })
        assertNotNull(services.devices.find(account, WebSession.DEVICE_ID))
    }

    @Test
    fun `a sign-in that lands after the clear does not reopen the session`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        client.webLogin()
        client.setWebPassword(app.accessToken, null)

        // What a sign-in that verified the password just before the clear committed would do next.
        // Registering a device clears its revocation, so without the re-read this is exactly how the
        // closed session comes back to life.
        assertNull(services.openWebSession(account))
        assertTrue(services.devices.isRevoked(account, WebSession.DEVICE_ID))
    }

    @Test
    fun `rotating leaves other devices and the open browser alone`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        val web: TokenResponse = client.webLogin().body()

        assertEquals(HttpStatusCode.NoContent, client.setWebPassword(app.accessToken, "web-pw-456").status)

        assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(web.accessToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(app.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin(value = webPassword).status)
        assertEquals(HttpStatusCode.OK, client.webLogin(value = "web-pw-456").status)
    }

    @Test
    fun `setting the password again reopens the session the clear closed`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        client.webLogin()
        client.setWebPassword(app.accessToken, null)

        client.setWebPassword(app.accessToken, "web-pw-456")
        val reopened: TokenResponse = client.webLogin(value = "web-pw-456").body()

        // The revoked web device is live again, and the log says so: a device that comes back
        // without a word is exactly what the re-enroll event exists to prevent.
        assertEquals(HttpStatusCode.OK, client.get("/devices") { bearerAuth(reopened.accessToken) }.status)
        assertTrue(services.activity.recent(50).any { it.event == "device.reenrolled" })
    }

    @Test
    fun `a web password outside the length bounds is refused before it is stored`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)

        assertEquals(HttpStatusCode.BadRequest, client.setWebPassword(app.accessToken, "short").status)
        assertEquals(HttpStatusCode.Unauthorized, client.webLogin(value = "short").status)
        // The ceiling is there so an absurd input never reaches Argon2; the value at the boundary
        // is a valid password, the one past it is not.
        assertEquals(HttpStatusCode.BadRequest, client.setWebPassword(app.accessToken, "p".repeat(257)).status)
        assertEquals(HttpStatusCode.NoContent, client.setWebPassword(app.accessToken, "p".repeat(256)).status)
        assertEquals(HttpStatusCode.OK, client.webLogin(value = "p".repeat(256)).status)
    }

    @Test
    fun `a web session reads metadata and nothing that could open the vault`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        val web: TokenResponse = client.webLogin().body()

        // What the zone is for: the metadata the server already holds in the clear.
        listOf("/account/summary", "/account/activity", "/vault/envelopes", "/devices", "/teams").forEach {
            assertEquals(HttpStatusCode.OK, client.get(it) { bearerAuth(web.accessToken) }.status, it)
        }

        // What it must never reach. The wrapped dataKey and the blobs are exactly the material an
        // offline attack on the master password needs, and the web password is the lesser
        // credential — it must not be a way to collect them.
        listOf("/vault/keys", "/vault/records").forEach {
            assertEquals(HttpStatusCode.Forbidden, client.get(it) { bearerAuth(web.accessToken) }.status, it)
        }
        // Nor may it write: rotating the web password would lock the owner out of their own zone,
        // and a pairing session would enrol a device that clearing the password does not revoke.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/auth/web-password") {
                bearerAuth(web.accessToken)
                contentType(ContentType.Application.Json)
                setBody(WebPasswordRequest("another-pw"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/pairing/start") {
                bearerAuth(web.accessToken)
                contentType(ContentType.Application.Json)
                setBody(PairingStartRequest(byteArrayOf(1, 2, 3).b64()))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.put("/vault/records") {
                bearerAuth(web.accessToken)
                contentType(ContentType.Application.Json)
                setBody(PushRequest(emptyList()))
            }.status,
        )
        // The app's own session is not restricted by any of this.
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(app.accessToken) }.status)
    }

    /**
     * The guard and the router have to agree on what a path is. Ktor resolves a route on decoded
     * segments and skips empty ones, so `/vault/%72ecords` and `/vault//records` both reach the
     * handler that hands over every ciphertext blob — while a guard comparing the raw request target
     * sees a string it doesn't recognise and waves the GET through.
     */
    @Test
    fun `a web session cannot reach the vault by respelling the path`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        val web: TokenResponse = client.webLogin().body()

        listOf("/vault/%72ecords", "/vault/k%65ys", "/vault//records", "//vault/keys").forEach { path ->
            val response = client.get { url { encodedPath = path }; bearerAuth(web.accessToken) }
            assertEquals(HttpStatusCode.Forbidden, response.status, path)
        }
        // The same spelling on the app's own session still resolves to the real handler — the point
        // is that the guard refuses it, not that the route stopped existing.
        assertEquals(
            HttpStatusCode.OK,
            client.get { url { encodedPath = "/vault/k%65ys" }; bearerAuth(app.accessToken) }.status,
        )
    }

    @Test
    fun `a web session can still revoke a device, including itself`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)
        val web: TokenResponse = client.webLogin().body()

        // The one action the account zone offers, and the only write a browser is trusted with.
        assertEquals(
            HttpStatusCode.NoContent,
            client.delete("/devices/${WebSession.DEVICE_ID}") { bearerAuth(web.accessToken) }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/devices") { bearerAuth(web.accessToken) }.status)
    }

    @Test
    fun `the app reads back whether web access is on`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)

        // Asserted on the raw bytes, not on a parsed object: kotlinx omits a property equal to its
        // default, and a field the app never receives would read as `false` off a deserialized
        // response — the §2.4 trap, which is why the DTO carries no default.
        val before = client.get("/auth/web-password") { bearerAuth(app.accessToken) }
        assertEquals(HttpStatusCode.OK, before.status)
        assertEquals("""{"enabled":false}""", before.bodyAsText())

        client.setWebPassword(app.accessToken, webPassword)
        assertEquals("""{"enabled":true}""", client.get("/auth/web-password") { bearerAuth(app.accessToken) }.bodyAsText())

        client.setWebPassword(app.accessToken, null)
        assertEquals("""{"enabled":false}""", client.get("/auth/web-password") { bearerAuth(app.accessToken) }.bodyAsText())
    }

    @Test
    fun `reading the web access state requires a session`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/auth/web-password").status)
    }

    @Test
    fun `setting a web password requires the app session`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/auth/web-password") {
                contentType(ContentType.Application.Json)
                setBody(WebPasswordRequest(webPassword))
            }.status,
        )
    }

    @Test
    fun `the limiter turns a brute force away before verification`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val app = client.registerAccount(account, password)
        client.setWebPassword(app.accessToken, webPassword)

        repeat(10) { n ->
            assertEquals(HttpStatusCode.Unauthorized, client.webLogin(value = "wrong-$n").status, "attempt $n")
        }
        // The eleventh is refused by the limiter — and the correct password gets the same answer,
        // which is the point: guessing costs attempts, not time.
        assertEquals(HttpStatusCode.TooManyRequests, client.webLogin().status)
    }
}
