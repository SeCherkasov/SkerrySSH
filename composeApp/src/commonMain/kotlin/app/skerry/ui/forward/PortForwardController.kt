package app.skerry.ui.forward

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.DynamicForwardSpec
import app.skerry.shared.ssh.LocalForwardSpec
import app.skerry.shared.ssh.PortForward
import app.skerry.shared.ssh.RemoteForwardSpec
import app.skerry.shared.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Forward direction: local (`-L`), remote (`-R`), or dynamic SOCKS (`-D`). */
enum class ForwardDirection { Local, Remote, Dynamic }

/** Typed, user-facing reason a forward could not be raised; the UI maps it to localized text. */
enum class ForwardFailure {
    /** The listener never came up: port busy or denied, or the server refused the request. */
    RaiseFailed,
}

/** State of a single forward in the list. */
sealed interface ForwardStatus {
    /** Listener is starting. */
    data object Starting : ForwardStatus

    /** Forward is active; [boundPort] is the actual listener port (assigned if requested as `0`). */
    data class Active(val boundPort: Int) : ForwardStatus

    /** Failed to start; [failure] is the typed reason, localized by the UI. */
    data class Failed(val failure: ForwardFailure) : ForwardStatus
}

/**
 * One row of the forward list. Parameters are immutable; [status] is observable Compose state,
 * mutated by the controller. [handle] holds the live [PortForward] for closing later.
 */
@Stable
class ForwardEntry internal constructor(
    val id: Long,
    val direction: ForwardDirection,
    val bindHost: String,
    val requestedPort: Int,
    val destHost: String,
    val destPort: Int,
) {
    var status: ForwardStatus by mutableStateOf(ForwardStatus.Starting)
        internal set

    /**
     * Whether the forward is paused (ACTIVE toggle off, port still held). Changed via
     * [PortForwardController.pause]/[resume].
     */
    var paused: Boolean by mutableStateOf(false)
        internal set

    /** Total bytes sent to the server, snapshot from the last telemetry poll. */
    var bytesUp: Long by mutableStateOf(0)
        internal set

    /** Total bytes received from the server, snapshot from the last telemetry poll. */
    var bytesDown: Long by mutableStateOf(0)
        internal set

    /** Current send rate (bytes/s), by delta between polls. */
    var upRate: Long by mutableStateOf(0)
        internal set

    /** Current receive rate (bytes/s), by delta between polls. */
    var downRate: Long by mutableStateOf(0)
        internal set

    internal var handle: PortForward? = null

    // Previous counter snapshot, for rate calculation during polling.
    internal var prevUp: Long = 0
    internal var prevDown: Long = 0
}

/**
 * Controller for the port forward list over a live [SshConnection]. `SshConnection` operations
 * are `suspend`, so the controller holds [scope] and starts/stops forwards via [launch].
 *
 * Each forward lives in its own [ForwardEntry] row and doesn't block the others; several forwards
 * can start in parallel. A start failure moves that row to [ForwardStatus.Failed] without affecting
 * others. Ownership of [SshConnection] is external; the controller only closes forwards
 * ([remove]/[closeAll]), not the connection.
 */
@Stable
class PortForwardController(
    private val connection: SshConnection,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 1000,
) {
    var forwards: List<ForwardEntry> by mutableStateOf(emptyList())
        private set

    private var nextId = 0L

    // Telemetry polling: for each active forward, samples byte counters and derives rate (bytes/s)
    // from the delta. [scope] is the shared controller scope that outlives the session, so session
    // teardown must call [stop] — nothing cancels this job otherwise.
    private val pollJob = scope.launch {
        while (isActive) {
            delay(pollIntervalMillis)
            pollTelemetry()
        }
    }

    /** Stops the telemetry poller. Session teardown; the controller is not reusable afterwards. */
    fun stop() {
        pollJob.cancel()
    }

    internal fun pollTelemetry() {
        forwards.forEach { entry ->
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

    /** Starts a local forward (`-L`). [bindPort] `0` means the OS picks the port. */
    fun addLocal(bindPort: Int, destHost: String, destPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Local, bindHost, bindPort, destHost, destPort)

    /** Starts a remote forward (`-R`). [bindPort] `0` means the server picks the port. */
    fun addRemote(bindPort: Int, destHost: String, destPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Remote, bindHost, bindPort, destHost, destPort)

    /**
     * Starts a dynamic forward (`-D`, SOCKS5 proxy). No destination parameters, since the
     * destination is dynamic; [bindPort] `0` means the OS picks the port.
     */
    fun addDynamic(bindPort: Int, bindHost: String = "127.0.0.1") =
        add(ForwardDirection.Dynamic, bindHost, bindPort, destHost = "", destPort = 0)

    private fun add(
        direction: ForwardDirection,
        bindHost: String,
        bindPort: Int,
        destHost: String,
        destPort: Int,
    ) {
        val entry = ForwardEntry(nextId++, direction, bindHost, bindPort, destHost, destPort)
        forwards = forwards + entry
        scope.launch {
            try {
                val forward = when (direction) {
                    ForwardDirection.Local ->
                        connection.forwardLocal(LocalForwardSpec(bindHost, bindPort, destHost, destPort))
                    ForwardDirection.Remote ->
                        connection.forwardRemote(RemoteForwardSpec(bindHost, bindPort, destHost, destPort))
                    ForwardDirection.Dynamic ->
                        connection.forwardDynamic(DynamicForwardSpec(bindHost, bindPort))
                }
                entry.handle = forward
                entry.status = ForwardStatus.Active(forward.boundPort)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Caught broadly (not just the expected PortForwardException) so an unexpected sshj
                // exception doesn't crash the shared session scope; it lands on the row as Failed.
                // The library text carries addresses/internals, so it never reaches the UI.
                entry.status = ForwardStatus.Failed(ForwardFailure.RaiseFailed)
            }
        }
    }

    /**
     * Pauses forward [entry]: the listener keeps the port but stops tunneling new connections.
     * [ForwardEntry.paused] updates immediately; the actual pause runs on [scope]. Only applies to
     * an active forward.
     */
    fun pause(entry: ForwardEntry) {
        if (entry.status !is ForwardStatus.Active) return
        entry.paused = true
        scope.launch { runCatching { entry.handle?.pause() } }
    }

    /** Resumes a previously paused forward [entry]. */
    fun resume(entry: ForwardEntry) {
        if (entry.status !is ForwardStatus.Active) return
        entry.paused = false
        scope.launch { runCatching { entry.handle?.resume() } }
    }

    /** Removes forward [entry] from the list and closes its listener, if started. */
    fun remove(entry: ForwardEntry) {
        forwards = forwards - entry
        scope.launch { runCatching { entry.handle?.close() } }
    }

    /** Removes all forwards (panel/session close). The connection stays open. */
    fun closeAll() {
        val current = forwards
        forwards = emptyList()
        scope.launch { current.forEach { runCatching { it.handle?.close() } } }
    }
}
