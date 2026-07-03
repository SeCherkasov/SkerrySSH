package app.skerry.server.routes

import app.skerry.server.model.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

// Верхние границы длины клиентских идентификаторов: не даём раздутым строкам оседать в pending-картах
// SRP/БД и давить на память ещё до общего лимита тела. Зеркалят схему (accountId varchar(320)).
internal const val MAX_ACCOUNT_ID = 320
internal const val MAX_OTHER_ID = 128

/** true, если [accountId] длиннее [MAX_ACCOUNT_ID] либо любой из прочих — длиннее [MAX_OTHER_ID]. */
internal fun tooLong(accountId: String, vararg otherIds: String): Boolean =
    accountId.length > MAX_ACCOUNT_ID || anyTooLong(*otherIds)

/** true, если любой из идентификаторов длиннее [MAX_OTHER_ID]. */
internal fun anyTooLong(vararg ids: String): Boolean = ids.any { it.length > MAX_OTHER_ID }

/** Обязательный path-параметр: при отсутствии или пустоте отвечает 400 и возвращает null. */
internal suspend fun ApplicationCall.requiredPathId(name: String): String? {
    val value = parameters[name]
    if (value.isNullOrBlank()) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("$name is required"))
        return null
    }
    return value
}

/** Параметр `?limit=` с дефолтом и жёсткими границами 1..[max] — списки не растут безгранично. */
internal fun ApplicationCall.limitParam(default: Int, max: Int): Int =
    request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, max) ?: default
