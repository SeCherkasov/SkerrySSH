package app.skerry.server.routes

import app.skerry.server.RateLimits
import app.skerry.server.Services
import app.skerry.sync.wire.ChallengeRequest
import app.skerry.sync.wire.ChallengeResponse
import app.skerry.server.model.ErrorResponse
import app.skerry.sync.wire.RefreshRequest
import app.skerry.sync.wire.RegisterRequest
import app.skerry.sync.wire.TokenResponse
import app.skerry.sync.wire.VerifyRequest
import app.skerry.sync.wire.VerifyResponse
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

/** Extract client IP, preferring X-Forwarded-For when the proxy is trusted. */
private fun io.ktor.server.application.ApplicationCall.clientIp(): String {
    val forwarded = request.headers["X-Forwarded-For"]
    if (!forwarded.isNullOrBlank()) return forwarded.substringBefore(",").trim()
    return request.local.remoteAddress.toString().removePrefix("/").split(":").first()
}

/**
 * Registration and login. The server sees only the SRP salt/verifier and the wrapped dataKey; the password and dataKey are never transmitted.
 */
fun Route.authRoutes(services: Services) {
    rateLimit(RateLimits.REGISTER) {
        post("/auth/register") {
            // Registration policy is checked before any work: a closed instance (Vaultwarden's
            // SIGNUPS_ALLOWED=false) rejects new accounts outright; existing accounts still log in
            // and pair new devices (those paths don't hit /auth/register).
            if (!services.config.registrationOpen) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("registration is closed"))
                return@post
            }
            val req = call.receive<RegisterRequest>()
            if (tooLong(req.accountId, req.deviceId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
                return@post
            }
            // Account IDs double as email identities (welcome mail, password-reset notices): require
            // a plausible email shape at registration. Existing non-email accounts still log in —
            // only /auth/register is gated.
            if (!EMAIL_RE.matches(req.accountId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("account id must be an email address"))
                return@post
            }
            // Optional per-instance cap (backstop for an instance left open). The count/create window
            // is a benign soft-limit race: the cap can overshoot by a few under concurrent registration,
            // never a security boundary. create() still enforces uniqueness.
            val cap = services.config.maxAccounts
            if (cap > 0 && services.accounts.count() >= cap) {
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("registration limit reached"))
                return@post
            }
            // base64-decode before writing to the DB: invalid payload -> 400, not 500.
            val wrapped = req.wrappedDataKey.unb64()
            try {
                services.accounts.create(
                    accountId = req.accountId,
                    srpSalt = req.srpSalt,
                    srpVerifier = req.srpVerifier,
                    wrappedDataKey = wrapped,
                )
            } catch (_: IllegalStateException) {
                // Existence check inside create() plus catching the PK race (PostgreSQL) -> a single 409.
                call.respond(HttpStatusCode.Conflict, ErrorResponse("account already exists"))
                return@post
            }
            val ip = call.clientIp()
            services.devices.register(req.accountId, req.deviceId, req.deviceName, req.platform, ip = ip)
            services.activity.record(req.accountId, "auth.register", "new account + device", deviceId = req.deviceId)
            // Fire-and-forget: welcome email
            services.sendWelcome(req.accountId)
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
        // Anti-enumeration: a nonexistent account does NOT get a 404 (that would reveal which
        // accountIds are registered). Instead a structurally identical challenge is synthesized
        // with a deterministic fake salt and a real-shaped `B` computed from a pseudo-verifier.
        // Failure only surfaces at /auth/srp/verify (M1 mismatch or unknown challenge) —
        // externally indistinguishable from a wrong password on an existing account.
        val (id, salt, verifier) = if (account != null) {
            Triple(account.id, account.srpSalt, account.srpVerifier)
        } else {
            val fakeSalt = fakeSalt(req.accountId, services.config.jwtSecret)
            val fakeVerifier = fakeVerifier(req.accountId, services.config.jwtSecret, services.srp.params.N).toString(16)
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
        val ip = call.clientIp()
        val oldIp = services.devices.getLastIp(verified.accountId, req.deviceId)
        val reactivated = services.devices.register(verified.accountId, req.deviceId, req.deviceName, req.platform, ip = ip)
        services.activity.record(verified.accountId, "auth.login", "srp login", deviceId = req.deviceId)
        // A revoked device returning with the correct password is a separate admin-console event:
        // revoke only invalidates tokens, so without this signal the admin wouldn't know the
        // device is active again.
        if (reactivated) {
            services.activity.record(verified.accountId, "device.reenrolled", "revoked device re-enrolled", deviceId = req.deviceId)
        }
        // Suspicious login: IP changed from previous session
        if (oldIp != null && ip != oldIp) {
            services.sendSuspiciousLogin(verified.accountId, req.deviceName, ip)
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

/** Deterministic fake salt per accountId — constant across process lifetime, 32 hex bytes. */
private fun fakeSalt(accountId: String, jwtSecret: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(jwtSecret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(accountId.toByteArray()).take(32).joinToString("") { "%02x".format(it) }
}

/** Deterministic fake verifier: v ≡ g^key mod N, where key = HMAC-SHA256(jwtSecret, accountId). */
private fun fakeVerifier(accountId: String, jwtSecret: String, n: BigInteger): BigInteger {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(jwtSecret.toByteArray(), "HmacSHA256"))
    val x = BigInteger(1, mac.doFinal(accountId.toByteArray()))
    return BigInteger("2").modPow(x, n)
}
