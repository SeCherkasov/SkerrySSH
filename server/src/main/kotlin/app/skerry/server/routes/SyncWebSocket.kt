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
 * WS push (`docs/skerry-sync-design.md` §3): сервер шлёт сигналы «появились изменения, сделай
 * дельта-pull» — никакого содержимого в кадрах. Форматы кадров:
 * - `{cursor}` — курсор аккаунтного vault (исторический формат);
 * - `team:{teamId}:{cursor}` — курсор записей команды;
 * - `teams` — изменился состав/приглашения — клиент перечитывает список команд.
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
        val teamNotifications = launch {
            services.notifier.teamChanges().collect { change ->
                // Членство меняется во время жизни сокета — фильтруем на каждом сигнале, а не на
                // рукопожатии; заодно тот же revoke-чек, что и в аккаунтном канале.
                if (services.devices.isRevoked(accountId, deviceId)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else if (change.teamId in services.teams.activeTeamIdsFor(accountId)) {
                    send(Frame.Text("team:${change.teamId}:${change.cursor}"))
                }
            }
        }
        val membershipNotifications = launch {
            services.notifier.forMembership(accountId).collect {
                if (services.devices.isRevoked(accountId, deviceId)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else {
                    send(Frame.Text("teams"))
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
            teamNotifications.cancel()
            membershipNotifications.cancel()
        }
    }
}
