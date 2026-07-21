package app.skerry.ui.tunnel

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/** Result of scanning a host for listening services. */
sealed interface ServiceScanState {
    /** Nothing scanned yet (or the result was cleared). */
    data object Idle : ServiceScanState

    /** Connecting to the host and asking it what it listens on. */
    data object Scanning : ServiceScanState

    /** Scan finished; [services] is empty when the host listens on nothing reachable. */
    data class Ready(val services: List<ListeningService>) : ServiceScanState

    /** The host can never answer: its connection type has no command channel (telnet, serial). */
    data object Unsupported : ServiceScanState

    /**
     * The scan didn't happen. [reason] is set when the failure was typed (config, not transport)
     * and takes precedence in the UI; [message] is the friendly transport string otherwise —
     * same split as [TunnelStatus.Failed].
     */
    data class Failed(val message: String = "", val reason: TunnelUnavailable? = null) : ServiceScanState
}

/**
 * Scans a saved host for listening TCP ports so they can be forwarded in one tap. Owned by
 * [TunnelManager] and dialling the same way its tunnels do — through [resolve] (host, secret and
 * ProxyJump chain from the vault) and [transport] — because the tunnel panel is global: the host
 * being scanned usually has no open session to borrow a connection from.
 *
 * The scan is a single exec round-trip on its own connection, which it closes again; nothing here
 * outlives [scan]. A host whose transport has no exec channel is a verdict
 * ([ServiceScanState.Unsupported]), not a retryable failure — the same rule as host monitoring.
 */
@Stable
class ServiceScanController(
    private val transport: SshTransport,
    private val resolve: (String) -> TunnelResolution,
    private val scope: CoroutineScope,
) {
    var state: ServiceScanState by mutableStateOf(ServiceScanState.Idle)
        private set

    /** Host the current [state] belongs to, so the view can't attribute a result to another host. */
    var scannedHostId: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    // Only the newest scan may publish. Cancellation alone isn't enough: a superseded scan whose
    // exec already returned would otherwise overwrite the newer result on its way out.
    private var epoch = 0

    /** Scans [hostId], superseding any scan already in flight. */
    fun scan(hostId: String) {
        job?.cancel()
        val mine = ++epoch
        scannedHostId = hostId
        state = ServiceScanState.Scanning
        job = scope.launch { run(mine, hostId) }
    }

    /** Cancels an in-flight scan and clears the result (host list closed, vault locked). */
    fun reset() {
        job?.cancel()
        job = null
        epoch++
        scannedHostId = null
        state = ServiceScanState.Idle
    }

    private suspend fun run(mine: Int, hostId: String) {
        when (val resolution = resolve(hostId)) {
            is TunnelResolution.Unavailable -> publish(mine, ServiceScanState.Failed(reason = resolution.reason))
            is TunnelResolution.Ready -> probe(mine, resolution)
        }
    }

    private suspend fun probe(mine: Int, resolution: TunnelResolution.Ready) {
        var conn: SshConnection? = null
        try {
            // resolution.auth carries the secret as a String (not zeroed on JVM); it lives on the
            // coroutine stack until connect, as in TunnelManager.openForward.
            conn = transport.connect(resolution.target, resolution.auth)
            coroutineContext.ensureActive()
            val output = conn.exec(SERVICE_SCAN_COMMAND)
            val services = parseListeningServices(output.stdout)
            // Nothing parsed *and* every branch of the command failed means the host has no tool
            // that can answer (a BSD netstat rejects the Linux flags outright) — a verdict, not an
            // empty list, which would claim the host listens on nothing.
            publish(
                mine,
                if (services.isEmpty() && output.exitCode != 0) {
                    ServiceScanState.Unsupported
                } else {
                    ServiceScanState.Ready(services)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnsupportedOperationException) {
            publish(mine, ServiceScanState.Unsupported)
        } catch (e: Exception) {
            publish(mine, ServiceScanState.Failed(friendlyTunnelError(e)))
        } finally {
            closeQuietly(conn)
        }
    }

    private fun publish(mine: Int, next: ServiceScanState) {
        if (mine == epoch) state = next
    }

    // Launched on the manager's scope, not the scan's: the scan may be finishing because it was
    // cancelled, and its connection still has to be handed back.
    private fun closeQuietly(conn: SshConnection?) {
        if (conn == null) return
        scope.launch { runCatching { conn.disconnect() } }
    }
}
