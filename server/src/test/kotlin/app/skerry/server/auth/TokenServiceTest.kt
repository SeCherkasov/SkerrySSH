package app.skerry.server.auth

import app.skerry.server.config.ServerConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.TokenExpiredException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TokenServiceTest {

    private fun config() = ServerConfig.fromEnv(
        mapOf("SKERRY_JWT_SECRET" to "test-secret", "SKERRY_ACCESS_TTL" to "900", "SKERRY_REFRESH_TTL" to "1000"),
    )

    @Test
    fun `access token carries account and device and verifies`() {
        val svc = TokenService(config())
        val token = svc.issueAccess("alice@example.com", "dev1", 0)
        val decoded = svc.verifier().verify(token)
        assertEquals("alice@example.com", decoded.subject)
        assertEquals("dev1", decoded.getClaim(TokenService.CLAIM_DEVICE).asString())
        assertEquals(TokenService.TYPE_ACCESS, decoded.getClaim(TokenService.CLAIM_TYPE).asString())
    }

    @Test
    fun `refresh verification rejects access tokens and honors type`() {
        val svc = TokenService(config())
        assertNull(svc.verifyRefresh(svc.issueAccess("alice@example.com", "dev1", 0)))
        assertNotNull(svc.verifyRefresh(svc.issueRefresh("alice@example.com", "dev1", 0)))
    }

    @Test
    fun `expired token is rejected`() {
        var now = 1_000_000L
        val svc = TokenService(config(), clock = { now })
        val token = svc.issueAccess("alice@example.com", "dev1", 0)
        now += 901_000 // past the 900-second TTL
        assertFailsWith<TokenExpiredException> { svc.verifier().verify(token) }
    }
    @Test
    fun `a token carries its device generation, and one from before the claim reads as 0`() {
        val svc = TokenService(config())
        assertEquals(7L, svc.generationOf(svc.verifier().verify(svc.issueAccess("alice@example.com", "dev1", 7))))

        val legacy = JWT.create()
            .withIssuer(config().jwtIssuer)
            .withSubject("alice@example.com")
            .withClaim(TokenService.CLAIM_DEVICE, "dev1")
            .withClaim(TokenService.CLAIM_TYPE, TokenService.TYPE_REFRESH)
            .sign(Algorithm.HMAC256("test-secret"))
        assertEquals(0L, svc.generationOf(svc.verifyRefresh(legacy)!!))
    }
}
