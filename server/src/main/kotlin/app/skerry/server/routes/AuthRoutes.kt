package app.skerry.server.routes

import app.skerry.server.RateLimits
import app.skerry.server.Services
import app.skerry.server.model.ChallengeRequest
import app.skerry.server.model.ChallengeResponse
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.RefreshRequest
import app.skerry.server.model.RegisterRequest
import app.skerry.server.model.TokenResponse
import app.skerry.server.model.VerifyRequest
import app.skerry.server.model.VerifyResponse
import app.skerry.server.model.unb64
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Регистрация и вход. Сервер видит только SRP-соль/верификатор и обёртку dataKey
 * (`docs/skerry-sync-design.md` §3); пароль и dataKey не передаются.
 */
fun Route.authRoutes(services: Services) {
    rateLimit(RateLimits.REGISTER) {
        post("/auth/register") {
            val req = call.receive<RegisterRequest>()
            if (tooLong(req.accountId, req.deviceId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
                return@post
            }
            // base64-декод до записи в БД: невалидный payload → 400 (BadRequestException), не 500.
            val wrapped = req.wrappedDataKey.unb64()
            try {
                services.accounts.create(
                    accountId = req.accountId,
                    srpSalt = req.srpSalt,
                    srpVerifier = req.srpVerifier,
                    wrappedDataKey = wrapped,
                )
            } catch (_: IllegalStateException) {
                // exists-проверка внутри create + перехват PK-гонки (PostgreSQL) → единый 409.
                call.respond(HttpStatusCode.Conflict, ErrorResponse("account already exists"))
                return@post
            }
            services.devices.register(req.accountId, req.deviceId, req.deviceName, req.platform)
            services.activity.record(req.accountId, "auth.register", "new account + device", deviceId = req.deviceId)
            call.respond(
                TokenResponse(
                    accessToken = services.tokens.issueAccess(req.accountId, req.deviceId),
                    refreshToken = services.tokens.issueRefresh(req.accountId, req.deviceId),
                ),
            )
        }
    }

    rateLimit(RateLimits.SRP_CHALLENGE) {
        post("/auth/srp/challenge") {
        val req = call.receive<ChallengeRequest>()
        if (tooLong(req.accountId)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
            return@post
        }
        val account = services.accounts.find(req.accountId)
        // Anti-enumeration: для несуществующего аккаунта НЕ отвечаем 404 (это раскрыло бы, какие
        // accountId зарегистрированы). Вместо этого синтезируем структурно идентичный challenge с
        // детерминированной фейковой солью и настоящим (по форме) `B`, посчитанным из псевдо-
        // верификатора. Реальный провал происходит лишь на /auth/srp/verify (M1 не сойдётся, либо
        // challenge неизвестен) — внешне неотличимо от неверного пароля у существующего аккаунта.
        val (id, salt, verifier) = if (account != null) {
            Triple(account.id, account.srpSalt, account.srpVerifier)
        } else {
            val fakeSalt = fakeSalt(req.accountId, services.config.jwtSecret)
            val fakeVerifier = fakeVerifier(req.accountId, services.config.jwtSecret, services.srp.params.N)
            Triple(req.accountId, fakeSalt, fakeVerifier)
        }
        val challenge = services.srp.startChallenge(id, salt, verifier)
        call.respond(ChallengeResponse(challenge.challengeId, challenge.salt, challenge.b))
        }
    }

    rateLimit(RateLimits.SRP_VERIFY) {
        post("/auth/srp/verify") {
        val req = call.receive<VerifyRequest>()
        if (tooLong(req.deviceId, req.challengeId)) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
            return@post
        }
        val verified = services.srp.verify(req.challengeId, req.a, req.m1)
        if (verified == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
            return@post
        }
        val reactivated = services.devices.register(verified.accountId, req.deviceId, req.deviceName, req.platform)
        services.activity.record(verified.accountId, "auth.login", "srp login", deviceId = req.deviceId)
        // Возврат отозванного устройства с верным паролем — отдельное событие для админ-консоли:
        // revoke лишь гасит токены, и без этого сигнала админ не узнал бы, что устройство снова активно.
        if (reactivated) {
            services.activity.record(verified.accountId, "device.reenrolled", "revoked device re-enrolled", deviceId = req.deviceId)
        }
        call.respond(
            VerifyResponse(
                m2 = verified.m2,
                accessToken = services.tokens.issueAccess(verified.accountId, req.deviceId),
                refreshToken = services.tokens.issueRefresh(verified.accountId, req.deviceId),
            ),
        )
        }
    }

    rateLimit(RateLimits.REFRESH) {
    post("/auth/refresh") {
        val req = call.receive<RefreshRequest>()
        val decoded = services.tokens.verifyRefresh(req.refreshToken)
        val deviceId = decoded?.getClaim("did")?.asString()
        val accountId = decoded?.subject
        if (decoded == null || deviceId == null || accountId == null ||
            services.devices.isRevoked(accountId, deviceId)
        ) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid refresh token"))
            return@post
        }
        call.respond(
            TokenResponse(
                accessToken = services.tokens.issueAccess(accountId, deviceId),
                refreshToken = services.tokens.issueRefresh(accountId, deviceId),
            ),
        )
    }
    }
}

private fun hmacSha256(secret: String, message: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8))
}

/**
 * Детерминированная фейковая SRP-соль (hex) для несуществующего аккаунта: HMAC-SHA256(серверный
 * секрет, accountId). 32 байта = 64 hex-символа — та же длина, что у клиентской 256-битной соли,
 * так что ответ challenge структурно неотличим от настоящего. Стабильна между запросами (анти-
 * enumeration: повторный challenge того же неизвестного accountId даёт ТУ ЖЕ соль, без сигнала
 * «аккаунта нет»).
 */
private fun fakeSalt(accountId: String, serverSecret: String): String =
    hmacSha256(serverSecret, "srp-fake-salt:$accountId").joinToString("") { "%02x".format(it) }

/**
 * Псевдо-верификатор (hex) для синтетического challenge: BigInteger из HMAC, приведённый в группу
 * (mod N, ненулевой). Нужен лишь чтобы `SRP6ServerSession.step1` посчитал правдоподобный `B` той же
 * формы, что у реального аккаунта; знать пароль под него невозможно, поэтому verify всегда падает.
 */
private fun fakeVerifier(accountId: String, serverSecret: String, n: BigInteger): String {
    val raw = BigInteger(1, hmacSha256(serverSecret, "srp-fake-verifier:$accountId")).mod(n)
    val v = if (raw.signum() == 0) BigInteger.ONE else raw
    return v.toString(16)
}
