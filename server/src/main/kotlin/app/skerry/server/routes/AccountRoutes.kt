package app.skerry.server.routes

import app.skerry.server.SERVER_VERSION
import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.AccountActivityDto
import app.skerry.server.model.AccountActivityResponse
import app.skerry.server.model.ErrorResponse
import app.skerry.sync.wire.AccountSummaryResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * What an account may read about itself: the totals and the audit rows the operator console shows
 * for one row of its tables, scoped to the caller's own JWT.
 *
 * Read-only projections of metadata the server already holds in the clear. Nothing here touches a
 * record blob — the account's ciphertext is served by `GET /vault/envelopes` as sizes and a preview,
 * and by nothing else.
 */
fun Route.accountRoutes(services: Services) {
    get("/account/summary") {
        val accountId = call.jwtPrincipal().accountId
        val summary = services.admin.accountSummary(accountId)
        if (summary == null) {
            // Unreachable in practice — deleting an account takes its devices with it, so the JWT
            // check refuses the token first — but the alternative to answering here is a 500 built
            // out of a `!!`, and the account row is not this route's invariant to assume.
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
            return@get
        }
        call.respond(
            AccountSummaryResponse(
                accountId = summary.id,
                createdAt = summary.createdAt,
                syncSeq = summary.syncSeq,
                devices = summary.devices,
                activeDevices = summary.activeDevices,
                records = summary.records,
                tombstones = summary.tombstones,
                storageBytes = summary.storageBytes,
                lastSeenAt = summary.lastSeenAt,
                serverVersion = SERVER_VERSION,
            ),
        )
    }

    get("/account/activity") {
        val accountId = call.jwtPrincipal().accountId
        val limit = call.limitParam(default = 100, max = 500)
        val total = services.activity.countForAccount(accountId)
        val events = services.activity.recentForAccount(accountId, limit, call.offsetParam()).map {
            AccountActivityDto(it.deviceId, it.event, it.detail, it.createdAt)
        }
        call.respond(AccountActivityResponse(events, total))
    }
}
