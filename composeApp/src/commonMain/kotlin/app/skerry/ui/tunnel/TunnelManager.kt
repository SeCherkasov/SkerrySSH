package app.skerry.ui.tunnel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.TunnelStore
import app.skerry.ui.connection.JumpChainProblem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Editable tunnel fields without [Tunnel.id]: the create/edit form operates on a draft, and
 * [TunnelManager] assigns identity. `null` [id] means a new tunnel.
 */
data class TunnelDraft(
    val id: String? = null,
    val label: String,
    val hostId: String,
    val direction: TunnelDirection,
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String? = null,
    val destPort: Int? = null,
    val autostart: Boolean = false,
)

/**
 * Why a saved tunnel can't be dialled. Typed, not a message string: [resolve] runs outside the
 * composition, so the view localizes it (`tunnelFailureText`) — same rule as [JumpChainProblem].
 */
sealed interface TunnelUnavailable {
    /** The tunnel's host profile was deleted. */
    data object HostNotFound : TunnelUnavailable

    /** The host has no secret bound in the vault. */
    data object NoCredential : TunnelUnavailable

    /** The host's ProxyJump chain didn't resolve. */
    data class Jump(val problem: JumpChainProblem) : TunnelUnavailable

    /**
     * It was up and stopped working: the listener died, or the SSH connection under it dropped.
     * Typed like the rest — the telemetry poll that detects this runs outside the composition, and
     * a poll that had to resolve a string would be a suspension (and a failure point) per tick.
     */
    data object LinkLost : TunnelUnavailable
}

/** Resolution of a saved tunnel to connection parameters: either the host and secret are available, or not. */
sealed interface TunnelResolution {
    /** Ready to connect: host address and auth resolved from the vault. */
    data class Ready(val target: SshTarget, val auth: SshAuth) : TunnelResolution

    /** Cannot connect (host deleted, secret unbound, broken jump chain). */
    data class Unavailable(val reason: TunnelUnavailable) : TunnelResolution
}

/** Runtime state of a saved tunnel; config lives in [Tunnel], this is the on/off status. */
sealed interface TunnelStatus {
    /** Off: no connection, no forward. */
    data object Inactive : TunnelStatus

    /** Coming up: opening the connection and listener. */
    data object Connecting : TunnelStatus

    /** Active; [boundPort] is the listener's actual port (assigned port when `0` was requested). */
    data class Active(val boundPort: Int) : TunnelStatus

    /**
     * Failed to come up. [reason] is set when the failure was typed (config, not transport) and
     * takes precedence in the UI; [message] is the friendly transport string otherwise.
     */
    data class Failed(val message: String = "", val reason: TunnelUnavailable? = null) : TunnelStatus
}

/**
 * One row in the tunnel list: saved [tunnel] config (updated via [TunnelManager.save]) plus
 * observable runtime state. [handle]/[connection] hold the live forward and its own SSH
 * connection for later closing; each tunnel owns its own connection, not shared externally.
 */
@Stable
class TunnelEntry internal constructor(tunnel: Tunnel) {
    var tunnel: Tunnel by mutableStateOf(tunnel)
        internal set

    val id: String get() = tunnel.id

    var status: TunnelStatus by mutableStateOf(TunnelStatus.Inactive)
        internal set

    var bytesUp: Long by mutableStateOf(0)
        internal set
    var bytesDown: Long by mutableStateOf(0)
        internal set
    var upRate: Long by mutableStateOf(0)
        internal set
    var downRate: Long by mutableStateOf(0)
        internal set

    // Written by the telemetry poll (Dispatchers.Default) and by deactivate (the UI thread), so
    // both sides have to see each other's writes.
    @Volatile
    internal var handle: PortForward? = null

    @Volatile
    internal var connection: SshConnection? = null

