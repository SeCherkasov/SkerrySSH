package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.deviceId
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.launch

/**
 * WS push (`docs/skerry-sync-design.md` §3): сервер шлёт лишь новый курсор аккаунта — сигнал
 * «появились изменения, сделай дельта-pull». Никакого содержимого в кадрах.
 */
fun Route.syncWebSocket(services: Services) {
    webSocket("/sync") {
        val principal = call.principal<JWTPrincipal>()
        if (principal == null) {
            // Defense-in-depth: маршрут стоит под authenticate("auth-jwt"), но при случайном
            // переносе наружу закрываемся явным CloseReason, а не молчаливым обрывом.
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "authentication required"))
            return@webSocket
        }
        val accountId = principal.accountId
        val deviceId = principal.deviceId

        // Уведомления — в дочерней корутине; основная читает incoming, чтобы Close-кадр клиента
        // (и обрыв соединения) завершал сессию сразу, а не висел в collect до следующего publish.
        val notifications = launch {
            services.notifier.forAccount(accountId).collect { cursor ->
                // JWT проверяется лишь на рукопожатии; отзыв устройства после подключения обязан
                // перепроверяться на каждом сигнале — иначе отозванный сокет получал бы push вечно.
                if (services.devices.isRevoked(accountId, deviceId)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else {
                    send(Frame.Text(cursor.toString()))
                }
            }
        }
        try {
            // Протокол server-push-only: содержимое клиентских кадров игнорируем, канал дочитываем
            // до закрытия (Close-кадр/обрыв TCP завершают итерацию).
            @Suppress("ControlFlowWithEmptyBody")
            for (frame in incoming) {
            }
        } finally {
            notifications.cancel()
        }
    }
}
