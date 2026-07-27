package app.skerry.server.routes

import app.skerry.server.Services
import app.skerry.server.accountId
import app.skerry.server.deviceId
import app.skerry.server.metrics.NotifyKind
import app.skerry.server.metrics.WsCloseReason
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * WS push: the server sends "changes are available, do a delta
 * pull" signals, no content in frames. Frame formats:
 * - `{cursor}` — account vault cursor (legacy format);
 * - `team:{teamId}:{cursor}` — team record cursor;
 * - `shares:{teamId}` — the team's live shared sessions changed, client re-reads the directory;
 * - `teams` — membership/invites changed, client re-reads the team list.
 */
fun Route.syncWebSocket(services: Services) {
    webSocket("/sync") {
        val principal = call.principal<JWTPrincipal>()
        if (principal == null) {
            services.metrics.wsSessionOpened()
            services.metrics.wsSessionClosed(WsCloseReason.NO_PRINCIPAL, 0.0)
            // Defense-in-depth: the route sits under authenticate("auth-jwt"), but if it's ever
            // accidentally moved outside that, close with an explicit CloseReason, not a silent drop.
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "authentication required"))
            return@webSocket
        }
        val accountId = principal.accountId
        val deviceId = principal.deviceId
        // The gauge is maintained here, not from ChangeNotifier.subscriptions: each session collects
        // three flows, so that counter is three times the number of sockets.
        services.metrics.wsSessionOpened()
        val openedAt = System.nanoTime()
        // Written by the three notification coroutines and by the reader, read in `finally`: an
        // AtomicReference gives that a happens-before edge, and first-cause-wins keeps the label
        // truthful when a revoke and a client close race.
        val closeReason = AtomicReference(WsCloseReason.CLIENT_CLOSE)
        fun markClosing(reason: WsCloseReason) = closeReason.compareAndSet(WsCloseReason.CLIENT_CLOSE, reason)

        // Notifications run in a child coroutine; the main one reads incoming so a client Close
        // frame (or connection drop) ends the session immediately, instead of hanging in collect
        // until the next publish.
        val notifications = launch {
            services.notifier.forAccount(accountId).collect { cursor ->
                // JWT is only checked at handshake; device revocation after connecting must be
                // rechecked on every signal, or a revoked socket would keep receiving pushes forever.
                if (services.devices.isRevoked(accountId, deviceId)) {
                    markClosing(WsCloseReason.DEVICE_REVOKED)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else {
                    send(Frame.Text(cursor.toString()))
                    services.metrics.wsFrameSent(NotifyKind.ACCOUNT)
                }
            }
        }
        val teamNotifications = launch {
            services.notifier.teamChanges().collect { change ->
                // Membership can change during the socket's lifetime, so filter per signal rather
                // than at handshake; also applies the same revoke check as the account channel.
                if (services.devices.isRevoked(accountId, deviceId)) {
                    markClosing(WsCloseReason.DEVICE_REVOKED)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else if (change.teamId in services.teams.activeTeamIdsFor(accountId)) {
                    send(Frame.Text("team:${change.teamId}:${change.cursor}"))
                    services.metrics.wsFrameSent(NotifyKind.TEAM)
                }
            }
        }
        val shareNotifications = launch {
            services.notifier.shareChanges().collect { teamId ->
                // Same per-signal membership and revocation checks as the team channel: the
                // directory of live shared sessions is team-scoped information.
                if (services.devices.isRevoked(accountId, deviceId)) {
                    markClosing(WsCloseReason.DEVICE_REVOKED)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else if (teamId in services.teams.activeTeamIdsFor(accountId)) {
                    send(Frame.Text("shares:$teamId"))
                    services.metrics.wsFrameSent(NotifyKind.TEAM)
                }
            }
        }
        val membershipNotifications = launch {
            services.notifier.forMembership(accountId).collect {
                if (services.devices.isRevoked(accountId, deviceId)) {
                    markClosing(WsCloseReason.DEVICE_REVOKED)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device revoked"))
                } else {
                    send(Frame.Text("teams"))
                    services.metrics.wsFrameSent(NotifyKind.MEMBERSHIP)
                }
            }
        }
        try {
            // Server-push-only protocol: client frame content is ignored, the channel is drained
            // until close (a Close frame or TCP drop ends the iteration).
            @Suppress("ControlFlowWithEmptyBody")
            for (frame in incoming) {
            }
        } catch (error: Throwable) {
            markClosing(WsCloseReason.ERROR)
            throw error
        } finally {
            notifications.cancel()
            teamNotifications.cancel()
            shareNotifications.cancel()
            membershipNotifications.cancel()
            services.metrics.wsSessionClosed(closeReason.get(), (System.nanoTime() - openedAt) / 1_000_000_000.0)
        }
    }
}
