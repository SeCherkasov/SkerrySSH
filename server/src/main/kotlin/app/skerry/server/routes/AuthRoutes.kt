package app.skerry.server.routes

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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Регистрация и вход. Сервер видит только SRP-соль/верификатор и обёртку dataKey
 * (`docs/skerry-sync-design.md` §3); пароль и dataKey не передаются.
 */
fun Route.authRoutes(services: Services) {
    post("/auth/register") {
        val req = call.receive<RegisterRequest>()
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

    post("/auth/srp/challenge") {
        val req = call.receive<ChallengeRequest>()
        val account = services.accounts.find(req.accountId)
        if (account == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
            return@post
        }
        val challenge = services.srp.startChallenge(account.id, account.srpSalt, account.srpVerifier)
        call.respond(ChallengeResponse(challenge.challengeId, challenge.salt, challenge.b))
    }

    post("/auth/srp/verify") {
        val req = call.receive<VerifyRequest>()
        val verified = services.srp.verify(req.challengeId, req.a, req.m1)
        if (verified == null) {
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
            return@post
        }
        services.devices.register(verified.accountId, req.deviceId, req.deviceName, req.platform)
        services.activity.record(verified.accountId, "auth.login", "srp login", deviceId = req.deviceId)
        call.respond(
            VerifyResponse(
                m2 = verified.m2,
                accessToken = services.tokens.issueAccess(verified.accountId, req.deviceId),
                refreshToken = services.tokens.issueRefresh(verified.accountId, req.deviceId),
            ),
        )
    }

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
