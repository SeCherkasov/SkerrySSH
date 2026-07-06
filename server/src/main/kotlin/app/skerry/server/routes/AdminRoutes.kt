package app.skerry.server.routes

import app.skerry.server.SERVER_VERSION
import app.skerry.server.Services
import app.skerry.server.model.AdminAccountDto
import app.skerry.server.model.AdminAccountsResponse
import app.skerry.server.model.AdminActivityDto
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDeviceDto
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminPurgeResponse
import app.skerry.server.model.AdminRecordDto
import app.skerry.server.model.AdminRecordsResponse
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.HealthResponse
import app.skerry.server.model.StatsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.security.MessageDigest

/**
 * Admin endpoints for the self-hosted console. `/admin/health` is open (liveness); the rest of
 * the `/admin` subtree is gated by the static [app.skerry.server.config.ServerConfig.adminToken]
 * (a separate admin role), checked by one route-scoped
 * interceptor. Zero-knowledge holds: only metadata (counts, device list) is served, no access to
 * record content.
 */
fun Route.adminRoutes(services: Services) {
    get("/admin/health") {
        call.respond(HealthResponse("ok", SERVER_VERSION))
    }

    // Guard on a transparent child node (like authenticate {}): routing merges identical
    // selectors, so a plugin installed directly on route("/admin") would also cover the open
    // /admin/health above.
    val guarded = route("/admin") {}.createChild(AdminGuardSelector())
    // Single route-scoped admin-token check for the whole subtree: on failure the plugin
    // responds 401 itself and aborts the pipeline before any route handler runs.
    guarded.install(AdminAuth) { token = services.config.adminToken }

    with(guarded) {
        get("/stats") {
            val c = services.stats.counts()
            call.respond(StatsResponse(c.accounts, c.devices, c.records, c.pairingSessions, c.storageBytes))
        }

        get("/devices") {
            val limit = call.limitParam(default = 200, max = 500)
            val total = services.devices.count()
            val devices = services.devices.listAll(limit).map {
                AdminDeviceDto(
                    accountId = it.accountId,
                    id = it.id,
                    name = it.name,
                    platform = it.platform,
                    createdAt = it.createdAt,
                    lastSeenAt = it.lastSeenAt,
                    syncVersion = it.lastSyncVersion,
                    revoked = it.revoked,
                )
            }
            call.respond(AdminDevicesResponse(devices, total))
        }

        get("/activity") {
            val limit = call.limitParam(default = 50, max = 2000)
            val total = services.activity.count()
            val events = services.activity.recent(limit).map {
                AdminActivityDto(it.accountId, it.deviceId, it.event, it.detail, it.createdAt)
            }
            call.respond(AdminActivityResponse(events, total))
        }

        delete("/devices/{id}") {
            val deviceId = call.parameters["id"]
            val accountId = call.request.queryParameters["accountId"]
            if (deviceId.isNullOrBlank() || accountId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("accountId and id are required"))
                return@delete
            }
            val revoked = services.devices.revoke(accountId, deviceId)
            if (revoked) {
                services.activity.record(accountId, "device.revoked", "admin-revoked $deviceId")
            }
            call.respond(if (revoked) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }

        get("/accounts") {
            val limit = call.limitParam(default = 100, max = 1000)
            val total = services.admin.accountCount()
            val accounts = services.admin.accountSummaries(limit).map {
                AdminAccountDto(
                    id = it.id,
                    createdAt = it.createdAt,
                    syncSeq = it.syncSeq,
                    devices = it.devices,
                    activeDevices = it.activeDevices,
                    records = it.records,
                    tombstones = it.tombstones,
                    storageBytes = it.storageBytes,
                    lastSeenAt = it.lastSeenAt,
                )
            }
            call.respond(AdminAccountsResponse(accounts, total))
        }

        get("/accounts/{id}/records") {
            val accountId = call.requiredPathId("id") ?: return@get
            val limit = call.limitParam(default = 100, max = 500)
            val records = services.admin.recordEnvelopes(accountId, limit).map {
                AdminRecordDto(
                    id = it.id,
                    type = it.type,
                    version = it.version,
                    updatedAt = it.updatedAt,
                    deviceId = it.deviceId,
                    deleted = it.deleted,
                    blobBytes = it.blobBytes,
                    serverSeq = it.serverSeq,
                    previewHex = it.previewHex,
                )
            }
            call.respond(AdminRecordsResponse(accountId, records))
        }

        delete("/accounts/{id}/tombstones") {
            val accountId = call.requiredPathId("id") ?: return@delete
            val purged = services.admin.purgeTombstones(accountId)
            if (purged > 0) {
                services.activity.record(accountId, "tombstones.purged", "purged $purged tombstones")
            }
            call.respond(AdminPurgeResponse(purged))
        }

        delete("/accounts/{id}") {
            val accountId = call.requiredPathId("id") ?: return@delete
            val deleted = services.admin.deleteAccount(accountId)
            if (deleted) {
                services.activity.record(accountId, "account.deleted", "admin-deleted account")
            }
            call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }
    }
}

/** Transparent selector (consumes no path segments); a separate guard node inside /admin. */
private class AdminGuardSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(admin guard)"
}

private class AdminAuthConfig {
    var token: String = ""
}

/**
 * Hook with PipelineContext access: unlike `onCall`, allows `finish()` — otherwise the route
 * handler would still run after a 401 (e.g. deleting an account without a token).
 */
private object AdminAuthHook : Hook<suspend (ApplicationCall) -> Boolean> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall) -> Boolean) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) {
            if (!handler(call)) finish()
        }
    }
}

/** Route-scoped guard for the `/admin` subtree: static token from [AdminAuthConfig.token]. */
private val AdminAuth = createRouteScopedPlugin("AdminAuth", ::AdminAuthConfig) {
    val token = pluginConfig.token
    on(AdminAuthHook) { call -> call.adminAuthorized(token) }
}

/**
 * Constant-time check of the static admin token. Missing/mismatched token responds 401 and
 * returns false; the calling hook does `finish()`. Constant-time comparison prevents a byte-by-
 * byte timing attack against the long-lived token.
 */
private suspend fun ApplicationCall.adminAuthorized(token: String): Boolean {
    val provided = request.headers["X-Admin-Token"]
    val ok = token.isNotBlank() && provided != null && constantTimeEquals(provided, token)
    if (!ok) respond(HttpStatusCode.Unauthorized, ErrorResponse("admin token required"))
    return ok
}

/**
 * Constant-time comparison. Both values are hashed to a fixed 32 bytes with SHA-256 first, then
 * compared — otherwise [MessageDigest.isEqual] on differing lengths returns early and leaks the
 * token length via timing.
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val md = MessageDigest.getInstance("SHA-256")
    val ha = md.digest(a.toByteArray(Charsets.UTF_8))
    val hb = md.digest(b.toByteArray(Charsets.UTF_8)) // digest() resets md's state
    return MessageDigest.isEqual(ha, hb)
}
