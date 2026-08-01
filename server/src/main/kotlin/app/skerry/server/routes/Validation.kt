package app.skerry.server.routes

import app.skerry.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.security.MessageDigest

// Upper bounds on client identifier length: keeps bloated strings out of the SRP/DB pending maps
// and out of memory before the overall body limit kicks in. Mirrors the schema (accountId varchar(320)).
internal const val MAX_ACCOUNT_ID = 320
internal const val MAX_OTHER_ID = 128

/** True if [accountId] is longer than [MAX_ACCOUNT_ID] or any other id is longer than [MAX_OTHER_ID]. */
internal fun tooLong(accountId: String, vararg otherIds: String): Boolean =
    accountId.length > MAX_ACCOUNT_ID || anyTooLong(*otherIds)

/** True if any identifier is longer than [MAX_OTHER_ID]. */
internal fun anyTooLong(vararg ids: String): Boolean = ids.any { it.length > MAX_OTHER_ID }

/** Required path parameter: responds 400 and returns null if missing or blank. */
internal suspend fun ApplicationCall.requiredPathId(name: String): String? {
    val value = parameters[name]
    if (value.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("$name is required"))
        return null
    }
    return value
}

/** `?limit=` query parameter with a default and hard bounds 1..[max], so lists can't grow unbounded. */
internal fun ApplicationCall.limitParam(default: Int, max: Int): Int =
    request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, max) ?: default

/**
 * `?offset=` query parameter: how many rows of the list to skip. Clamped at zero rather than
 * rejected — a reader holding a stale page number after rows were purged should land on an empty
 * page, and a negative offset is an SQL error waiting to happen, not a 400 worth writing.
 */
internal fun ApplicationCall.offsetParam(): Long =
    request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0

/**
 * Constant-time comparison of two long-lived static tokens (admin console, metrics scraper). Both
 * values are hashed to a fixed 32 bytes with SHA-256 first, then compared — otherwise
 * [MessageDigest.isEqual] on differing lengths returns early and leaks the token length via timing.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    val md = MessageDigest.getInstance("SHA-256")
    val ha = md.digest(a.toByteArray(Charsets.UTF_8))
    val hb = md.digest(b.toByteArray(Charsets.UTF_8)) // digest() resets md's state
    return MessageDigest.isEqual(ha, hb)
}
