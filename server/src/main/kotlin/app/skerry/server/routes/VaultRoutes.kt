package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.deviceId
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.ErrorResponse
import app.skerry.sync.wire.KeysResponse
import app.skerry.sync.wire.PushRequest
import app.skerry.sync.wire.PushResponse
import app.skerry.sync.wire.RecordsResponse
import app.skerry.server.model.b64
import app.skerry.server.model.toDto
import app.skerry.server.model.toIncoming
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** Allowed values for the plaintext `type` field (mirrors core `RecordType`). */
private val ALLOWED_TYPES = setOf(
    "HOST", "GROUP", "IDENTITY", "CREDENTIAL", "KNOWN_HOST", "SNIPPET", "TUNNEL", "SETTINGS",
    "TEAM", "TEAM_IDENTITY",
)

/** Ciphertext storage: dataKey wrapping, delta reads, and batch push with LWW. */
fun Route.vaultRoutes(services: Services) {
    get("/vault/keys") {
        val principal = call.jwtPrincipal()
        val account = services.accounts.find(principal.accountId)
        if (account == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("no such account"))
            return@get
        }
        services.devices.touch(principal.accountId, principal.deviceId)
        call.respond(KeysResponse(account.wrappedDataKey.b64()))
    }

    get("/vault/records") {
        val principal = call.jwtPrincipal()
        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
        val delta = services.records.delta(principal.accountId, since)
        val cursor = delta.lastOrNull()?.serverSeq ?: since
        // Record activity and the cursor the device has read up to (for the admin console).
        services.devices.touch(principal.accountId, principal.deviceId, syncVersion = cursor)
        // Log only non-empty pulls; empty polls shouldn't clutter the audit log.
        if (delta.isNotEmpty()) {
            services.activity.record(
                principal.accountId, "sync.pull", "delta since $since · ${delta.size} records",
                deviceId = principal.deviceId,
            )
        }
        // After touch, so this device's cursor is already reflected in the watermark. Tombstone ids
        // that all devices have read; the client compacts them locally and stops re-pushing them.
        val compactedIds = services.records.compactedTombstoneIds(principal.accountId)
        call.respond(RecordsResponse(delta.map { it.toDto() }, cursor, compactedIds))
    }

    put("/vault/records") {
        val principal = call.jwtPrincipal()
        val req = call.receive<PushRequest>()
        val unknown = req.records.firstOrNull { it.type !in ALLOWED_TYPES }
        if (unknown != null) throw BadRequestException("unknown record type: ${unknown.type}")

        val result = services.records.upsert(principal.accountId, req.records.map { it.toIncoming() })
        services.devices.touch(principal.accountId, principal.deviceId, syncVersion = result.cursor)
        services.activity.record(
            principal.accountId, "sync.push", "${req.records.size} records · cursor ${result.cursor}",
            deviceId = principal.deviceId,
        )
        // Notify other devices on the account: "changes exist up to cursor" (no payload). Published only
        // when the cursor actually advanced; a no-op push (same version+deviceId, wins=false) publishes
        // nothing, otherwise live-sync would loop push -> WS -> push (another/the same device pulls the
        // delta and pushes it back as a no-op, waking WS again).
        if (result.changed) services.notifier.publish(principal.accountId, result.cursor)
        call.respond(PushResponse(result.records.map { it.toDto() }, result.cursor))
    }
}