    // Coroutine bringing the tunnel up (status Connecting); [TunnelManager.deactivate] cancels it
    // so a connection opening right now doesn't leak after deactivation. Volatile for the same
    // reason as the two above, and it is the field the Stop-then-Start guard reads: a stale read in
    // the cancelled attempt's `finally` would clear the newer attempt's job, leaving nothing able
    // to cancel it — not the next Stop, and not the vault lock.
    @Volatile
    internal var connectingJob: Job? = null

    internal var prevUp: Long = 0
    internal var prevDown: Long = 0

    internal fun resetCounters() {
        bytesUp = 0; bytesDown = 0; upRate = 0; downRate = 0; prevUp = 0; prevDown = 0
    }
}

/**
 * Manager for saved tunnels: a tunnel is a standalone object in [TunnelStore], not part of an
 * open terminal session. [activate] opens its own SSH connection to the bound host via
 * [transport] (in production, a transport with `ReadOnlyHostKeyVerifier(UnknownHost.Refuse)` — only a
 * host already trusted or covered by a trusted CA, since this connection has no terminal and nobody
 * watching it) and raises the forward; [deactivate] closes the forward and its connection. Host/secret
 * resolution is factored into [resolve] so the manager has no direct dependency on the host
 * manager or vault, and can be tested without them.
 *
 * Each tunnel lives in its own [TunnelEntry] row and doesn't block others; a failed raise sets
 * the row to [TunnelStatus.Failed] without affecting the manager.
 */
