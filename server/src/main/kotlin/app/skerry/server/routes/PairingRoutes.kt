package app.skerry.server.routes

import app.skerry.server.RateLimits
import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.PairingClaimRequest
import app.skerry.server.model.PairingClaimResponse
import app.skerry.server.model.PairingStartRequest
import app.skerry.server.model.PairingStartResponse
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

/** Случайный URL-safe код паринга (capability) — 18 байт энтропии. */
private fun newCode(): String {
    val bytes = ByteArray(18)
    rng.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Старт паринга (вариант B): вошедшее устройство A кладёт dataKey, зашифрованный одноразовым
 * transferKey (transferKey уходит только в QR, не на сервер), и получает код с TTL.
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
 * Claim паринга устройством B (ещё не вошло): по коду получает зашифрованный dataKey и токены.
 * Код одноразовый и с TTL; сервер видит только шифротекст dataKey.
 */
fun Route.pairingClaimRoute(services: Services) {
    rateLimit(RateLimits.PAIRING_CLAIM) {
        post("/pairing/claim") {
            val req = call.receive<PairingClaimRequest>()
            // Валидация ДО consume: невалидный запрос не должен сжигать одноразовый код, а
            // сверхдлинный deviceId на PostgreSQL валил бы insert в varchar-колонку 500-й.
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
