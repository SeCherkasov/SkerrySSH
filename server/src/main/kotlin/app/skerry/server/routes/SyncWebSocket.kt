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
 * WS push: the server sends "changes are available, do a delta
 * pull" signals, no content in frames. Frame formats:
 * - `{cursor}` — account vault cursor (legacy format);
 * - `team:{teamId}:{cursor}` — team record cursor;
 * - `teams` — membership/invites changed, client re-reads the team list.
 */
fun Route.syncWebSocket(services: Services) {
    webSocket("/sync") {
        val principal = call.principal<JWTPrincipal>()
        if (principal == null) {
            // Defense-in-depth: the route sits under authenticate("auth-jwt"), but if it's ever
            // accidentally moved outside that, close with an explicit CloseReason, not a silent drop.
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "authentication required"))
            return@webSocket
        }
        val accountId = principal.accountId
        val deviceId = principal.deviceId

        // Notifications run in a child coroutine; the main one reads incoming so a client Close
        // frame (or connection drop) ends the session immediately, instead of hanging in collect
        // until the next publish.
        val notifications = launch {
            services.notifier.forAccount(accountId).collect { cursor ->
                // JWT is only checked at handshake; device revocation after connecting must be
                // rechecked on every signal, or a revoked socket would keep receiving pushes forever.
                if (services.devices.isRevoked(accountId, deviceId)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else {
                    send(Frame.Text(cursor.toString()))
                }
            }
        }
        val teamNotifications = launch {
            services.notifier.teamChanges().collect { change ->
                // Membership can change during the socket's lifetime, so filter per signal rather
                // than at handshake; also applies the same revoke check as the account channel.
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
            // Server-push-only protocol: client frame content is ignored, the channel is drained
            // until close (a Close frame or TCP drop ends the iteration).
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
