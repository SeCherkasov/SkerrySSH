package app.skerry.android

import android.content.Context
import android.content.Intent
import android.os.Build
import app.skerry.ui.keepalive.SessionKeepAliveBridge
import java.util.concurrent.ConcurrentHashMap

/**
 * Android implementation of [SessionKeepAliveBridge]: runs the foreground service while at least
 * one session is open, one notification per live session (Termius-style), and stops the service
 * when the last one closes. The session map tolerates unbalanced calls (a failed connect tears
 * down without a matching start; multi-pane sessions interleave events) — [onSessionEnded] for an
 * unknown id is a no-op, so stray events can never wedge the service.
 */
class AndroidSessionKeepAlive(
    private val context: Context,
    /**
     * Called once, when the FIRST session opens (transition empty -> non-empty). The platform uses
     * it for the lazy notification-permission request on Android 13+ — the request is tied to the
     * user's first explicit connect instead of app startup, and never repeats.
     */
    private val onFirstSession: (() -> Unit)? = null,
) : SessionKeepAliveBridge {

    // sessionId -> host label. The source of truth for "is the service needed at all"; the service
    // keeps its own copy for notification ids.
    private val sessions = ConcurrentHashMap<String, String>()

    init {
        // Let the service rebuild its notification map after a system restart (see
        // SessionKeepAliveService.replaySessionsFromBridge).
        SessionKeepAliveService.bridgeInstance = this
    }

    /** Point-in-time copy of the live sessions, for the service to replay after a restart. */
    fun snapshotSessions(): Map<String, String> = sessions.toMap()

    override fun onSessionStarted(sessionId: String, hostLabel: String) {
        val wasEmpty = sessions.isEmpty()
        sessions[sessionId] = hostLabel
        if (wasEmpty) onFirstSession?.invoke()
        val intent = Intent(context, SessionKeepAliveService::class.java)
            .setAction(SessionKeepAliveService.ACTION_ADD)
            .putExtra(SessionKeepAliveService.EXTRA_SESSION_ID, sessionId)
            .putExtra(SessionKeepAliveService.EXTRA_HOST_LABEL, hostLabel)
        if (wasEmpty) {
            // First session: bring the service up. startForegroundService is required on API 26+;
            // called while the app is foreground (a session was just opened), exactly when Android
            // permits it. Reconnects of an already-notified session take the plain path below.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            // Service already up (or a reconnect of a known session) — plain start is enough to
            // deliver the intent; a dead service re-promotes itself inside onStartCommand.
            runCatching { context.startService(intent) }
        }
    }

    override fun onSessionEnded(sessionId: String) {
        if (sessions.remove(sessionId) == null) return  // idempotent: unknown id is a no-op
        val intent = Intent(context, SessionKeepAliveService::class.java)
            .setAction(SessionKeepAliveService.ACTION_REMOVE)
            .putExtra(SessionKeepAliveService.EXTRA_SESSION_ID, sessionId)
        runCatching { context.startService(intent) }
    }
}
