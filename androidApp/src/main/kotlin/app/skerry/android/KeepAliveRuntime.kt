package app.skerry.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.rdp.RdpCredentials
import app.skerry.shared.rdp.RdpRemoteDesktop
import app.skerry.shared.rdp.RdpTarget
import app.skerry.shared.rdp.RdpTransport
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.TerminalHistoryRecord
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.terminal.VaultTerminalHistoryStore
import app.skerry.shared.vault.Vault
import app.skerry.shared.vnc.VncRemoteDesktop
import app.skerry.shared.vnc.VncTransport
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.teams.TeamsCoordinator
import app.skerry.ui.terminal.TerminalSessionPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-scoped half of the Android session keep-alive: the sessions graph, its scope, and the
 * service bridge all outlive any single Activity — the foreground service keeps the process alive
 * while sessions are open, and these objects keep the connections alive inside it, so returning to
 * the app (or tapping a per-session notification) shows the same live terminal.
 *
 * Everything an Activity rebuilds on recreation (the dependency graph, the notification-permission
 * hook, the live terminal prefs) is reached through replaceable references here rather than being
 * captured at construction: a session graph pinning the FIRST Activity's graph would write history
 * through a forever-locked first vault instance — two [app.skerry.shared.vault.FileVault] caches
 * over one file silently losing records — and would 2FA-prompt into a dead composition.
 */
internal object KeepAliveRuntime {

    /** The slice of the per-Activity dependency graph the session graph reads, per call. */
    class GraphDeps(
        val transport: SshTransport,
        val vncTransport: VncTransport?,
        val rdpTransport: RdpTransport?,
        val vault: Vault?,
        val teams: TeamsCoordinator?,
    )

    /**
     * Scope of the process-lived connections. Main.immediate on purpose: [ConnectionController]'s
     * serialization argument (disconnect vs. onSessionLost race) relies on its scope being the
     * single main thread, exactly like the desktop composition scope it replaces. Never cancelled —
     * its lifetime IS the process; per-session teardown goes through disconnect().
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Rebuilt by every [MainActivity.onCreate]; read by the factories below at call time. */
    @Volatile
    var deps: GraphDeps? = null

    /** The one bridge instance for the process (see [AndroidSessionKeepAlive]); set once. */
    @Volatile
    var bridge: AndroidSessionKeepAlive? = null

    /**
     * Live terminal settings for NEW sessions, refreshed by the UI on every settings change. A
     * plain value, not a closure — a closure over the design state would retain the destroyed
     * Activity for the process lifetime.
     */
    @Volatile
    var terminalPrefs: TerminalSessionPrefs = TerminalSessionPrefs()

    /**
     * The current Activity's notification-permission hook (Android 13+), invoked once when the
     * first session opens. Replaced in onCreate, cleared in onDestroy — never a static strong
     * reference to a dead Activity.
     */
    @Volatile
    var onFirstSession: (() -> Unit)? = null

    /**
     * Session id routed from a per-session notification tap; the UI activates that pane once and
     * clears it. Compose state (not @Volatile) so the routing LaunchedEffect reacts to the tap.
     */
    var pendingSessionId by mutableStateOf<String?>(null)

    @Volatile
    private var built: SessionsController? = null

    /** The process-scoped sessions controller, built on first use. */
    val sessions: SessionsController? get() = built

    fun sessionsController(): SessionsController = built ?: build().also { built = it }

    // Reads the CURRENT graph on every connect: a reconnect after an Activity recreation must
    // verify host keys against the vault the user actually unlocked and prompt 2FA into the live
    // composition, not the first Activity's orphaned instances.
    private val liveTransport = object : SshTransport {
        override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
            requireNotNull(deps) { "keep-alive transport used before the first dependency graph" }
                .transport.connect(target, auth)
    }

    // Same indirection for history: writing through a stale FileVault instance would rewrite the
    // vault file from a stale record cache (silent data loss). The vault-backed store is a thin
    // stateless wrapper, cheap to derive per call; locked/absent vault degrades to no persistence.
    private val liveHistory = object : TerminalHistoryStore {
        private fun store(): TerminalHistoryStore? = deps?.vault?.let { VaultTerminalHistoryStore(it) }
        override fun load(key: String): List<String> = store()?.load(key).orEmpty()
        override fun save(key: String, commands: List<String>, label: String?) {
            store()?.save(key, commands, label)
        }
        override fun all(): List<TerminalHistoryRecord> = store()?.all().orEmpty()
    }

    private fun build(): SessionsController {
        var counter = 0
        return SessionsController(
            newId = { "sess-${counter++}" },
            vncControllerFactory = { RemoteDesktopController(scope) },
            openVncSession = { target, auth ->
                val vnc = requireNotNull(deps?.vncTransport) { "no VNC transport wired" }
                VncRemoteDesktop(vnc.connect(target, auth))
            },
            openRdpSession = { request ->
                val rdp = requireNotNull(deps?.rdpTransport) { "no RDP transport wired" }
                RdpRemoteDesktop(
                    rdp.connect(
                        RdpTarget(
                            host = request.host,
                            port = request.port,
                            desktopWidth = request.width,
                            desktopHeight = request.height,
                            clientName = request.clientName,
                            loadBalanceInfo = request.loadBalanceInfo,
                            audioOutput = request.audioOutput,
                            audioDeviceId = request.audioDeviceId,
                            clipboard = request.clipboard,
                            imageQuality = request.imageQuality,
                        ),
                        RdpCredentials(
                            username = request.user,
                            password = request.password,
                            domain = request.domain,
                        ),
                    ),
                )
            },
            // Desktop parity: the session half of the Teams activity feed (the coordinator holds
            // the privacy gates). Reported to the CURRENT coordinator — the first one's sync link
            // dies with its Activity.
            onHostSessionOpened = { hostId -> deps?.teams?.reportSessionOpened(hostId) },
            controllerFactory = {
                ConnectionController(
                    liveTransport,
                    scope,
                    history = liveHistory,
                    terminalPrefs = { terminalPrefs },
                    keepAlive = bridge,
                )
            },
        )
    }
}
