package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.ChallengeRequest
import app.skerry.sync.wire.ChallengeResponse
import app.skerry.sync.wire.PairingClaimRequest
import app.skerry.sync.wire.PairingStartRequest
import app.skerry.sync.wire.PairingStartResponse
import app.skerry.sync.wire.VerifyRequest
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A device id longer than the `devices.id` column is refused with a 400 before anything is spent:
 * no account created without its device, no SRP challenge consumed, no pairing code burned.
 */
class DeviceIdLengthTest {

    private val accountId = "alice@example.com"
    private val password = "auth-key-hex-abc123"

    /** Longer than `varchar(64)`, shorter than the generic 128-char identifier bound. */
    private val overlong = "d".repeat(65)

    @Test
    fun `register refuses an overlong device id without creating the account`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }

        assertEquals(HttpStatusCode.BadRequest, client.registerAccountResponse(accountId, password, deviceId = overlong).status)
        assertEquals(
            HttpStatusCode.OK,
            client.registerAccountResponse(accountId, password, deviceId = "devA").status,
            "the refused registration left an account behind",
        )
    }

    @Test
    fun `srp verify refuses an overlong device id without consuming the challenge`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(accountId, password)

        val sc = srpClient(accountId, password)
        val challenge: ChallengeResponse = client.post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(accountId))
        }.body()
        val creds = sc.step2(SRP_PARAMS, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))
        fun verify(deviceId: String) = VerifyRequest(challenge.challengeId, creds.A.toString(16), creds.M1.toString(16), deviceId, "B")

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/auth/srp/verify") {
                contentType(ContentType.Application.Json)
                setBody(verify(overlong))
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/auth/srp/verify") {
                contentType(ContentType.Application.Json)
                setBody(verify("devB"))
            }.status,
        )
    }

    @Test
    fun `change password refuses an overlong device id`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(accountId, password)

        assertEquals(
            HttpStatusCode.BadRequest,
            client.changePassword(accountId, password, "new-auth-key-hex", byteArrayOf(2), deviceId = overlong).status,
        )
        // Nothing rotated: the old password still logs in.
        assertEquals(HttpStatusCode.OK, client.srpLoginResponse(accountId, password, "devA", "A").status)
    }

    @Test
    fun `pairing claim refuses an overlong device id without burning the code`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val tokens = client.registerAccount(accountId, password)
        val start: PairingStartResponse = client.post("/pairing/start") {
            bearerAuth(tokens.accessToken)
            contentType(ContentType.Application.Json)
            setBody(PairingStartRequest(byteArrayOf(9).b64()))
        }.body()

        assertEquals(
            HttpStatusCode.BadRequest,
            client.post("/pairing/claim") {
                contentType(ContentType.Application.Json)
                setBody(PairingClaimRequest(start.code, overlong, "Phone B"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.post("/pairing/claim") {
                contentType(ContentType.Application.Json)
                setBody(PairingClaimRequest(start.code, "devB", "Phone B"))
            }.status,
        )
    }
}
