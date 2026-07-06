package app.skerry.server.routes

import app.skerry.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

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
