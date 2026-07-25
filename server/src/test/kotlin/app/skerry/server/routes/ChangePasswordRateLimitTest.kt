package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.sync.wire.ChangePasswordRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The rate limiter gates the route, so a throttled rotation is rejected before it can reach
 * rotatePassword. The client leans on that: SyncCoordinator.changeAccountPassword treats a 429 as
 * "nothing was written" and keeps the device's auto-restore token. If this ever inverts — a limiter
 * that counts inside the handler, a check moved after verify() — the device would hold a live token
 * for a password the account no longer uses, and only this test would notice.
 */
class ChangePasswordRateLimitTest {

    private val account = "alice@example.com"
    private val oldPassword = "pw-hex-old"
    private val newPassword = "pw-hex-new"

    /** Well-formed request that can't rotate anything (unknown challenge) — burns one bucket slot. */
    private fun spentAttempt(n: Int) = ChangePasswordRequest(
        challengeId = "no-such-challenge-$n",
        a = "1",
        m1 = "1",
        deviceId = "devA",
        deviceName = "Laptop A",
        platform = null,
        newSrpSalt = "aa",
        newSrpVerifier = "bb",
        newWrappedDataKey = "AA==",
    )

    @Test
    fun `a rotation rejected by the rate limiter leaves the account password untouched`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount(account, oldPassword)

        // Exhaust the per-IP bucket (10/min) without touching the SRP challenge endpoint, which has
        // its own bucket the valid attempt below still needs.
        repeat(10) { n ->
            val resp = client.post("/auth/change-password") {
                contentType(ContentType.Application.Json)
                setBody(spentAttempt(n))
            }
            assertEquals(HttpStatusCode.Unauthorized, resp.status, "attempt $n should fail on SRP, not on the limiter")
        }

        // A fully valid rotation — correct current password, fresh challenge, real new verifier.
        // It must be turned away by the limiter, not served.
        val throttled = client.changePassword(account, oldPassword, newPassword, byteArrayOf(1))
        assertEquals(HttpStatusCode.TooManyRequests, throttled.status)

        // The verifier never moved: the old password still logs in, the new one doesn't exist.
        assertEquals(
            HttpStatusCode.OK,
            client.srpLoginResponse(account, oldPassword, "devA", "Laptop A").status,
            "a throttled rotation must not have changed the password",
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.srpLoginResponse(account, newPassword, "devA", "Laptop A").status,
            "the new password must not work — the rotation never happened",
        )
    }
}
