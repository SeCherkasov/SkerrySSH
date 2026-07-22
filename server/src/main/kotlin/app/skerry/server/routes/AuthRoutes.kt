package app.skerry.server.routes

import app.skerry.server.RateLimits
import app.skerry.server.Services
import app.skerry.sync.wire.ChallengeRequest
import app.skerry.sync.wire.ChallengeResponse
import app.skerry.sync.wire.ChangePasswordRequest
import app.skerry.sync.wire.ChangePasswordResponse
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
        // Anti-enumeration: a nonexistent account does NOT get a 404 (that would reveal which
        // accountIds are registered). Instead a structurally identical challenge is synthesized
        // with a deterministic fake salt and a real-shaped `B` computed from a pseudo-verifier.
        // Failure only surfaces at /auth/srp/verify (M1 mismatch or unknown challenge) —
        // externally indistinguishable from a wrong password on an existing account.
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
        // A revoked device returning with the correct password is a separate admin-console event:
        // revoke only invalidates tokens, so without this signal the admin wouldn't know the
        // device is active again.
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

    rateLimit(RateLimits.CHANGE_PASSWORD) {
        post("/auth/change-password") {
            val req = call.receive<ChangePasswordRequest>()
            if (tooLong(req.deviceId, req.challengeId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
                return@post
            }
            // Prove the CURRENT password before touching anything: the SRP proof (M1) is checked
            // against the account's current verifier, so a stolen access token alone can't rotate.
            // The proof also establishes the accountId (from the one-shot challenge).
            val verified = services.srp.verify(req.challengeId, req.a, req.m1)
            if (verified == null) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
                return@post
            }
            // base64-decode before writing to the DB: invalid payload -> 400, not 500.
            val newWrapped = req.newWrappedDataKey.unb64()
            // Ensure the acting device exists and is not revoked (rotatePassword keeps it while
            // revoking the others), so its fresh tokens below authenticate.
            services.devices.register(verified.accountId, req.deviceId, req.deviceName, req.platform)
            val syncSeq = services.accounts.rotatePassword(
                accountId = verified.accountId,
                newSrpSalt = req.newSrpSalt,
                newSrpVerifier = req.newSrpVerifier,
                newWrappedDataKey = newWrapped,
                keepDeviceId = req.deviceId,
            )
            if (syncSeq == null) {
                // Should not happen (the SRP proof implies the account exists), but stay defensive.
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
                return@post
            }
            services.activity.record(verified.accountId, "auth.password_changed", "account password rotated", deviceId = req.deviceId)
            // Nudge currently-connected devices: the revoked ones' sockets close on the next
            // emission (per-signal isRevoked check), dropping them to "reconnect" promptly instead
            // of only on their next server call. The acting device's cursor already equals syncSeq,
            // so it ignores the signal.
            services.notifier.publish(verified.accountId, syncSeq)
            call.respond(
                ChangePasswordResponse(
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
 * Deterministic fake SRP salt (hex) for a nonexistent account: HMAC-SHA256(server secret,
 * accountId). 32 bytes = 64 hex chars, the same length as a real client 256-bit salt, so the
 * challenge response is structurally indistinguishable from a real one. Stable across requests
 * (anti-enumeration: a repeated challenge for the same unknown accountId returns the same salt,
 * with no "account doesn't exist" signal).
 */
private fun fakeSalt(accountId: String, serverSecret: String): String =
    hmacSha256(serverSecret, "srp-fake-salt:$accountId").joinToString("") { "%02x".format(it) }

/**
 * Pseudo-verifier (hex) for the synthetic challenge: a BigInteger from HMAC, reduced into the
 * group (mod N, nonzero). Only needed so `SRP6ServerSession.step1` computes a plausible `B` of the
 * same shape as a real account; no password can match it, so verify always fails.
 */
private fun fakeVerifier(accountId: String, serverSecret: String, n: BigInteger): String {
    val raw = BigInteger(1, hmacSha256(serverSecret, "srp-fake-verifier:$accountId")).mod(n)
    val v = if (raw.signum() == 0) BigInteger.ONE else raw
    return v.toString(16)
}
