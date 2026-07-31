package app.skerry.server.routes

import app.skerry.server.RateLimits
import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.db.WebSession
import app.skerry.server.deviceId
import app.skerry.server.jwtPrincipal
import app.skerry.server.metrics.AuthKind
import app.skerry.server.metrics.AuthOutcome
import app.skerry.server.metrics.RegistrationRejection
import app.skerry.server.metrics.RevokedBy
import app.skerry.server.metrics.TokenType
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
import app.skerry.sync.wire.WebAccessResponse
import app.skerry.sync.wire.WebLoginRequest
import app.skerry.sync.wire.WebPasswordRequest
import app.skerry.server.model.unb64
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
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
                services.metrics.registrationRejected(RegistrationRejection.CLOSED)
                services.metrics.authAttempt(AuthKind.REGISTER, AuthOutcome.DENIED)
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
                services.metrics.registrationRejected(RegistrationRejection.CAP_REACHED)
                services.metrics.authAttempt(AuthKind.REGISTER, AuthOutcome.DENIED)
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
                services.metrics.authAttempt(AuthKind.REGISTER, AuthOutcome.ERROR)
                call.respond(HttpStatusCode.Conflict, ErrorResponse("account already exists"))
                return@post
            }
            services.devices.register(req.accountId, req.deviceId, req.deviceName, req.platform)
            services.activity.record(req.accountId, "auth.register", "new account + device", deviceId = req.deviceId)
            services.metrics.authAttempt(AuthKind.REGISTER, AuthOutcome.OK)
            services.metrics.tokensIssued(TokenType.ACCESS)
            services.metrics.tokensIssued(TokenType.REFRESH)
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
        // Deliberately no "account exists" dimension: it would hand an enumerator exactly the
        // signal the synthesized challenge above is built to withhold.
        services.metrics.authAttempt(AuthKind.SRP_CHALLENGE, AuthOutcome.OK)
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
            services.metrics.authAttempt(AuthKind.SRP_VERIFY, AuthOutcome.DENIED)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
            return@post
        }
        services.metrics.authAttempt(AuthKind.SRP_VERIFY, AuthOutcome.OK)
        services.metrics.tokensIssued(TokenType.ACCESS)
        services.metrics.tokensIssued(TokenType.REFRESH)
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
                // Tell the client a revoked device just came back so it re-mirrors the server before pushing.
                reactivated = reactivated,
            ),
        )
        }
    }

    // The limiter gates the route, so a 429 is always emitted before rotatePassword below ever runs.
    // The client relies on that: SyncCoordinator.changeAccountPassword treats 429 as "rejected before
    // any write" and keeps the device's auto-restore token. Don't move this check past receive()/
    // verify()/rotatePassword() — a 429 after a committed rotation would leave that device holding a
    // live token for a password the account no longer uses.
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
                services.metrics.authAttempt(AuthKind.CHANGE_PASSWORD, AuthOutcome.DENIED)
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
                services.metrics.authAttempt(AuthKind.CHANGE_PASSWORD, AuthOutcome.ERROR)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
                return@post
            }
            services.metrics.authAttempt(AuthKind.CHANGE_PASSWORD, AuthOutcome.OK)
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

    // The limiter gates the route, so a throttled attempt never reaches the Argon2 verification —
    // which is the expensive half and the one an attacker would rather make us run.
    rateLimit(RateLimits.WEB_LOGIN) {
        post("/auth/web-login") {
            val req = call.receive<WebLoginRequest>()
            if (tooLong(req.accountId)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("identifier too long"))
                return@post
            }
            if (!services.accounts.verifyWebPassword(req.accountId, req.password)) {
                // One answer for a wrong password, an account with no web access, and an account
                // that doesn't exist. Anything more specific is an enumeration signal, and the
                // verification above already spent the same time on all three.
                services.metrics.authAttempt(AuthKind.WEB_LOGIN, AuthOutcome.DENIED)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
                return@post
            }
            val reactivated = services.openWebSession(req.accountId)
            if (reactivated == null) {
                // The password was cleared while this sign-in was verifying it. Same answer as a
                // wrong one: the credential this request used no longer exists.
                services.metrics.authAttempt(AuthKind.WEB_LOGIN, AuthOutcome.DENIED)
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("authentication failed"))
                return@post
            }
            services.activity.record(
                req.accountId, "auth.web_login", "browser signed in", deviceId = WebSession.DEVICE_ID,
            )
            // Same signal the SRP path records: a revoked device that comes back looks active again
            // in the console with nothing to say why. Here it means the web password was cleared and
            // then set anew — the session the clear closed is open once more.
            if (reactivated) {
                services.activity.record(
                    req.accountId, "device.reenrolled", "revoked device re-enrolled", deviceId = WebSession.DEVICE_ID,
                )
            }
            services.metrics.authAttempt(AuthKind.WEB_LOGIN, AuthOutcome.OK)
            services.metrics.tokensIssued(TokenType.ACCESS)
            services.metrics.tokensIssued(TokenType.REFRESH)
            call.respond(
                TokenResponse(
                    accessToken = services.tokens.issueAccess(req.accountId, WebSession.DEVICE_ID),
                    refreshToken = services.tokens.issueRefresh(req.accountId, WebSession.DEVICE_ID),
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
            services.metrics.authAttempt(AuthKind.REFRESH, AuthOutcome.DENIED)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid refresh token"))
            return@post
        }
        services.metrics.authAttempt(AuthKind.REFRESH, AuthOutcome.OK)
        services.metrics.tokensIssued(TokenType.ACCESS)
        services.metrics.tokensIssued(TokenType.REFRESH)
        call.respond(
            TokenResponse(
                accessToken = services.tokens.issueAccess(accountId, deviceId),
                refreshToken = services.tokens.issueRefresh(accountId, deviceId),
            ),
        )
    }
    }
}

