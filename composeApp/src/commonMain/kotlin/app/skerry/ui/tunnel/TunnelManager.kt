package app.skerry.ui.tunnel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.PortForwardException
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshAuthenticationException
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshHostKeyRejectedException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.tunnel.Tunnel
import app.skerry.shared.tunnel.TunnelDirection
import app.skerry.shared.tunnel.TunnelStore
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.ptail_err_auth_failed
import app.skerry.ui.generated.resources.ptail_err_connection_failed
import app.skerry.ui.generated.resources.ptail_err_forward_failed
import app.skerry.ui.generated.resources.ptail_err_host_not_trusted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
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
)

/** Resolution of a saved tunnel to connection parameters: either the host and secret are available, or not. */
sealed interface TunnelResolution {
    /** Ready to connect: host address and auth resolved from the vault. */
    data class Ready(val target: SshTarget, val auth: SshAuth) : TunnelResolution

    /**
     * Cannot connect (host deleted, secret unbound, etc). [reason] is shown to the user as-is,
     * bypassing [TunnelManager.friendlyError] — [resolve] implementations must keep it generic,
     * with no file names, record ids, or system messages.
     */
    data class Unavailable(val reason: String) : TunnelResolution
}

/** Runtime state of a saved tunnel; config lives in [Tunnel], this is the on/off status. */
sealed interface TunnelStatus {
    /** Off: no connection, no forward. */
    data object Inactive : TunnelStatus

    /** Coming up: opening the connection and listener. */
    data object Connecting : TunnelStatus

    /** Active; [boundPort] is the listener's actual port (assigned port when `0` was requested). */
    data class Active(val boundPort: Int) : TunnelStatus

    /** Failed to come up; [message] is user-facing (no raw transport details). */
    data class Failed(val message: String) : TunnelStatus
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

    internal var handle: PortForward? = null
    internal var connection: SshConnection? = null

    // Coroutine bringing the tunnel up (status Connecting); [TunnelManager.deactivate] cancels it
    // so a connection opening right now doesn't leak after deactivation.
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
 * [transport] (in production, a transport with `ProbeHostKeyVerifier` — only already-trusted
 * hosts) and raises the forward; [deactivate] closes the forward and its connection. Host/secret
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
    private val resolve: (Tunnel) -> TunnelResolution,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 1000,
    private val newId: () -> String,
) {
    var tunnels: List<TunnelEntry> by mutableStateOf(store.all().map { TunnelEntry(it) })
        private set

    init {
        // Polls telemetry for active tunnels: samples counters and computes rate from the delta.
        scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                pollTelemetry()
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
        val byId = tunnels.associateBy { it.id }
        tunnels = store.all().map { tunnel -> byId[tunnel.id]?.also { it.tunnel = tunnel } ?: TunnelEntry(tunnel) }
    }

    fun find(id: String): TunnelEntry? = tunnels.firstOrNull { it.id == id }

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
                when (val resolution = resolve(entry.tunnel)) {
                    is TunnelResolution.Unavailable -> entry.status = TunnelStatus.Failed(resolution.reason)
                    is TunnelResolution.Ready -> openForward(entry, resolution)
                }
            } finally {
                entry.connectingJob = null
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
        } catch (e: CancellationException) {
            closeQuietly(conn)
            throw e
        } catch (e: Exception) {
            closeQuietly(conn)
            entry.status = TunnelStatus.Failed(friendlyError(e))
        }
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

    /** Deactivates all tunnels. */
    fun closeAll() {
        tunnels.forEach { deactivate(it.id) }
    }

    internal fun pollTelemetry() {
        tunnels.forEach { entry ->
            val handle = entry.handle ?: return@forEach
            val up = handle.bytesUp
            val down = handle.bytesDown
            entry.upRate = ((up - entry.prevUp) * 1000 / pollIntervalMillis).coerceAtLeast(0)
            entry.downRate = ((down - entry.prevDown) * 1000 / pollIntervalMillis).coerceAtLeast(0)
            entry.prevUp = up
            entry.prevDown = down
            entry.bytesUp = up
            entry.bytesDown = down
        }
    }

    private fun closeQuietly(conn: SshConnection?) {
        if (conn == null) return
        scope.launch { runCatching { conn.disconnect() } }
    }

    // Raw exception text (addresses/sshj internals) is never shown in the UI, only generic
    // messages, as in runConnectionTest. Host key rejection is called out separately: tunnels use
    // the probe verifier, so this is the expected outcome for a not-yet-trusted host.
    private suspend fun friendlyError(e: Exception): String = when (e) {
        is SshHostKeyRejectedException -> getString(Res.string.ptail_err_host_not_trusted)
        is SshAuthenticationException -> getString(Res.string.ptail_err_auth_failed)
        is PortForwardException -> getString(Res.string.ptail_err_forward_failed)
        is SshConnectionException -> getString(Res.string.ptail_err_connection_failed)
        else -> getString(Res.string.ptail_err_connection_failed)
    }
}
