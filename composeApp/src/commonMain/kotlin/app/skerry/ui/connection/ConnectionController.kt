package app.skerry.ui.connection

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.files.FileBrowser
import app.skerry.shared.files.SftpFileBrowser
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.serial.SerialProblem
import app.skerry.shared.serial.SerialUnavailableException
import app.skerry.shared.mosh.MoshSetupException
import app.skerry.shared.ssh.ConnectionType
import app.skerry.shared.ssh.SshAgentForwarding
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.terminal.ShellTerminalSession
import app.skerry.shared.terminal.TerminalHistoryStore
import app.skerry.shared.terminal.TerminalState
import app.skerry.shared.terminal.terminalHistoryKey
import app.skerry.ui.terminal.ThroughputController
import app.skerry.ui.files.FilePaneController
import app.skerry.ui.files.TransferCoordinator
import app.skerry.ui.forward.PortForwardController
import app.skerry.ui.metrics.HostMetricsController
import app.skerry.ui.terminal.TerminalScreenState
import app.skerry.ui.terminal.TerminalSessionPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Status-bar RTT poll cadence for Mosh sessions: reads a cached value, sends no traffic. */
private const val MOSH_RTT_POLL_MILLIS = 3_000L

/** State of the connection screen. */
sealed interface ConnectionUiState {
    /** The connection form is shown (start, or return after disconnect/error). */
    data object Form : ConnectionUiState

    /** Connect/auth/shell-open in progress. */
    data object Connecting : ConnectionUiState

    /** Session is open; [terminal] is the live terminal state. */
    data class Connected(val terminal: TerminalScreenState) : ConnectionUiState

    /**
     * Connect failed; [message] is shown to the user. [moshReason]/[moshDetail] and
     * [serialProblem]/[serialDetail] are set when the failure is a typed Mosh setup or serial port
     * problem — the view then renders a localized explanation (missing server package, locale,
     * blocked UDP; port missing, permission denied) instead of the raw English [message]
     * (see `connectionErrorText`); building the text here would bake in one language
     * (same rule as [app.skerry.shared.sync.SyncFailureReason]).
     */
    data class Error(
        val message: String,
        val moshReason: MoshSetupException.Reason? = null,
        val moshDetail: String? = null,
        val serialProblem: SerialProblem? = null,
        val serialDetail: String? = null,
    ) : ConnectionUiState

    /**
     * The session was established but the shell closed NOT on our initiative (EOF / transport
     * drop). Our own [disconnect] never lands here — it cancels the session scope before an
     * observer would see the close, and transitions to [Form]. [terminal] is the frozen screen at
     * the moment of loss: the UI keeps showing it while auto-reconnect tries to restore a live
     * session on top.
     *
     * [reconnecting] is whether an auto-reconnect attempt is in progress (true between the drop
     * and success/giving up); [attempt] is the current/last attempt number (for a "Reconnecting…
     * #N" banner). Once the attempt limit is exhausted, the state stays [Disconnected] with
     * `reconnecting=false` (connection not restored).
     *
     * [cleanExit] is true when the shell exited normally (EOF, e.g. via `exit`): there is NO
     * auto-reconnect (the session is simply closed), and the banner reads neutrally "Session
     * closed". False means a transport drop.
     */
    data class Disconnected(
        val terminal: TerminalScreenState,
        val reconnecting: Boolean,
        val attempt: Int,
        val cleanExit: Boolean = false,
    ) : ConnectionUiState
}

/**
 * Binds the connection form to [SshTransport]: [connect] establishes the connection, opens an
 * interactive shell, and assembles a [TerminalScreenState] over [ShellTerminalSession].
 *
 * Output collection and decoding live on a separate session scope (see [newSessionScope]):
 * [disconnect] cancels it and tears down the connection without touching the controller's main
 * [scope]. Tests substitute [newSessionScope] with a test dispatcher for determinism.
 *
 * Transitions only originate from [ConnectionUiState.Form] (guarded in [connect]), so concurrent
 * connects are impossible. [disconnect] cancels an in-flight connect and closes an already
 * established connection; teardown runs under [NonCancellable] so it isn't lost if the main scope
 * is cancelled.
 */
