package app.skerry.ui.keepalive

/**
 * Platform hook for background session keep-alive: while at least one session is open, the mobile
 * app keeps its process (and with it the SSH keep-alive loops) alive, so backgrounding the app
 * doesn't freeze the connection and the terminal is still live on return. Injected into
 * [app.skerry.ui.connection.ConnectionController] by the platform that has one (Android);
 * desktop passes nothing and the hook stays null.
 *
 * Calls are keyed by session id so the platform can show one notification per live session
 * (Termius-style) and route a notification tap back to the exact terminal it belongs to. A drop
 * that enters auto-reconnect is NOT reported as ended — the platform must keep the session's
 * keep-alive up through the retry window (see the controller's keepAliveOpen contract).
 *
 * Implementations must be thread-safe, must NEVER throw (a platform refusal here must not be able
 * to unwind a live SSH session), and must tolerate slightly unbalanced calls — a failed connect
 * can tear down resources without a matching start, and multi-pane sessions interleave events.
 */
interface SessionKeepAliveBridge {

    /**
     * One session established. [sessionId] is the tab/pane id of the session (stable for the
     * session's lifetime, also across an auto-reconnect — re-announcing a known id is an in-place
     * update, not a second notification); [hostLabel] is the sanitised target host, used for the
     * per-session notification title.
     */
    fun onSessionStarted(sessionId: String, hostLabel: String)

    /** The session [sessionId] is gone. Idempotent: repeated calls for a missing id are no-ops. */
    fun onSessionEnded(sessionId: String)

    /**
     * Whether battery optimizations are ignored on this device (false = system may freeze sockets in background).
     */
    fun isOptimizedForKeepAlive(): Boolean = true

    /**
     * Request battery optimization exemption or open system power management.
     */
    fun requestKeepAliveOptimization() {}

    /**
     * Open system autostart / background management settings page.
     */
    fun openAutostartSettings() {}

    /**
     * Open system application details settings page.
     */
    fun openAppDetailsSettings() {}

    /**
     * Human-readable device manufacturer (e.g. "Xiaomi", "Huawei", "OPPO", "vivo", "Samsung").
     */
    fun getManufacturer(): String = "Unknown"

    /**
     * Whether this platform supports mobile keep-alive configuration (true on Android).
     */
    val isKeepAliveConfigSupported: Boolean get() = false

    /**
     * Whether the user has enabled background keep-alive (screen-off CPU wake lock).
     * Default false — keeping the CPU awake is a battery trade-off the user must opt into.
     */
    val isWakeLockEnabled: Boolean get() = false
}
