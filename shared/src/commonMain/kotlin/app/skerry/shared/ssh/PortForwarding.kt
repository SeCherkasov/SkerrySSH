package app.skerry.shared.ssh

/**
 * Port forwarding contract over an established SSH session. Opened from
 * [SshConnection.forwardLocal]/[SshConnection.forwardRemote]; platform implementation is sshj on
 * desktop/Android (jvmShared), same as the transport itself.
 *
 * Two directions:
 * - local (`-L`, [LocalForwardSpec]): listener runs on this machine, outbound connections are
 *   tunneled and opened by the server to the destination address;
 * - remote (`-R`, [RemoteForwardSpec]): listener runs on the server, inbound connections arrive
 *   over the tunnel and are opened by us to the local destination address.
 *
 * Dynamic (`-D`, [DynamicForwardSpec]): a SOCKS5 proxy runs on this machine; each SOCKS client
 *   supplies its own destination address, and a separate tunnel to the server is opened per
 *   connection (sshj has no built-in SOCKS forwarder, so the listener and protocol are implemented
 *   here).
 */

/**
 * Local forward (`-L`). Listener runs on [bindHost]:[bindPort] of this machine; each accepted
 * connection is tunneled over SSH and opened by the server to [destHost]:[destPort] (destination
 * resolved server-side). [bindPort] `0` lets the OS choose a free port (actual port in
 * [PortForward.boundPort]). Listens on loopback only by default.
 */
data class LocalForwardSpec(
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String,
    val destPort: Int,
)

/**
 * Remote forward (`-R`). Listener runs on the server at [bindHost]:[bindPort]; inbound connections
 * arrive over the tunnel and are opened by us to [destHost]:[destPort] (destination resolved
 * locally). [bindPort] `0` lets the server choose a free port (assigned port in
 * [PortForward.boundPort]). [bindHost] `""` means "all server interfaces" per RFC 4254.
 */
data class RemoteForwardSpec(
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val destHost: String,
    val destPort: Int,
)

/**
 * Dynamic forward (`-D`). A SOCKS5 proxy runs on [bindHost]:[bindPort] of this machine; the SOCKS
 * client supplies the destination address per connection, and a separate tunnel is opened for each
 * (destination resolved server-side, as with `-L`). [bindPort] `0` lets the OS choose a port
 * (actual port in [PortForward.boundPort]). Listens on loopback only by default. No destination
 * address here — it's dynamic, so the spec only carries listener parameters.
 */
data class DynamicForwardSpec(
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
)

/**
 * A live port forward. Active from open until [isActive] flips; [close] tears down the listener and
 * closes already-established tunneled connections. The SSH connection itself stays open.
 *
 * Carries its own telemetry ([bytesUp]/[bytesDown]) and supports pausing ([pause]/[resume]): while
 * paused the listener keeps its port but new connections aren't tunneled (accepted and immediately
 * closed); already-open tunnels run to completion. Throughput (bytes/s) is computed by the consumer
 * from the delta of the counters over time — the forward itself only reports monotonic totals.
 */
interface PortForward {
    val isActive: Boolean

    /** Forward is paused: listener holds the port but new connections aren't tunneled. */
    val isPaused: Boolean

    /**
     * Actual listener port. For a local forward, the port on this machine; for a remote forward,
     * the port on the server. If the spec requested `0`, this is the actually assigned port.
     */
    val boundPort: Int

    /**
     * Total bytes sent into the SSH channel (to the server) across all connections of this forward.
     * Monotonically increasing over the forward's lifetime; pausing or closing individual tunnels
     * doesn't reset it.
     */
    val bytesUp: Long

    /** Total bytes received from the SSH channel (from the server). Monotonic; never reset. */
    val bytesDown: Long

    /** Pause tunneling of new connections. Idempotent; the port stays held. */
    suspend fun pause()

    /** Resume tunneling after [pause]. Idempotent. */
    suspend fun resume()

    /** Tear down the forward. Idempotent. */
    suspend fun close()
}

/** Port forwarding error: listener failed to start, server rejected the request, or channel broke. */
class PortForwardException(message: String, cause: Throwable? = null) : SshException(message, cause)
