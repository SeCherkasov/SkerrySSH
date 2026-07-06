package app.skerry.server.auth

import com.nimbusds.srp6.SRP6ClientSession
import com.nimbusds.srp6.SRP6CryptoParams
import com.nimbusds.srp6.SRP6VerifierGenerator
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SrpServiceTest {

    private val params: SRP6CryptoParams = SRP6CryptoParams.getInstance(2048, "SHA-256")
    private val accountId = "alice@example.com"
    private val password = "correct-auth-key-hex" // in production this is authKey, not a raw password

    /** Client-side registration: salt + verifier, as the client would compute them before /auth/register. */
    private fun register(pwd: String = password): Pair<String, String> {
        val salt = BigInteger(256, SecureRandom())
        val verifier = SRP6VerifierGenerator(params).generateVerifier(salt, accountId, pwd)
        return salt.toString(16) to verifier.toString(16)
    }

    @Test
    fun `full SRP handshake authenticates and mutually proves`() {
        val (salt, verifier) = register()
        val srp = SrpService()

        val client = SRP6ClientSession()
        client.step1(accountId, password)

        val challenge = srp.startChallenge(accountId, salt, verifier)
        val creds = client.step2(params, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))

        val verified = srp.verify(challenge.challengeId, creds.A.toString(16), creds.M1.toString(16))
        assertNotNull(verified)
        assertEquals(accountId, verified.accountId)

        // Client verifies the server's counter-proof M2; no throw means the server is genuine.
        client.step3(BigInteger(verified.m2, 16))
    }

    @Test
    fun `wrong password fails verification`() {
        val (salt, verifier) = register()
        val srp = SrpService()

        val client = SRP6ClientSession()
        client.step1(accountId, "wrong-password")

        val challenge = srp.startChallenge(accountId, salt, verifier)
        val creds = client.step2(params, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))

        assertNull(srp.verify(challenge.challengeId, creds.A.toString(16), creds.M1.toString(16)))
    }

    @Test
    fun `challenge is one-shot and expires`() {
        val (salt, verifier) = register()
        var now = 0L
        val srp = SrpService(clock = { now }, challengeTtlMillis = 1_000)

        val client = SRP6ClientSession()
        client.step1(accountId, password)
        val challenge = srp.startChallenge(accountId, salt, verifier)
        val creds = client.step2(params, BigInteger(challenge.salt, 16), BigInteger(challenge.b, 16))

        // expired
        now = 2_000
        assertNull(srp.verify(challenge.challengeId, creds.A.toString(16), creds.M1.toString(16)))

        // unknown challenge
        assertNull(srp.verify("nope", creds.A.toString(16), creds.M1.toString(16)))
    }
}
