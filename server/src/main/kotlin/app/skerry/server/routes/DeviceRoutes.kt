package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.deviceId
import app.skerry.server.jwtPrincipal
import app.skerry.server.model.DeviceDto
import app.skerry.server.model.DevicesResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get

/** Список устройств аккаунта и отзыв доступа (`docs/skerry-sync-design.md` §3). */
fun Route.deviceRoutes(services: Services) {
    get("/devices") {
        val principal = call.jwtPrincipal()
        val current = principal.deviceId
        val devices = services.devices.list(principal.accountId).map {
            DeviceDto(it.id, it.name, it.createdAt, it.lastSeenAt, it.revoked, current = it.id == current)
        }
        call.respond(DevicesResponse(devices))
    }

    delete("/devices/{id}") {
        val principal = call.jwtPrincipal()
        val target = call.parameters["id"]
        if (target.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest)
            return@delete
        }
        val revoked = services.devices.revoke(principal.accountId, target)
        if (revoked) {
            services.activity.record(principal.accountId, "device.revoked", "revoked $target", deviceId = principal.deviceId)
        }
        call.respond(if (revoked) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }
}
