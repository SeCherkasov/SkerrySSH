package app.skerry.server.routes

import app.skerry.server.RateLimits
import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.ErrorResponse
import app.skerry.sync.wire.PairingClaimRequest
import app.skerry.sync.wire.PairingClaimResponse
import app.skerry.sync.wire.PairingStartRequest
import app.skerry.sync.wire.PairingStartResponse
import app.skerry.server.model.b64
import app.skerry.server.model.unb64
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.security.SecureRandom
import java.util.Base64

private val rng = SecureRandom()

/** Random URL-safe pairing code (capability token), 18 bytes of entropy. */
private fun newCode(): String {
    val bytes = ByteArray(18)
    rng.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Start pairing (variant B): the logged-in device A uploads dataKey encrypted with a one-time
 * transferKey (transferKey goes only into the QR code, never to the server) and gets a code with a TTL.
 */
fun Route.pairingStartRoute(services: Services) {
    post("/pairing/start") {
        val principal = call.jwtPrincipal()
        val req = call.receive<PairingStartRequest>()
        val ttl = (req.ttlSeconds ?: services.config.pairingTtlSeconds).coerceIn(30, 3600)
        val expiresAt = System.currentTimeMillis() + ttl * 1000
        val code = newCode()
        services.pairing.create(code, principal.accountId, req.encryptedDataKey.unb64(), expiresAt)
        call.respond(PairingStartResponse(code, expiresAt))
    }
}

/**
 * Claim pairing from device B (not yet logged in): exchanges the code for the encrypted dataKey
 * and tokens. The code is one-time with a TTL; the server sees only dataKey ciphertext.
 */
fun Route.pairingClaimRoute(services: Services) {
    rateLimit(RateLimits.PAIRING_CLAIM) {
        post("/pairing/claim") {
            val req = call.receive<PairingClaimRequest>()
            // Validate before consume: an invalid request must not burn the one-time code, and an
            // oversized deviceId would fail the insert into a varchar column on PostgreSQL with a 500.
            if (anyTooLong(req.code, req.deviceId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
                return@post
            }
            val session = services.pairing.consume(req.code)
            if (session == null) {
                call.respond(HttpStatusCode.Gone, ErrorResponse("pairing code invalid or expired"))
                return@post
            }
            services.devices.register(session.accountId, req.deviceId, req.deviceName)
            call.respond(
                PairingClaimResponse(
                    accountId = session.accountId,
                    encryptedDataKey = session.encryptedDataKey.b64(),
                    accessToken = services.tokens.issueAccess(session.accountId, req.deviceId),
                    refreshToken = services.tokens.issueRefresh(session.accountId, req.deviceId),
                ),
            )
        }
    }
}
