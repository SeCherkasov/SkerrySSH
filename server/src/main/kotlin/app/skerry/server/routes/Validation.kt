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

/**
 * Longest SRP field the 2048-bit group can hold, in hex digits. The verifier is `g^x mod N`, so
 * anything past this is not a verifier at all; the salt is a 256-bit client random and fits many
 * times over.
 *
 * The bound is what keeps the anonymous `/auth/srp/challenge` cheap: it re-parses the stored
 * verifier and multiplies it on every request, so without a cap the body limit (4 MiB) would decide
 * the cost of an unauthenticated request — a 16-megabit modexp per call (#314). The rate limiter
 * bounds how often that happens, never how much each one costs.
 */
internal const val MAX_SRP_HEX = 2048 / 4

/**
 * Whether a client-supplied SRP salt or verifier is storable: non-empty, hexadecimal, and no wider
 * than the group. Not `< N` — Nimbus reduces mod N and still refuses `A mod N == 0`, so the width
 * is what this has to bound. Both are parsed with `BigInteger(value, 16)` on the login path, which throws
 * `NumberFormatException` on anything else — and that is not a `BadRequestException`, so a value
 * stored unchecked turned every later challenge for the account into a 500, with no path that
 * rewrites it short of logging in first (#314).
 */
internal fun isSrpHex(value: String): Boolean =
    value.isNotEmpty() && value.length <= MAX_SRP_HEX && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** True if any of these SRP fields is not storable/parsable ([isSrpHex]). */
internal fun malformedSrp(vararg values: String): Boolean = values.any { !isSrpHex(it) }

/**
 * Hex digits a salt may have. The client's is a 256-bit random and the synthesized one an unknown
 * account gets is 32 bytes of HMAC, so 64 is what the protocol ever needs — and a wider stored salt
 * would answer wider than any synthesized one, which is a one-sided "this account exists". Only new
 * writes are held to it: the read path stays on [isSrpHex] so a row that predates the rule keeps
 * answering instead of reading as an account that does not exist.
 */
internal const val MAX_SALT_HEX = 64

/** Whether a client-supplied salt is storable ([isSrpHex]) and no wider than [MAX_SALT_HEX]. */
internal fun malformedSalt(value: String): Boolean = !isSrpHex(value) || value.length > MAX_SALT_HEX

/**
 * A client-chosen id, made safe to put in a log line.
 *
 * Not only the control characters: `isISOControl` stops at U+009F, while U+2028/U+2029 are line
 * breaks to every JSON- or JS-based log viewer and the bidi controls reverse everything after them,
 * so an id ending in one draws the rest of the entry out of order. An account id is bounded in
 * length and in nothing else (see [MAX_ACCOUNT_ID]), so all three categories have to go.
 */
internal fun logSafe(value: String): String =
    value.map { if (it.isISOControl() || it.category in LOG_UNSAFE) '?' else it }.joinToString("")

private val LOG_UNSAFE = setOf(
    CharCategory.FORMAT,
    CharCategory.LINE_SEPARATOR,
    CharCategory.PARAGRAPH_SEPARATOR,
)

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
