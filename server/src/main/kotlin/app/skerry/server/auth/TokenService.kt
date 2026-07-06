package app.skerry.server.auth

import app.skerry.server.config.ServerConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.util.Date

/**
 * Issues and verifies JWTs (`docs/skerry-sync-design.md` §4: short TTL + refresh). A token is
 * bound to accountId and deviceId, and grants access only to ciphertext, never plaintext content.
 *
 * ## Refresh token revocation: stateless by design
 *
 * Refresh tokens are self-contained stateless JWTs: the server keeps no per-token DB record, so an
 * individual refresh token cannot be revoked on its own — a signed JWT stays valid until its `exp`.
 *
 * Revocation on compromise goes through **device revoke**: both `/auth/refresh` and the access
 * validator (`auth-jwt`) check [app.skerry.server.db.DeviceRepository.isRevoked] (accountId+deviceId)
 * on every request. Revoking a device instantly cuts off both its access and refresh flow without
 * touching other devices. A global revoke of all of an account's tokens means rotating
 * `SKERRY_JWT_SECRET` (invalidates all signatures) or changing the master password (rotates the
 * SRP verifier).
 *
 * A generation counter / DB-backed refresh token list is intentionally not implemented: device
 * revoke already gives the needed granularity for the single-instance model.
 */
class TokenService(private val config: ServerConfig, private val clock: () -> Long = System::currentTimeMillis) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.jwtSecret)

    companion object {
        const val CLAIM_DEVICE = "did"
        const val CLAIM_TYPE = "typ"
        const val TYPE_ACCESS = "access"
        const val TYPE_REFRESH = "refresh"
    }

    fun issueAccess(accountId: String, deviceId: String): String =
        issue(accountId, deviceId, TYPE_ACCESS, config.accessTokenTtlSeconds)

    fun issueRefresh(accountId: String, deviceId: String): String =
        issue(accountId, deviceId, TYPE_REFRESH, config.refreshTokenTtlSeconds)

    private fun issue(accountId: String, deviceId: String, type: String, ttlSeconds: Long): String {
        val now = clock()
        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withSubject(accountId)
            .withClaim(CLAIM_DEVICE, deviceId)
            .withClaim(CLAIM_TYPE, type)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + ttlSeconds * 1000))
            .sign(algorithm)
    }

    /** Verifier for Ktor `jwt {}`: checks signature and issuer (expiry is checked by Ktor/JWT itself). */
    fun verifier(): JWTVerifier = JWT.require(algorithm).withIssuer(config.jwtIssuer).build()

    /** Decodes and verifies a refresh token; `null` if it's not a refresh token, expired, or forged. */
    fun verifyRefresh(token: String): DecodedJWT? = try {
        val decoded = verifier().verify(token)
        if (decoded.getClaim(CLAIM_TYPE).asString() != TYPE_REFRESH) null else decoded
    } catch (_: Exception) {
        null
    }
}
