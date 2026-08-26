package app.skerry.server.routes

import app.skerry.server.configureServer
import app.skerry.server.model.b64
import app.skerry.sync.wire.ChallengeRequest
import app.skerry.sync.wire.ChallengeResponse
import app.skerry.sync.wire.ChangePasswordRequest
import app.skerry.sync.wire.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #314: the SRP salt and verifier were stored exactly as the client sent them — both `text`
 * columns, neither checked for shape or length, on a surface where every other client-supplied
 * field is bounded. They are first parsed on the anonymous login path
 * ([app.skerry.server.auth.SrpService.startChallenge], `BigInteger(x, 16)`), so a non-hex verifier
 * made `/auth/srp/challenge` answer 500 for that account forever, and a four-million-digit one made
 * every anonymous challenge an arbitrarily expensive modexp.
 */
class SrpMaterialValidationTest {

    private suspend fun HttpClient.register(accountId: String, salt: String, verifier: String): HttpResponse =
        post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(accountId, salt, verifier, byteArrayOf(0).b64(), "devA", "Laptop A", null))
        }

    private suspend fun HttpClient.challenge(accountId: String): HttpResponse =
        post("/auth/srp/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(accountId))
        }

    private fun good(accountId: String, password: String) = srpRegister(accountId, password)

    @Test
    fun `a non-hex verifier is refused instead of locking the account out`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ok = good("x@example.com", "pw")

        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", ok.salt, "zz").status)
        // Nothing was stored, so the account does not exist and the anonymous challenge answers with
        // a synthesized one — never the 500 a stored "zz" produced on every attempt, forever.
        assertEquals(HttpStatusCode.OK, client.challenge("x@example.com").status)
    }

    @Test
    fun `a non-hex salt is refused`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ok = good("x@example.com", "pw")

        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", "not-hex", ok.verifier).status)
    }

    @Test
    fun `an empty salt or verifier is refused`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ok = good("x@example.com", "pw")

        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", "", ok.verifier).status)
        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", ok.salt, "").status)
    }

    /**
     * The body limit is 4 MiB, so an unbounded verifier is a multi-megabit integer that every
     * anonymous `/auth/srp/challenge` re-parses and multiplies. The group size is the bound: for the
     * configured 2048-bit N, anything past 512 hex digits is not a verifier.
     */
    @Test
    fun `a verifier larger than the SRP group is refused`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ok = good("x@example.com", "pw")

        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", ok.salt, "f".repeat(4096)).status)
        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", "f".repeat(4096), ok.verifier).status)
    }

    @Test
    fun `valid SRP material still registers and answers a challenge`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ok = good("x@example.com", "pw")

        assertEquals(HttpStatusCode.OK, client.register("x@example.com", ok.salt, ok.verifier).status)
        val challenge: ChallengeResponse = client.challenge("x@example.com").body()
        // Padded, not echoed: the value is what has to survive, and a real salt shorter than the
        // synthesized one would say the account exists (see the salt width in SrpService).
        assertEquals(BigInteger(ok.salt, 16), BigInteger(challenge.salt, 16))
    }

    /**
     * The synthesized challenge an unknown account gets is 32 bytes of HMAC — always 64 hex digits.
     * A client salt is `BigInteger(256, random).toString(16)`, which drops leading zeros, so one
     * real account in sixteen would answer with a shorter salt than any unknown one ever does: a
     * one-sided oracle that proves the account exists, on the route built to withhold exactly that.
     */
    @Test
    fun `a real salt is answered at the same width as a synthesized one`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val short = "0f".repeat(4) // 8 bytes: what a leading-zero-heavy 256-bit salt renders as
        val ok = good("x@example.com", "pw")
        assertEquals(HttpStatusCode.OK, client.register("x@example.com", short, ok.verifier).status)

        val real: ChallengeResponse = client.challenge("x@example.com").body()
        val unknown: ChallengeResponse = client.challenge("nobody@example.com").body()
        assertEquals(unknown.salt.length, real.salt.length, "the salt width says whether the account exists")
        assertEquals(BigInteger(short, 16), BigInteger(real.salt, 16), "padding changed the salt's value")
    }

    /**
     * The half of the fix that repairs accounts already stored with unparsable material: written
     * before this validation shipped, such a row answered 500 on every challenge, and there is no
     * path that rewrites it without logging in first — which needs that very challenge.
     */
    @Test
    fun `an account already stored with malformed material answers a challenge instead of failing`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        val client = createClient { install(ContentNegotiation) { json() } }
        services.accounts.create("x@example.com", "zz", "zz", byteArrayOf(0))

        val answered = client.challenge("x@example.com")
        assertEquals(HttpStatusCode.OK, answered.status)
        // And the proof against that synthesized challenge is refused as a wrong password would be,
        // not as a server fault.
        val challenge: ChallengeResponse = answered.body()
        val verified = client.post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                app.skerry.sync.wire.VerifyRequest(
                    challengeId = challenge.challengeId,
                    a = "01",
                    m1 = "01",
                    deviceId = "devA",
                    deviceName = "Laptop A",
                    platform = null,
                ),
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, verified.status)
    }

    /**
     * The salt is the one field with a tighter bound than the group: the protocol never needs more
     * than 256 bits, and a stored salt wider than the synthesized one is a one-sided "this account
     * exists" on the anonymous challenge route.
     */
    @Test
    fun `a salt wider than the protocol needs is refused at registration`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        val ok = good("x@example.com", "pw")

        assertEquals(HttpStatusCode.BadRequest, client.register("x@example.com", "a".repeat(MAX_SALT_HEX + 1), ok.verifier).status)
        assertEquals(HttpStatusCode.OK, client.register("x@example.com", "a".repeat(MAX_SALT_HEX), ok.verifier).status)
    }

    /** [MAX_SRP_HEX] is the configured group's width; the two are only linked by this assertion. */
    @Test
    fun `the hex bound matches the configured SRP group`() = testApplication {
        val services = testServices()
        application { configureServer(services) }
        assertEquals(services.srp.params.N.bitLength() / 4, MAX_SRP_HEX)
    }

    /**
     * The rotation path stores the same two fields. A malformed new verifier used to lock the
     * account out at the moment of the change: it is written, and from then on there is no path
     * that rewrites it without first logging in.
     */
    @Test
    fun `a malformed new verifier is refused and the account keeps its password`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount("x@example.com", "pw-1")

        val sc = srpClient("x@example.com", "pw-1")
        val challenge: ChallengeResponse = client.challenge("x@example.com").body()
        val creds = sc.step2(SRP_PARAMS, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))
        val rotated = client.post("/auth/change-password") {
            contentType(ContentType.Application.Json)
            setBody(
                ChangePasswordRequest(
                    challengeId = challenge.challengeId,
                    a = creds.A.toString(16),
                    m1 = creds.M1.toString(16),
                    deviceId = "devA",
                    deviceName = "Laptop A",
                    platform = null,
                    newSrpSalt = "zz",
                    newSrpVerifier = "zz",
                    newWrappedDataKey = byteArrayOf(1).b64(),
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, rotated.status)
        // The old password still works, and the challenge for it is still answerable.
        assertEquals(HttpStatusCode.OK, client.srpLoginResponse("x@example.com", "pw-1", "devA", "Laptop A").status)
    }

    /**
     * `A` and `M1` are parsed as hex on the same path, and a challenge id is free to anyone — the
     * challenge route is anonymous. Unchecked, they are the same 500 and the same unbounded modexp.
     */
    @Test
    fun `a proof that is not hexadecimal is refused rather than parsed`() = testApplication {
        application { configureServer(testServices()) }
        val client = createClient { install(ContentNegotiation) { json() } }
        client.registerAccount("x@example.com", "pw-1")
        val challenge: ChallengeResponse = client.challenge("x@example.com").body()

        val verified = client.post("/auth/srp/verify") {
            contentType(ContentType.Application.Json)
            setBody(
                app.skerry.sync.wire.VerifyRequest(
                    challengeId = challenge.challengeId,
                    a = "zz",
                    m1 = "zz",
                    deviceId = "devA",
                    deviceName = "Laptop A",
                    platform = null,
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, verified.status)
    }
}
