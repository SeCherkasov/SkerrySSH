package app.skerry.ui.keepalive

/**
 * Platform hook for background session keep-alive: while at least one session is open, the mobile
 * app keeps its process (and with it the SSH keep-alive loops) alive, so backgrounding the app
 * doesn't freeze the connection and the terminal is still live on return. Desktop installs leave
 * [SessionKeepAlive.bridge] unset and nothing happens.
 *
 * Calls are keyed by session id so the platform can show one notification per live session
 * (Termius-style) and route a notification tap back to the exact terminal it belongs to.
 *
 * Implementations must be thread-safe and tolerant of slightly unbalanced calls — a failed connect
 * can tear down resources without a matching start, and multi-pane sessions interleave events.
 */
interface SessionKeepAliveBridge {

    /**
     * One session established. [sessionId] is the tab/pane id of the session (stable for the
     * session's lifetime, also across an auto-reconnect); [hostLabel] is the target host, used for
     * the per-session notification title.
     */
    fun onSessionStarted(sessionId: String, hostLabel: String)

    /** The session [sessionId] is gone. Idempotent: repeated calls for a missing id are no-ops. */
    fun onSessionEnded(sessionId: String)
}

/** Static holder so the shared UI can reach the platform implementation without DI plumbing. */
object SessionKeepAlive {
    @Volatile
    var bridge: SessionKeepAliveBridge? = null
}
