package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.ChangePasswordResponse
import app.skerry.sync.wire.PairingClaimRequest
import app.skerry.sync.wire.PairingStartRequest
import app.skerry.sync.wire.PairingStartResponse
import app.skerry.sync.wire.RefreshRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a revocation takes away has to stay taken: tokens and pairing codes a device held before a
 * revoke or a password change must not come back when the flag is later cleared by a re-login.
 */
class TokenRevocationTest {

    private val accountId = "alice@example.com"
    private val password = "auth-key-hex-abc123"

    private suspend fun HttpClient.refresh(token: String): HttpResponse = post("/auth/refresh") {
        contentType(ContentType.Application.Json)
        setBody(RefreshRequest(token))
    }

    private suspend fun HttpClient.startPairing(accessToken: String): PairingStartResponse = post("/pairing/start") {
        bearerAuth(accessToken)
        contentType(ContentType.Application.Json)
        setBody(PairingStartRequest(byteArrayOf(9).b64()))
    }.body()

    private suspend fun HttpClient.claim(code: String, deviceId: String): HttpResponse = post("/pairing/claim") {
        contentType(ContentType.Application.Json)
        setBody(PairingClaimRequest(code, deviceId, "New device"))
    }

    @Test
    fun `tokens held before a revoke stay dead after the device logs in again`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount(accountId, password, deviceId = "devA")
        // The pair a thief copied off devB.
        val stolen = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")

        assertEquals(HttpStatusCode.NoContent, client.delete("/devices/devB") { bearerAuth(devA.accessToken) }.status)
        // The owner signs devB back in; that clears the revocation flag.
        val fresh = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")

        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(fresh.accessToken) }.status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.refresh(stolen.refreshToken).status,
            "a refresh token issued before the revoke came back to life with the re-login",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/vault/keys") { bearerAuth(stolen.accessToken) }.status,
            "an access token issued before the revoke came back to life with the re-login",
        )
    }

    @Test
    fun `a password change retires the acting device's earlier tokens and hands it working ones`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val before = client.registerAccount(accountId, password, deviceId = "devA")

        val rotated = client.changePassword(accountId, password, "new-auth-key-hex", byteArrayOf(2), deviceId = "devA")
        assertEquals(HttpStatusCode.OK, rotated.status)
        val after: ChangePasswordResponse = rotated.body()

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.refresh(before.refreshToken).status,
            "the acting device's pre-rotation refresh token survived the password change",
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/vault/keys") { bearerAuth(before.accessToken) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/vault/keys") { bearerAuth(after.accessToken) }.status)
        assertEquals(HttpStatusCode.OK, client.refresh(after.refreshToken).status)
    }

    @Test
    fun `a password change keeps other devices' old tokens dead after they log in with the new one`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(accountId, password, deviceId = "devA")
        val stolen = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")
        val newPassword = "new-auth-key-hex"

        assertEquals(HttpStatusCode.OK, client.changePassword(accountId, password, newPassword, byteArrayOf(2)).status)
        client.srpLogin(accountId, newPassword, deviceId = "devB", deviceName = "Phone B")

        assertEquals(
            HttpStatusCode.Unauthorized,
            client.refresh(stolen.refreshToken).status,
            "a pre-rotation refresh token came back once its device re-logged in with the new password",
        )
    }

    @Test
    fun `a pairing code dies with the revoke of the device that started it`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount(accountId, password, deviceId = "devA")
        val devB = client.srpLogin(accountId, password, deviceId = "devB", deviceName = "Phone B")
        val code = client.startPairing(devB.accessToken).code

        assertEquals(HttpStatusCode.NoContent, client.delete("/devices/devB") { bearerAuth(devA.accessToken) }.status)

        assertEquals(
            HttpStatusCode.Gone,
            client.claim(code, "devX").status,
            "a pairing code started by a revoked device still enrolled a new one",
        )
    }

    @Test
    fun `a password change voids pending pairing codes`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val devA = client.registerAccount(accountId, password, deviceId = "devA")
        val code = client.startPairing(devA.accessToken).code

        assertEquals(HttpStatusCode.OK, client.changePassword(accountId, password, "new-auth-key-hex", byteArrayOf(2)).status)

        assertEquals(
            HttpStatusCode.Gone,
            client.claim(code, "devX").status,
            "a pairing code started before the password change still enrolled a new device",
        )
    }
}