/**
 * Registers the browser as this account's web device. Returns whether a revoked device was
 * re-enrolled, or null when the web password is gone — see below.
 *
 * Verifying the password and writing this row are two round trips, and Argon2 makes the gap between
 * them wide. A clear landing inside it nulls the column and revokes the session; this registration
 * would then un-revoke the very session the clear had just closed, and hand it a fresh 30-day
 * refresh token. Re-reading the column after the write closes that in every interleaving: if the
 * clear committed first, the read here returns null and the session is revoked again before a token
 * is issued; if it commits after, its own revoke covers the row this call has already written.
 */
internal suspend fun Services.openWebSession(accountId: String): Boolean? {
    val reactivated = devices.register(
        accountId = accountId,
        deviceId = WebSession.DEVICE_ID,
        name = WebSession.DEVICE_NAME,
        platform = WebSession.PLATFORM,
    )
    if (accounts.webPasswordHash(accountId) != null) return reactivated
    // The result is deliberately ignored: whether this call or the concurrent clear did the
    // revoking, the row ends up revoked and no token is issued on this branch either way.
    devices.revoke(accountId, WebSession.DEVICE_ID)
    return null
}

/**
 * `POST /auth/web-password` — the app sets, rotates or clears the web password over its own
 * authenticated session. Installed under `authenticate("auth-jwt")` (see [configureServer]): the
 * password can only be changed by a device that already holds a live token, so it is never a way
 * back in for someone who lost one.
 */
fun Route.webPasswordRoute(services: Services) {
    // The state the app's Web access screen renders: a screen that cannot tell "no web access" from
    // "web access is on" would have to offer both buttons blind, and its "remove" would be a guess.
    // Only the hash's presence leaves the server — never the hash.
    get("/auth/web-password") {
        val accountId = call.jwtPrincipal().accountId
        call.respond(WebAccessResponse(services.accounts.webPasswordHash(accountId) != null))
    }

    post("/auth/web-password") {
        val principal = call.jwtPrincipal()
        val accountId = principal.accountId
        val password = call.receive<WebPasswordRequest>().password
        if (password == null || password.isEmpty()) {
            val revoked = services.accounts.clearWebPassword(accountId)
            if (revoked == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
                return@post
            }
            services.activity.record(
                accountId, "auth.web_password_set", "web access removed", deviceId = principal.deviceId,
            )
            // One line per closed session, the same event the console shows for any revocation: an
            // account log that only said "removed" would hide that a live browser was just cut off.
            revoked.forEach { deviceId ->
                services.activity.record(accountId, "device.revoked", "revoked $deviceId", deviceId = principal.deviceId)
                services.metrics.deviceRevoked(RevokedBy.USER)
            }
            call.respond(HttpStatusCode.NoContent)
            return@post
        }
        if (password.length !in MIN_WEB_PASSWORD..MAX_WEB_PASSWORD) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("web password must be $MIN_WEB_PASSWORD..$MAX_WEB_PASSWORD characters"),
            )
            return@post
        }
        // Read before the write: afterwards there is no way left to tell a first-time grant from a
        // rotation, and the two are different facts to whoever reads the log.
        val rotation = services.accounts.webPasswordHash(accountId) != null
        if (!services.accounts.setWebPassword(accountId, password)) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
            return@post
        }
        services.activity.record(
            accountId,
            "auth.web_password_set",
            if (rotation) "web password rotated" else "web access enabled",
            deviceId = principal.deviceId,
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * The web password protects metadata, not vault content, and online guessing is rate-limited — but
 * the hash is offline-guessable if the database leaks, so a floor is still worth having. The ceiling
 * only keeps an absurd input out of Argon2.
 */
private const val MIN_WEB_PASSWORD = 8
private const val MAX_WEB_PASSWORD = 256

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
