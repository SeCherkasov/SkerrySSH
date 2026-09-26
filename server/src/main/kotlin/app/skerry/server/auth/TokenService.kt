package app.skerry.server.auth

import app.skerry.server.config.ServerConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.Payload
import java.util.Date

/**
 * Issues and verifies JWTs (short TTL + refresh). A token is
 * bound to accountId and deviceId, and grants access only to ciphertext, never plaintext content.
 *
 * ## Refresh token revocation: a generation per device
 *
 * Refresh tokens are self-contained stateless JWTs: the server keeps no per-token DB record, so an
 * individual refresh token cannot be revoked on its own — a signed JWT stays valid until its `exp`.
 *
 * Revocation on compromise goes through **device revoke**: both `/auth/refresh` and the access
 * validator (`auth-jwt`) check the device (accountId+deviceId) on every request — it must not be
 * revoked, and the token's [CLAIM_GENERATION] must equal the device's current
 * [app.skerry.server.db.Devices.tokenGeneration]. Revoking a device moves that generation on, so its
 * tokens stay dead even after a re-login clears the revocation; a password change moves it on for
 * every device of the account, the acting one included (it gets fresh tokens in the response).
 * Rotating `SKERRY_JWT_SECRET` still invalidates every signature on the instance.
 *
 * A token issued before the claim existed reads as generation 0, which is what every device row
 * starts at — so an upgrade signs nobody out.
 */
class TokenService(private val config: ServerConfig, private val clock: () -> Long = System::currentTimeMillis) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    companion object {
        const val CLAIM_DEVICE = "did"
        const val CLAIM_TYPE = "typ"
        const val CLAIM_GENERATION = "gen"
        const val TYPE_ACCESS = "access"
        const val TYPE_REFRESH = "refresh"
    }

    fun issueAccess(accountId: String, deviceId: String, generation: Long): String =
        issue(accountId, deviceId, generation, TYPE_ACCESS, config.accessTokenTtlSeconds)

    fun issueRefresh(accountId: String, deviceId: String, generation: Long): String =
        issue(accountId, deviceId, generation, TYPE_REFRESH, config.refreshTokenTtlSeconds)

    private fun issue(accountId: String, deviceId: String, generation: Long, type: String, ttlSeconds: Long): String {
        val now = clock()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(accountId)
            .withClaim(CLAIM_DEVICE, deviceId)
            .withClaim(CLAIM_TYPE, type)
            .withClaim(CLAIM_GENERATION, generation)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + ttlSeconds * 1000))
            .sign(algorithm)
    }

    /** Verifier for Ktor `jwt {}`: checks signature and issuer (expiry is checked by Ktor/JWT itself). */
    fun verifier(): JWTVerifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).build()

    /** The device generation [token] was issued under; 0 for a token that predates the claim. */
    fun generationOf(token: Payload): Long = token.getClaim(CLAIM_GENERATION).asLong() ?: 0L

    /** Decodes and verifies a refresh token; `null` if it's not a refresh token, expired, or forged. */
    fun verifyRefresh(token: String): DecodedJWT? = try {
        val decoded = verifier().verify(token)
        if (decoded.getClaim(CLAIM_TYPE).asString() != TYPE_REFRESH) null else decoded
    } catch (_: Exception) {
        null
    }
}
