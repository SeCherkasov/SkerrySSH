package app.skerry.server.routes

import app.skerry.server.SERVER_VERSION
import app.skerry.server.Services
import app.skerry.server.metrics.RevokedBy
import app.skerry.server.model.AdminAccountDto
import app.skerry.server.model.AdminAccountsResponse
import app.skerry.server.model.AdminActivityDto
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDeviceDto
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.AdminObservabilityDto
import app.skerry.server.model.AdminPurgeResponse
import app.skerry.server.model.AdminRecordsResponse
import app.skerry.server.model.toDto
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

/**
 * Admin endpoints for the self-hosted console. `/admin/health` is open (liveness); the rest of
 * the `/admin` subtree is gated by the static [app.skerry.server.config.ServerConfig.adminToken]
 * (a separate admin role), checked by one route-scoped
 * interceptor. Zero-knowledge holds: only metadata (counts, device list) is served, no access to
 * record content.
 */
/**
 * `/admin/health` is the only open endpoint under `/admin`, and since the public front page reads it
 * on every visit it is registered outside the admin rate-limit bucket: sharing the brute-force
 * budget of the admin token would let anonymous page views throttle the operator out of the console
 * — and, behind a reverse proxy that isn't in `SKERRY_TRUSTED_PROXIES`, all visitors count as one
 * client, so a healthy instance would start reporting itself unavailable.
 */
fun Route.adminHealthRoute(services: Services) {
    get("/admin/health") {
        call.respond(HealthResponse("ok", SERVER_VERSION, services.config.registrationOpen))
    }
}

fun Route.adminRoutes(services: Services) {
    // Guard on a transparent child node (like authenticate {}): routing merges identical
    // selectors, so a plugin installed directly on route("/admin") would also cover the open
    // /admin/health above.
    val guarded = route("/admin") {}.createChild(AdminGuardSelector())
    // Single route-scoped admin-token check for the whole subtree: on failure the plugin
    // responds 401 itself and aborts the pipeline before any route handler runs.
    guarded.install(AdminAuth) {
        token = services.config.adminToken
        onFailure = { services.metrics.adminAuthFailure() }
    }

    with(guarded) {
        get("/stats") {
            val c = services.stats.counts()
            call.respond(StatsResponse(c.accounts, c.devices, c.records, c.pairingSessions, c.storageBytes))
        }

        get("/observability") {
            val lastSuccess = services.metrics.inventoryLastSuccessSecondsOrNull
            call.respond(
                AdminObservabilityDto(
                    metrics = services.config.metrics.name.lowercase(),
                    ready = services.dbProbe.ready,
                    inventoryIntervalSeconds = services.config.metricsInventoryIntervalSeconds,
                    inventoryAgeSeconds = lastSuccess?.let { (System.currentTimeMillis() / 1000 - it).coerceAtLeast(0) },
                ),
            )
        }

        get("/devices") {
            val limit = call.limitParam(default = 200, max = 500)
            val offset = call.offsetParam()
            // Optional account filter (`skerry-admin devices list --account`); the total follows it so
            // the "N of M" line can't disagree with the page.
            val accountFilter = call.request.queryParameters["accountId"]?.takeIf { it.isNotBlank() }
            val total = services.devices.count(accountFilter)
            val devices = services.devices.listAll(limit, accountFilter, offset).map {
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
            val events = services.activity.recent(limit, call.offsetParam()).map {
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
                services.metrics.deviceRevoked(RevokedBy.ADMIN)
            }
            call.respond(if (revoked) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
        }

        get("/accounts") {
            val limit = call.limitParam(default = 100, max = 1000)
            val total = services.admin.accountCount()
            val accounts = services.admin.accountSummaries(limit, offset = call.offsetParam()).map {
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
            val records = services.admin.recordEnvelopes(accountId, limit, call.offsetParam()).map { it.toDto() }
            call.respond(AdminRecordsResponse(accountId, records, services.admin.recordCount(accountId)))
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
            val outcome = services.admin.deleteAccount(accountId)
            if (outcome != null) {
                // Name the teams, not just how many: once the transaction commits, the owner column
                // is rewritten and the account row is gone, so the ids survive nowhere else — and
                // without them nobody can be told their team changed hands or vanished.
                val teams = listOfNotNull(
                    outcome.teamsTransferred.takeIf { it.isNotEmpty() }
                        ?.joinToString { id -> "$id → ${outcome.newOwners[id]}" }
                        ?.let { "transferred: $it" },
                    outcome.teamsDeleted.takeIf { it.isNotEmpty() }?.let { "deleted: ${it.joinToString()}" },
                )
                val detail = "admin-deleted account" + if (teams.isEmpty()) "" else " (${teams.joinToString("; ")})"
                services.activity.record(accountId, "account.deleted", detail)
                // The members of a transferred team read their own feed, not the admin console's.
                outcome.teamsTransferred.forEach { teamId ->
                    services.activity.record(
                        accountId = outcome.newOwners.getValue(teamId),
                        event = "team.owner_replaced",
                        detail = "owner account deleted by an administrator",
                        teamId = teamId,
                    )
                }
                // Same push every other membership change makes: without it the new owner and the
                // members of a deleted team only find out on their next poll.
                outcome.notifyAccounts.forEach { services.notifier.publishMembership(it) }
            }
            call.respond(if (outcome != null) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
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
    /** Called on a rejected request: the counter is the brute-force signal for a static token. */
    var onFailure: () -> Unit = {}
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
    val onFailure = pluginConfig.onFailure
    on(AdminAuthHook) { call -> call.adminAuthorized(token, onFailure) }
}

/**
 * Constant-time check of the static admin token. Missing/mismatched token responds 401 and
 * returns false; the calling hook does `finish()`. Constant-time comparison prevents a byte-by-
 * byte timing attack against the long-lived token.
 */
private suspend fun ApplicationCall.adminAuthorized(token: String, onFailure: () -> Unit): Boolean {
    val provided = request.headers["X-Admin-Token"]
    val ok = token.isNotBlank() && provided != null && constantTimeEquals(provided, token)
    if (!ok) {
        onFailure()
        respond(HttpStatusCode.Unauthorized, ErrorResponse("admin token required"))
    }
    return ok
}