@Stable
class TunnelManager(
    private val store: TunnelStore,
    private val transport: SshTransport,
    private val resolve: (String) -> TunnelResolution,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 1000,
    /**
     * Transport for the service scan, which is a different kind of connection from a forward: the
     * user pressed a button and is watching the result, so an unknown host key is theirs to accept,
     * while [transport] refuses one because a forward runs unattended. Defaults to [transport] for
     * tests and previews, which care about neither distinction.
     */
    private val scanTransport: SshTransport = transport,
    private val newId: () -> String,
) {
    var tunnels: List<TunnelEntry> by mutableStateOf(store.all().map { TunnelEntry(it) })
        private set

    /** Discovery of listening services on a host, for forwarding one of them in a tap. */
    val services: ServiceScanController = ServiceScanController(scanTransport, resolve, scope)

    /** Aggregate throughput window and the failure journal behind the section's dashboard. */
    val telemetry: TunnelTelemetry = TunnelTelemetry(pollIntervalMillis)

    // Autostart is a one-shot per unlock: reloadManagers runs on every synced change, and re-running
    // it there would raise tunnels the user had just switched off. Rearmed by closeAll (the lock).
    private var autostarted = false

    // Ids raised by the last autostart run, so the section can say how many of them are down.
    // Snapshot state: dismissing the report has to reach the banner.
    private var autostartIds: Set<String> by mutableStateOf(emptySet())

    /**
     * Autostart tunnels that are currently failed. Derived rather than latched at the end of the
     * run: the raises settle asynchronously, and a tunnel the user retries by hand should leave the
     * report on its own.
     */
    val autostartFailures: List<TunnelEntry>
        get() = tunnels.filter { it.id in autostartIds && it.status is TunnelStatus.Failed }

    /** Drops the report without touching the tunnels — the rows keep saying what happened. */
    fun dismissAutostartReport() {
        autostartIds = emptySet()
    }

    init {
        // Polls telemetry for active tunnels: samples counters and computes rate from the delta.
        scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                pollTick()
            }
        }
    }

    /**
     * Reloads the list from the store. Needed after writes that bypass the manager and after
     * vault unlock ([store] sits on top of the vault and returns empty while locked). Existing
     * rows are kept by id to preserve runtime state of active forwards; removed tunnels are
     * dropped, new ones added.
     */
    fun reload() {
        val incoming = store.all()
        // liveIds, not the ids of `incoming`: a record whose payload cannot be decrypted is missing
        // from `all()` but is very much still there — adopting an account dataKey leaves every
        // not-yet-pushed record sealed under the previous key. Tearing down on that would stop a
        // live tunnel the moment this desktop joins an existing sync account.
        val kept = store.liveIds()
        // A row that has really left the store takes its forward with it. Deleting a tunnel on
        // another device arrives here as a store write plus a reload — `delete()` is never called —
        // so dropping the entry alone would leave the port bound and the SSH connection open with
        // no row left to stop them.
        tunnels.filter { it.id !in kept }.forEach {
            deactivate(it.id)
            telemetry.forget(it.id)
        }
        val readable = incoming.map { it.id }.toHashSet()
        val byId = tunnels.associateBy { it.id }
        val refreshed = incoming.map { tunnel -> byId[tunnel.id]?.also { it.tunnel = tunnel } ?: TunnelEntry(tunnel) }
        // A row whose record is there but unreadable keeps the config it was loaded with, at the
        // end of the list: the tunnel exists and may well be up, and dropping the row would leave
        // its forward running with nothing on screen to stop it.
        tunnels = refreshed + tunnels.filter { it.id in kept && it.id !in readable }
    }

    fun find(id: String): TunnelEntry? = tunnels.firstOrNull { it.id == id }

    /**
     * Raises every tunnel flagged [Tunnel.autostart]. Called once the vault is open — a tunnel
     * needs its host's secret, so there is nothing to raise before that. Idempotent within one
     * unlock, so wiring it into `reloadManagers` doesn't fight the user's own toggles.
     */
    fun startAutostart() {
        if (autostarted) return
        autostarted = true
        val flagged = tunnels.filter { it.tunnel.autostart }
        autostartIds = flagged.map { it.id }.toSet()
        flagged.forEach { activate(it.id) }
    }

    /**
     * Creates (when [TunnelDraft.id] is null) or updates a tunnel and writes it to the store.
     * Returns the assigned id. Editing an active tunnel's config updates the row in place but
     * does not restart the forward; new parameters take effect on the next activation.
     */
    fun save(draft: TunnelDraft): String {
        val id = draft.id ?: newId()
        val tunnel = Tunnel(
            id = id,
            label = draft.label,
            hostId = draft.hostId,
            direction = draft.direction,
            bindHost = draft.bindHost,
            bindPort = draft.bindPort,
            destHost = draft.destHost,
            destPort = draft.destPort,
            autostart = draft.autostart,
        )
        store.put(tunnel)
        val existing = find(id)
        if (existing != null) existing.tunnel = tunnel else tunnels = tunnels + TunnelEntry(tunnel)
        return id
    }

    /** Deletes a tunnel: deactivates it if active, then removes it from the store and list. */
    fun delete(id: String) {
        deactivate(id)
        store.remove(id)
        tunnels = tunnels.filterNot { it.id == id }
        telemetry.forget(id)
    }

    /** Activates a tunnel: opens the host connection and raises the forward. Idempotent for an active tunnel. */
    fun activate(id: String) {
        val entry = find(id) ?: return
        if (entry.status is TunnelStatus.Active || entry.status is TunnelStatus.Connecting) return
        entry.status = TunnelStatus.Connecting
        // Called from the UI thread: the status read and Connecting write are synchronous (no
        // suspend between them), so a repeat tap can't slip past the guard. Job is kept so
        // deactivate can cancel it.
        entry.connectingJob = scope.launch {
            try {
                when (val resolution = resolve(entry.tunnel.hostId)) {
                    is TunnelResolution.Unavailable -> {
                        entry.status = TunnelStatus.Failed(reason = resolution.reason)
                        noteFailure(entry, TunnelFailureKind.Unavailable)
                    }
                    is TunnelResolution.Ready -> openForward(entry, resolution)
                }
            } finally {
                // Only when the field still points at THIS attempt. A dial is blocking, so a
                // cancelled attempt unwinds long after the user pressed Stop — by then a fresh
                // Start may own the field, and clearing it blindly would leave that attempt with
                // nothing to cancel it: neither the next Stop nor the vault lock, which is the
                // same call, and the tunnel would come up after being stopped.
                if (entry.connectingJob === coroutineContext[Job]) entry.connectingJob = null
            }
        }
    }

    private suspend fun openForward(entry: TunnelEntry, resolution: TunnelResolution.Ready) {
        var conn: SshConnection? = null
        try {
            // resolution.auth carries the secret as a String (not zeroed on JVM); lives on the
            // coroutine stack until connect.
            conn = transport.connect(resolution.target, resolution.auth)
            // The tunnel may have been deactivated while connect was in flight; ensureActive
            // avoids leaking the now-open connection.
            coroutineContext.ensureActive()
            val forward = raise(conn, entry.tunnel)
            entry.connection = conn
            entry.handle = forward
            entry.resetCounters()
            entry.status = TunnelStatus.Active(forward.boundPort)
            telemetry.active(entry.id, entry.tunnel.label, forward.boundPort)
            // Once it has come up, the autostart run is answered for it. A failure hours later,
            // after the user toggled it by hand, is not something autostart did.
            autostartIds = autostartIds - entry.id
        } catch (e: CancellationException) {
            closeQuietly(conn)
            throw e
        } catch (e: Exception) {
            closeQuietly(conn)
            entry.status = TunnelStatus.Failed(friendlyTunnelError(e))
            noteFailure(entry, tunnelFailureKind(e))
        }
    }

    private fun noteFailure(entry: TunnelEntry, kind: TunnelFailureKind) {
        telemetry.failed(entry.id, entry.tunnel.label, entry.tunnel.bindPort, kind)
    }

    private suspend fun raise(conn: SshConnection, tunnel: Tunnel): PortForward = when (tunnel.direction) {
        // destHost/destPort are required for -L/-R (see Tunnel KDoc); requireNotNull fails loudly
        // instead of silently forwarding to ":0".
        TunnelDirection.Local -> conn.forwardLocal(
            LocalForwardSpec(tunnel.bindHost, tunnel.bindPort, requireDestHost(tunnel), requireDestPort(tunnel)),
        )
        TunnelDirection.Remote -> conn.forwardRemote(
            RemoteForwardSpec(tunnel.bindHost, tunnel.bindPort, requireDestHost(tunnel), requireDestPort(tunnel)),
        )
        TunnelDirection.Dynamic -> conn.forwardDynamic(
            DynamicForwardSpec(tunnel.bindHost, tunnel.bindPort),
        )
    }

    private fun requireDestHost(tunnel: Tunnel): String =
        requireNotNull(tunnel.destHost) { "Tunnel ${tunnel.direction} requires a destination host" }

    private fun requireDestPort(tunnel: Tunnel): Int =
        requireNotNull(tunnel.destPort) { "Tunnel ${tunnel.direction} requires a destination port" }

    /** Deactivates a tunnel: closes the forward and its connection, resets the row to [TunnelStatus.Inactive]. */
    fun deactivate(id: String) {
        val entry = find(id) ?: return
        // Cancels the in-flight raise, if any, so a completing connect doesn't leak afterward.
        entry.connectingJob?.cancel()
        entry.connectingJob = null
        val handle = entry.handle
        val conn = entry.connection
        entry.handle = null
        entry.connection = null
        entry.status = TunnelStatus.Inactive
        entry.resetCounters()
        if (handle != null || conn != null) {
            scope.launch {
                runCatching { handle?.close() }
                runCatching { conn?.disconnect() }
            }
        }
    }

    /** Deactivates all tunnels and drops any scan result (vault lock, shutdown). */
    fun closeAll() {
        tunnels.forEach { deactivate(it.id) }
        services.reset()
        // The window and the journal describe connections that no longer exist, and behind a lock
        // screen they would still name hosts and ports. Autostart rearms for the next unlock.
        telemetry.reset()
        autostarted = false
        autostartIds = emptySet()
    }

    /**
     * One turn of the telemetry loop. One bad tick costs one tick: unguarded, a throw here ends the
     * loop for the rest of the session, and with it every traffic rate and the dead-tunnel
     * detection — silently, since nothing else visits a running tunnel.
     */
    internal fun pollTick() {
        // Last resort around a tick that is otherwise all arithmetic: one bad tick must cost one
        // tick, not the loop — with it goes every traffic rate and the dead-tunnel detection, and
        // nothing restarts it. A row that throws is handled where it throws (see [pollTelemetry]).
        try {
            pollTelemetry()
        } catch (e: CancellationException) {
            // Kept in step with the per-row guard below, which rethrows it for the same reason:
            // cancelling the scope has to end the loop, not be mistaken for a bad tick.
            throw e
        } catch (_: Exception) {
        }
    }

    internal fun pollTelemetry() {
        var up = 0L
        var down = 0L
        tunnels.forEach { entry ->
            val handle = entry.handle ?: return@forEach
            // One failing row is one failing row: without this the rest of the pass — every other
            // tunnel's counters and the throughput sample below — goes with it, and the section's
            // graph freezes with nothing said. The journal is what says it.
            try {
                pollEntry(entry, handle)?.let { (entryUp, entryDown) ->
                    up += entryUp
                    down += entryDown
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Torn down like any other dead tunnel, not merely journalled: a row left Active
                // with its port still bound would throw again on the very next tick, adding an
                // event a second while the forward it describes keeps accepting connections.
                dropDead(entry, handle)
            }
        }
        telemetry.sample(up, down)
    }

    /** One row's counters, or null when the tunnel turned out to be dead and was torn down. */
    private fun pollEntry(entry: TunnelEntry, handle: PortForward): Pair<Long, Long>? {
        // A forward whose listener died, or whose SSH connection dropped underneath it, was
        // never noticed: the row kept saying Active with the port still bound, every
        // application connecting through it got an immediate close, and nothing was ever
        // journalled. The poll is the only thing that looks at a running tunnel, so it is
        // where a dead one is caught.
        if (!handle.isActive || entry.connection?.isConnected == false) {
            dropDead(entry, handle)
            return null
        }
        val entryUp = handle.bytesUp
        val entryDown = handle.bytesDown
        entry.upRate = ((entryUp - entry.prevUp) * 1000 / pollIntervalMillis).coerceAtLeast(0)
        entry.downRate = ((entryDown - entry.prevDown) * 1000 / pollIntervalMillis).coerceAtLeast(0)
        entry.prevUp = entryUp
        entry.prevDown = entryDown
        entry.bytesUp = entryUp
        entry.bytesDown = entryDown
        return entry.upRate to entry.downRate
    }

    /**
     * Tears down a tunnel that stopped working on its own, and says so on the row and in the
     * journal.
     *
     * Non-suspending, like the whole tick — the reason is typed, so nothing here has to resolve a
     * string. It rechecks the handle it was called about, which narrows (it cannot close: the poll
     * and the UI run on different threads) the window where a Stop lands mid-teardown and the row
     * it already set to Inactive is overwritten with a failure the user caused on purpose.
     */
    private fun dropDead(entry: TunnelEntry, seenHandle: PortForward) {
        if (entry.handle !== seenHandle) return
        val handle = entry.handle
        val conn = entry.connection
        entry.handle = null
        entry.connection = null
        entry.resetCounters()
        entry.status = TunnelStatus.Failed(reason = TunnelUnavailable.LinkLost)
        noteFailure(entry, TunnelFailureKind.Connection)
        scope.launch {
            runCatching { handle?.close() }
            runCatching { conn?.disconnect() }
        }
    }

    private fun closeQuietly(conn: SshConnection?) {
        if (conn == null) return
        scope.launch { runCatching { conn.disconnect() } }
    }
}
