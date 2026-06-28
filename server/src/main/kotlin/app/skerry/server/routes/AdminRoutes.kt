package app.skerry.server.routes

import app.skerry.server.SERVER_VERSION
import app.skerry.server.Services
import app.skerry.server.model.AdminActivityDto
import app.skerry.server.model.AdminActivityResponse
import app.skerry.server.model.AdminDeviceDto
import app.skerry.server.model.AdminDevicesResponse
import app.skerry.server.model.ErrorResponse
import app.skerry.server.model.HealthResponse
import app.skerry.server.model.StatsResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import java.security.MessageDigest

/**
 * Админ-эндпоинты для self-hosted консоли. `/admin/health` открыт (liveness); остальное закрыто
 * статическим [config.adminToken] — отдельная admin-роль (`docs/skerry-sync-design.md` §3).
 * Zero-knowledge сохраняется: отдаются только метаданные (счётчики, список устройств), к
 * содержимому записей доступа нет по определению.
 */
fun Route.adminRoutes(services: Services) {
    get("/admin/health") {
        call.respond(HealthResponse("ok", SERVER_VERSION))
    }

    get("/admin/stats") {
        if (!call.adminAuthorized(services.config.adminToken)) return@get
        val c = services.stats.counts()
        call.respond(StatsResponse(c.accounts, c.devices, c.records, c.pairingSessions))
    }

    get("/admin/devices") {
        if (!call.adminAuthorized(services.config.adminToken)) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 200
        val devices = services.devices.listAll(limit).map {
            AdminDeviceDto(it.accountId, it.id, it.name, it.createdAt, it.lastSeenAt, it.revoked)
        }
        call.respond(AdminDevicesResponse(devices))
    }

    get("/admin/activity") {
        if (!call.adminAuthorized(services.config.adminToken)) return@get
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
        val events = services.activity.recent(limit).map {
            AdminActivityDto(it.accountId, it.deviceId, it.event, it.detail, it.createdAt)
        }
        call.respond(AdminActivityResponse(events))
    }

    delete("/admin/devices/{id}") {
        if (!call.adminAuthorized(services.config.adminToken)) return@delete
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
}

/**
 * Constant-time проверка статического admin-токена. При отсутствии/несовпадении сразу отвечает
 * 401 и возвращает false — вызывающий обработчик делает `return`. Сравнение постоянного времени:
 * не даём по таймингу побайтно подобрать долгоживущий токен.
 */
private suspend fun ApplicationCall.adminAuthorized(token: String): Boolean {
    val provided = request.headers["X-Admin-Token"]
    val ok = token.isNotBlank() && provided != null && constantTimeEquals(provided, token)
    if (!ok) respond(HttpStatusCode.Unauthorized, ErrorResponse("admin token required"))
    return ok
}

/**
 * Сравнение постоянного времени. Сначала хэшируем оба значения SHA-256 до фиксированных 32 байт,
 * потом сверяем — иначе [MessageDigest.isEqual] на разной длине выходит раньше и по таймингу выдаёт
 * длину токена (security-ревью M1).
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val md = MessageDigest.getInstance("SHA-256")
    val ha = md.digest(a.toByteArray(Charsets.UTF_8))
    val hb = md.digest(b.toByteArray(Charsets.UTF_8)) // digest() сбрасывает состояние md
    return MessageDigest.isEqual(ha, hb)
}