@Stable
class ConnectionController(
    private val transport: SshTransport,
    private val scope: CoroutineScope,
    private val newSessionScope: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
    },
    // Auto-reconnect policy for an unintended drop. The attempt limit guards against an infinite
    // loop against a permanently dead host; backoff is exponential, capped at 30s. Tests supply
    // their own values (zero backoff, small limit) for determinism.
    private val maxReconnectAttempts: Int = 6,
    private val reconnectDelayMillis: (attempt: Int) -> Long = { attempt ->
        minOf(30_000L, 1_000L shl (attempt - 1).coerceIn(0, 16))
    },
    // Per-host terminal command history persistence (for autocomplete). null means no persistence
    // (the session only learns from itself). The key is derived from the target
    // ([terminalHistoryKey]); saving happens on IO so file I/O never blocks the UI thread per
    // command.
    private val history: TerminalHistoryStore? = null,
    // Terminal settings (scrollback depth + cursor style), read at the moment of EACH connect — so
    // a settings change affects new sessions while already-open ones keep their emulator. The
    // default (mock/tests) gives standard values.
    private val terminalPrefs: () -> TerminalSessionPrefs = { TerminalSessionPrefs() },
) {
    var uiState: ConnectionUiState by mutableStateOf(ConnectionUiState.Form)
        private set

    /**
     * The negotiated cipher of this session's live connection (for the info panel), or `null`
     * while not connected / not reported by the transport. Held as snapshot state (rather than a
     * getter over the non-snapshot [connection]) so Compose tracks the read and redraws the info
     * panel when the connection appears/resets. Set on transition to [ConnectionUiState.Connected].
     */
    var cipher: String? by mutableStateOf(null)
        private set

    /**
     * SSH server ident of the live connection (`SSH-2.0-OpenSSH_8.9p1`), or `null` while not
     * connected / not reported by the transport. Snapshot state for the same reason as [cipher]
     * (Compose must redraw the status bar when the connection appears/resets).
     */
    var serverVersion: String? by mutableStateOf(null)
        private set

    /**
     * Whether the live session's shell got the SSH agent forwarded into it, and — the point of
     * showing it at all — whether the server refused. Snapshot state like [cipher]: the info panel
     * has to redraw when the session appears or resets.
     */
    var agentForwarding: SshAgentForwarding by mutableStateOf(SshAgentForwarding.None)
        private set

    /**
     * History key of the live session (see [terminalHistoryKey]), or `null` while not connected.
     * The command palette reads it to put this host's commands first. Snapshot state so the palette
     * sees the key appear when a session connects under it.
     */
    var historyKey: String? by mutableStateOf(null)
        private set

    private var connectJob: Job? = null
    // Target/auth of the last connect — used by auto-reconnect after a drop (reconnects to the same target).
    private var lastTarget: SshTarget? = null
    private var lastAuth: SshAuth? = null
    // One-shot action for the FIRST transition to Connected of this connect (e.g. a snippet's "Run
    // on host": run a command in the freshly opened session). Fires and is cleared in
    // establishSession on success; auto-reconnect via establishSession never sets it, so the
    // command doesn't repeat on reconnect.
    private var pendingOnConnected: ((TerminalScreenState) -> Unit)? = null
    private var reconnectJob: Job? = null
    private var connection: SshConnection? = null
    private var shellChannel: ShellChannel? = null
    private var sessionScope: CoroutineScope? = null
    private var portForwards: PortForwardController? = null
    private var sftpClient: SftpClient? = null
    private var transferCoordinator: TransferCoordinator? = null
    private val sftpMutex = Mutex()
    private var metrics: HostMetricsController? = null
    private var throughput: ThroughputController? = null
    private var ping: PingController? = null

    /**
     * Connect to [target]/[auth]. [onConnected] (if given) is called EXACTLY ONCE on the first
     * transition to [ConnectionUiState.Connected] with the ready terminal — the hook for "run a
     * command right after the session opens" (snippets' "Run on host"). Not repeated on
     * auto-reconnect after a drop (reconnect carries no such callback).
     */
    fun connect(target: SshTarget, auth: SshAuth, onConnected: ((TerminalScreenState) -> Unit)? = null) {
        // Only starts from the form: while a connect is in progress or a session is open, a repeat
        // connect is ignored — otherwise a scope/connection could leak.
        if (uiState !is ConnectionUiState.Form) return
        lastTarget = target
        lastAuth = auth
        pendingOnConnected = onConnected
        uiState = ConnectionUiState.Connecting
        connectJob = scope.launch {
            try {
                establishSession(target, auth)
            } catch (e: CancellationException) {
                // disconnect() fired mid-connect: the half-open connection is already closed inside
                // establishSession; uiState was set to Form by disconnect() itself.
                throw e
            } catch (e: Exception) {
                // The serial transport wraps its typed failure into SshConnectionException, so the
                // cause carries the reason the view localizes.
                val serial = e as? SerialUnavailableException ?: e.cause as? SerialUnavailableException
                uiState = ConnectionUiState.Error(
                    // Transport text is diagnostics only: the view shows a localized base and keeps
                    // this as a parenthetical detail (sshj/okio messages are always English).
                    message = e.message.orEmpty(),
                    moshReason = (e as? MoshSetupException)?.reason,
                    moshDetail = (e as? MoshSetupException)?.detail,
                    serialProblem = serial?.problem,
                    serialDetail = serial?.detail,
                )
            }
        }
    }

    /**
     * Establishes a live session to [target]/[auth]: opens the connection and shell, assembles the
     * terminal, transitions to [ConnectionUiState.Connected], and subscribes the drop observer. On
     * any error, closes the half-open connection and rethrows (the caller decides: show [Error] or
     * retry a reconnect attempt). Used by both the initial [connect] and auto-reconnect.
     */
    private suspend fun establishSession(target: SshTarget, auth: SshAuth) {
        var conn: SshConnection? = null
        try {
            val opened = transport.connect(target, auth)
            conn = opened
            coroutineContext.ensureActive()
            val channel = opened.openShell()
            coroutineContext.ensureActive()
            val sScope = newSessionScope()
            connection = conn
            // IMPORTANT: the channel must be set BEFORE uiState = Connected — the status bar's
            // reaction to that transition calls openThroughput(), which requires a live shellChannel
            // (otherwise it throws).
            shellChannel = channel
            cipher = conn.cipher
            serverVersion = conn.serverVersion
            // Read after openShell: the request rides the shell's session channel, so before that
            // the connection has nothing to report.
            agentForwarding = conn.agentForwarding
            sessionScope = sScope
            // Command history for autocomplete: load for this host and attach a snapshot-persist
            // hook on every committed command (runs on the controller's IO scope, not the UI thread).
            val historyKey = terminalHistoryKey(
                target.connectionType.name, target.username, target.host, target.port,
            )
            this.historyKey = historyKey
            val loadedHistory = history?.load(historyKey).orEmpty()
            // Snapshot terminal settings at connect time: they apply to the new session.
            val prefs = terminalPrefs()
            val terminal = TerminalScreenState(
                ShellTerminalSession(channel, sScope),
                sScope,
                initialHistory = loadedHistory,
                scrollback = prefs.effectiveScrollback,
                cursorShape = prefs.cursorStyle.shape,
                cursorBlink = prefs.cursorStyle.blink,
                clipboardWriteEnabled = prefs.clipboardWriteEnabled,
                onHistoryChanged = history?.let { store ->
                    // Moves the write off the UI thread onto the controller's scope (Default):
                    // commands are infrequent and the write is small. The label rides along so the
                    // command palette can name the host a command came from.
                    val label = "${target.username}@${target.host}"
                    { snapshot -> scope.launch { store.save(historyKey, snapshot, label) } }
                },
            )
            // Keep-alive per the profile's cadence (0 = off, SSH-only): pings run from the moment
            // the session exists — not lazily from the status bar — so an idle session behind a NAT
            // stays alive even with no UI polling it. Created BEFORE Connected (like shellChannel)
            // so the status bar's openPing() sees it on the transition. Doubles as the RTT source.
            if (target.connectionType == ConnectionType.SSH && target.keepAliveSeconds > 0) {
                ping = PingController(
                    measure = { opened.measureRoundTrip() },
                    scope = scope,
                    pollIntervalMillis = target.keepAliveSeconds * 1_000L,
                    onDead = {
                        // Dead link (consecutive keepalives unanswered): force-close the shell
                        // channel so the loss flows through the regular drop path (Closed without
                        // EOF -> auto-reconnect) now, not after minutes of frozen terminal
                        // waiting out the TCP timeout. NonCancellable like the other teardown
                        // launches: the close must not be lost if the scope dies at that moment.
                        scope.launch(NonCancellable) { runCatching { channel.close() } }
                    },
                ).also { it.start() }
            }
            if (target.connectionType == ConnectionType.MOSH) {
                // Mosh needs no keep-alive traffic (the protocol heartbeats every 3s on its own)
                // and must not be declared dead (it survives outages/roaming by design), so:
                // fixed poll cadence, no onDead. measureRoundTrip() only reads the smoothed RTT
                // mosh already measured — the poll itself sends nothing.
                ping = PingController(
                    measure = { opened.measureRoundTrip() },
                    scope = scope,
                    pollIntervalMillis = MOSH_RTT_POLL_MILLIS,
                ).also { it.start() }
            }
            uiState = ConnectionUiState.Connected(terminal)
            // One-shot action for the first connect (Run on host): taken and cleared BEFORE a
            // possible drop, so a reconnect through this same establishSession doesn't repeat it.
            pendingOnConnected?.let { action -> pendingOnConnected = null; action(terminal) }
            watchForSessionLoss(terminal, sScope)
        } catch (e: Exception) {
            // A throw after the session fields are published (e.g. from the onConnected action)
            // must not leave a half-established session — keep-alive loop, session scope, open
            // socket — behind an Error state: reuse the disconnect teardown. Before that point
            // only the local connection exists and just needs closing.
            if (connection != null) releaseSessionResources() else conn?.let(::closeConnectionQuietly)
            throw e
        }
    }

    /**
     * Opens an SFTP channel over this session's live connection. The channel is owned by the
     * caller (the SFTP screen): close it via [app.skerry.shared.sftp.SftpClient.close] in dispose.
     * The SSH connection itself stays with the controller and is closed by [disconnect].
     * @throws IllegalStateException the session isn't connected (no live connection)
     */
    suspend fun openSftp(): SftpClient =
        (connection ?: error("No active connection for SFTP")).openSftp()

    /**
     * This session's port-forward controller — one per connection, created lazily and cached, so
     * it survives UI tab/pane switches (tunnels stay alive as long as the session is). Operations
     * run on the session's internal [scope] (like [openTransferCoordinator]), not the screen's
     * UI scope — otherwise the view leaving composition would cancel the already-cached
     * controller's scope and silently kill tunnel setup/teardown. All forwards are torn down by
     * [disconnect] when the session closes.
     * @throws IllegalStateException the session isn't connected (no live connection)
     */
    fun openPortForwards(): PortForwardController =
        portForwards ?: PortForwardController(
            connection ?: error("No active connection for port forwarding"),
            scope,
        ).also { portForwards = it }

    /**
     * This session's dual-pane SFTP coordinator (local filesystem + remote host) — one per
     * connection, created lazily and cached (like [openPortForwards]), so it survives view
     * switches (pane path/selection isn't reset). Pane and transfer operations run on the
     * session's internal [scope]; the channel itself ([sftpClient]) is closed by [disconnect].
     * The first call opens the channel and starts loading both panes' initial directories
     * ([FilePaneController.start]). [localBrowser] is the platform browser for the local
     * filesystem (supplied by the UI layer so the controller stays free of platform expect
     * functions and testable); [hostLabel] labels the remote pane. Both parameters are used only
     * on first creation — a repeat call returns the cache and ignores them. [sftpMutex] serializes
     * the lazy init: even under a race of two callers the channel opens exactly once (no leaked
     * second one), and the non-volatile cache fields are published safely under the lock.
     * @throws IllegalStateException the session isn't connected (no live connection)
     */
    suspend fun openTransferCoordinator(localBrowser: FileBrowser, hostLabel: String): TransferCoordinator =
        sftpMutex.withLock {
            transferCoordinator ?: run {
                val client = (connection ?: error("No active connection for SFTP")).openSftp()
                sftpClient = client
                val remoteBrowser = SftpFileBrowser(client, hostLabel)
                TransferCoordinator(
                    sftp = client,
                    local = FilePaneController(localBrowser, scope),
                    localBrowser = localBrowser,
                    remote = FilePaneController(remoteBrowser, scope),
                    remoteBrowser = remoteBrowser,
                    scope = scope,
                ).also {
                    it.local.start()
                    it.remote.start()
                    transferCoordinator = it
                }
            }
        }

    /**
     * This session's live host-metrics controller — one per connection, created lazily and cached
     * (like [openPortForwards]/[openTransferCoordinator]); polling runs on the session's [scope]
     * and starts immediately. Stopped by [disconnect] along with the session.
     * @throws IllegalStateException the session isn't connected (no live connection)
     */
    fun openMetrics(): HostMetricsController {
        val conn = connection ?: error("No active connection for metrics")
        return metrics ?: HostMetricsController(
            exec = { cmd -> conn.exec(cmd) },
            scope = scope,
        ).also { it.start(); metrics = it }
    }

    /**
     * This session's terminal-channel throughput controller — one per connection, created lazily
     * and cached (like [openMetrics]); polling runs on the session's [scope]. Samplers read the
     * channel's live counters; after [disconnect] (channel cleared) they'd return 0, but the
     * poller is stopped by then.
     * @throws IllegalStateException the session isn't connected (no live channel)
     */
    fun openThroughput(): ThroughputController {
        val channel = shellChannel ?: error("No active channel for throughput measurement")
        return throughput ?: ThroughputController(
            sampleUp = { channel.bytesUp },
            sampleDown = { channel.bytesDown },
            scope = scope,
        ).also { it.start(); throughput = it }
    }

    /**
     * This session's keep-alive/RTT poller — created together with the session when the target's
     * keep-alive cadence is on ([SshTarget.keepAliveSeconds] > 0, SSH-only), `null` while not
     * connected or with keep-alive off (no pings — the status bar shows no RTT). Unlike the other
     * open* accessors it never creates anything: the loop's lifecycle belongs to the session
     * (keep-alive must run without any UI polling). Stopped by [disconnect].
     */
    fun openPing(): PingController? = ping

    /** Close the session (if any) and return to the form. Cancels any active connect and auto-reconnect. */
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        // Drop the secret reference right away (auth may carry a password/key) — don't hold it on
        // the heap longer than the connection's lifetime.
        lastAuth = null
        lastTarget = null
        // Cancelled before Connected — discard the not-yet-fired one-shot action (Run on host).
        pendingOnConnected = null
        // Stop pointing at the disconnected host: a palette opened later must not attribute its
        // commands to a session that is gone.
        historyKey = null
        releaseSessionResources()
        uiState = ConnectionUiState.Form
    }

    /**
     * Disables auto-reconnect WITHOUT touching the live session: cancels a pending reconnect and
     * clears the saved target/auth. Called on vault lock — the open socket is left alive (project
     * decision), but a new auth handshake after a drop on a locked vault is not allowed
     * (zero-knowledge): without [lastAuth], a drop lands in [ConnectionUiState.Disconnected] with
     * no attempts, and the user reconnects manually after unlocking.
     */
    fun clearReconnectCredentials() {
        reconnectJob?.cancel()
        reconnectJob = null
        lastAuth = null
        lastTarget = null
        // Lock also cancels the pending first-connect action (Run on host): a snippet command must
        // not fire into the terminal if the handshake completes after the vault is already locked.
        pendingOnConnected = null
    }

    /**
     * Watches this session's shell for closure: once [TerminalState.Closed] arrives, dispatches
     * loss handling to [onSessionLost]. The observer lives on the session scope, so our own
     * [disconnect] (which cancels that scope) kills it BEFORE Closed arrives — this path is
     * reached ONLY on a server-side drop, which is what distinguishes an unintended loss (->
     * auto-reconnect) from an intentional close (-> Form).
     */
    private fun watchForSessionLoss(terminal: TerminalScreenState, sScope: CoroutineScope) {
        sScope.launch {
            val closed = terminal.state.first { it is TerminalState.Closed } as TerminalState.Closed
            // Dispatch loss handling onto the main [scope] — the same one [disconnect] runs on.
            // Otherwise onSessionLost would run on the session scope (Dispatchers.Default), racing
            // reconnectJob writes/cancels against disconnect on the UI thread. On one scope they're
            // serialized.
            scope.launch { onSessionLost(terminal, closed.cleanExit) }
        }
    }

    /**
     * Session closed not on our initiative: release resources (keeping the [frozen] screen for
     * display). On a clean shell exit ([cleanExit] — `exit`/EOF), there is NO reconnect: clears the
     * saved credentials and shows a neutral "Session closed". Otherwise (transport drop), starts
     * auto-reconnect to the last [lastTarget]/[lastAuth] — but ONLY for SSH; Telnet/Serial have no
     * reconnect (see below). Without saved target/credentials, stays in
     * [ConnectionUiState.Disconnected] with no attempts. The Connected guard prevents re-entry.
     */
    private fun onSessionLost(frozen: TerminalScreenState, cleanExit: Boolean) {
        if (uiState !is ConnectionUiState.Connected) return
        releaseSessionResources()
        if (cleanExit) {
            // The user closed the shell themselves (`exit`) — close the session, no reconnect. Drop
            // the secret (auth may carry a password/key): no point holding it, there won't be a new connect.
            lastAuth = null
            lastTarget = null
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = 0, cleanExit = true)
            return
        }
        val target = lastTarget
        val auth = lastAuth
        if (target == null || auth == null) {
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = 0)
            return
        }
        // Auto-reconnect only for SSH. It doesn't make sense for Telnet/Serial: there's no
        // authentication, and a "drop" there is usually the server closing the session or the
        // device disappearing (cable unplugged / rig stopped) — silently reconnecting is pointless;
        // the user connects again manually. Mosh is excluded too: the protocol itself survives
        // outages and roaming (that's its point), so its session never "drops" on network loss —
        // reaching here means the server shut down or the socket died, and a silent re-bootstrap
        // would open a brand-new remote session behind the user's back.
        if (target.connectionType != ConnectionType.SSH) {
            lastAuth = null
            lastTarget = null
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = 0)
            return
        }
        startReconnect(frozen, target, auth)
    }

    /**
     * Auto-reconnect loop: up to [maxReconnectAttempts] attempts with backoff
     * ([reconnectDelayMillis]) between them. Each attempt shows
     * [ConnectionUiState.Disconnected] with `reconnecting=true`, waits the backoff, then tries
     * [establishSession] (which sets [ConnectionUiState.Connected] and subscribes a new drop
     * observer on success). Once the limit is exhausted, stays in Disconnected with
     * `reconnecting=false`. Runs on the main [scope] (outlives the old session's teardown);
     * [disconnect] cancels [reconnectJob].
     */
    private fun startReconnect(frozen: TerminalScreenState, target: SshTarget, auth: SshAuth) {
        reconnectJob = scope.launch {
            var attempt = 1
            while (attempt <= maxReconnectAttempts) {
                uiState = ConnectionUiState.Disconnected(frozen, reconnecting = true, attempt = attempt)
                delay(reconnectDelayMillis(attempt))
                try {
                    establishSession(target, auth)
                    return@launch // success: establishSession moved to Connected and resubscribed the observer
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                }
            }
            uiState = ConnectionUiState.Disconnected(frozen, reconnecting = false, attempt = maxReconnectAttempts)
        }
    }

    /**
     * Releases the current connection's resources (tunnels/SFTP/metrics/pollers/session
     * scope/connection itself) WITHOUT touching [uiState]. Shared teardown for [disconnect]
     * (-> Form) and connection loss (-> reconnect). Idempotent: a repeat call on an already
     * cleaned-up controller is safe.
     */
    private fun releaseSessionResources() {
        val conn = connection
        portForwards?.stop()
        portForwards?.closeAll()
        portForwards = null
        val sftp = sftpClient
        sftpClient = null
        transferCoordinator = null
        metrics?.stop()
        metrics = null
        throughput?.stop()
        throughput = null
        ping?.stop()
        ping = null
        sessionScope?.cancel()
        sessionScope = null
        connection = null
        shellChannel = null
        cipher = null
        serverVersion = null
        agentForwarding = SshAgentForwarding.None
        if (sftp != null) closeSftpQuietly(sftp)
        if (conn != null) closeConnectionQuietly(conn)
    }

    /** Closes the connection without letting scope cancellation break teardown, and swallows errors. */
    private fun closeConnectionQuietly(conn: SshConnection) {
        scope.launch(NonCancellable) { runCatching { conn.disconnect() } }
    }

    /** Closes the SFTP channel in the background under [NonCancellable] (the SSH connection closes separately after). */
    private fun closeSftpQuietly(client: SftpClient) {
        scope.launch(NonCancellable) { runCatching { client.close() } }
    }
}
