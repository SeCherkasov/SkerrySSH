package app.skerry.shared.ssh

import app.skerry.shared.agent.AgentSessionChannel
import app.skerry.shared.agent.SshjAgentForwarder
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SshjSftpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.transport.TransportException

/**
 * Live sshj connection: exec/shell/SFTP/forwards over one authenticated client. [upstream] holds
 * the ProxyJump hop clients this connection is tunneled through (entry point first, empty for a
 * direct connection); they only live to carry [client]'s traffic and are closed with it.
 *
 * [agentForwarder] is present when this target asked for SSH agent forwarding: the interactive
 * shell then requests it and the forwarder answers the server's agent channels. It dies with the
 * connection ([disconnect]) — the agent must not stay reachable from a server whose session is gone.
 */
internal class SshjConnection(
    private val client: SSHClient,
    override val cipher: String?,
    override val serverVersion: String?,
    private val upstream: List<SSHClient> = emptyList(),
    private val agentForwarder: SshjAgentForwarder? = null,
) : SshConnection {

    override val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    // Written on the IO thread that opens the shell, read from the UI (info panel) — hence the
    // atomic reference rather than a plain field.
    private val agentForwardingState = AtomicReference(SshAgentForwarding.None)

    override val agentForwarding: SshAgentForwarding get() = agentForwardingState.get()

    override suspend fun exec(command: String): ExecResult = withContext(Dispatchers.IO) {
        try {
            // Entire exec is under a timeout: otherwise a server process that hangs without closing
            // stdout (`tail -f`, waiting for input) would block readAtMost forever, and cmd.join(timeout)
            // would never be reached. withTimeoutOrNull cancels the inner work and returns null (like
            // measureRoundTrip), without conflating a timeout with external cancellation; reads under
            // runInterruptible are actually interruptible.
            val result = withTimeoutOrNull(EXEC_TIMEOUT_SECONDS * 1000L) {
                client.startSession().use { session ->
                    val cmd = session.exec(command)
                    // Output is capped: an untrusted/hung server must not be able to exhaust client
                    // memory with a verbose stream.
                    val stdout = runInterruptible { cmd.inputStream.readAtMost(MAX_EXEC_OUTPUT_BYTES) }.decodeToString()
                    val stderr = runInterruptible { cmd.errorStream.readAtMost(MAX_EXEC_OUTPUT_BYTES) }.decodeToString()
                    runInterruptible { cmd.join(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                    ExecResult(exitCode = cmd.exitStatus, stdout = stdout, stderr = stderr)
                }
            }
            result ?: throw SshConnectionException("Command execution timed out")
        } catch (e: IOException) {
            throw SshConnectionException("Command execution failed", e)
        }
    }

    override suspend fun measureRoundTrip(): Long? = withContext(Dispatchers.IO) {
        if (!client.isConnected) return@withContext null
        val startNanos = System.nanoTime()
        // Timeout is enforced externally via withTimeoutOrNull: on expiry the coroutine is cancelled
        // (interrupting the blocking retrieve via runInterruptible) and a clean null is returned, rather
        // than guessing "reply or timeout" from elapsed time. External cancellation is not swallowed.
        withTimeoutOrNull(PING_TIMEOUT_MILLIS) {
            // sendGlobalRequest is OUTSIDE runInterruptible: the Promise is registered in sshj's state
            // before we enter the interruptible wait for the reply, so interruption never leaves a
            // dangling Promise (worst case, connection teardown on disconnect picks it up).
            val replied = try {
                val promise = client.connection.sendGlobalRequest(KEEPALIVE_REQUEST, true, ByteArray(0))
                // keepalive@openssh.com, wantReply=true: OpenSSH replies SUCCESS (retrieve returns);
                // other servers reply REQUEST_FAILURE (retrieve throws ConnectionException). Both count
                // as a round trip.
                runInterruptible { promise.retrieve() }
                true
            } catch (e: ConnectionException) {
                true // REQUEST_FAILURE is a server reply; the round trip happened
            } catch (e: TransportException) {
                false // transport broke; no round trip
            }
            if (replied) (System.nanoTime() - startNanos) / 1_000_000 else null
        }
    }

    override suspend fun openShell(size: PtySize, term: String): ShellChannel =
        withContext(Dispatchers.IO) {
            try {
                openShellChannel(newSession(), size, term)
            } catch (e: IOException) {
                throw SshConnectionException("Failed to open shell channel", e)
            }
        }

    /**
     * A session channel for the interactive shell. Without agent forwarding this is sshj's own
     * `startSession()`; with it, the same channel plus an `auth-agent-req@openssh.com` request,
     * which has to be sent before the PTY/shell requests (see [AgentSessionChannel]).
     *
     * Only the shell gets the agent — one-shot [exec] channels (metrics polling, service scan, the
     * Mosh bootstrap) run without it, so background machinery can never hand keys to a server.
     */
    private fun newSession(): Session {
        val forwarder = agentForwarder ?: return client.startSession()
        val session = AgentSessionChannel(client.connection, client.remoteCharset)
        session.open()
        val granted = forwarder.requestOn(session)
        agentForwardingState.set(if (granted) SshAgentForwarding.Active else SshAgentForwarding.Refused)
        return session
    }

    override suspend fun openSftp(): SftpClient = withContext(Dispatchers.IO) {
        try {
            SshjSftpClient(client.newSFTPClient())
        } catch (e: IOException) {
            throw SshConnectionException("Failed to open SFTP subsystem", e)
        }
    }

    override suspend fun forwardLocal(spec: LocalForwardSpec): PortForward =
        withContext(Dispatchers.IO) {
            // Each accepted connection is tunneled ourselves through a direct-tcpip channel to
            // destHost:destPort — this gives traffic counters and pause (see [SshjLocalForward]);
            // sshj's stock LocalPortForwarder hides the pumping internally.
            SshjLocalForward(client, bindForwardListener(spec.bindHost, spec.bindPort), spec.destHost, spec.destPort)
        }

    override suspend fun forwardRemote(spec: RemoteForwardSpec): PortForward =
        withContext(Dispatchers.IO) {
            try {
                SshjRemoteForward.open(
                    client.remotePortForwarder,
                    RemotePortForwarder.Forward(spec.bindHost, spec.bindPort),
                    spec.destHost,
                    spec.destPort,
                )
            } catch (e: IOException) {
                throw PortForwardException(
                    "Server rejected remote forward ${spec.bindHost}:${spec.bindPort}", e,
                )
            }
        }

    override suspend fun forwardDynamic(spec: DynamicForwardSpec): PortForward =
        withContext(Dispatchers.IO) {
            // Listener as for `-L`; each accepted connection then speaks the SOCKS5 protocol.
            SshjDynamicForward(client, bindForwardListener(spec.bindHost, spec.bindPort))
        }

    override suspend fun disconnect() {
        // Timeout + runInterruptible: disconnect is normally fast, but writing SSH_MSG_DISCONNECT to an
        // already-dead socket (full TCP buffer) could hang indefinitely, and this is called from the UI
        // thread on tab close. On timeout, just force-close the client.
        withContext(Dispatchers.IO) {
            // Before the transport goes down: stop answering agent channels and drop the opener, so
            // no serving coroutine survives the session that authorized it.
            agentForwarder?.let { runCatching { it.stop() } }
            withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) { runInterruptible { client.disconnect() } }
                ?: runCatching { client.close() }
            // ProxyJump hops die with the session: closing the target client only closes its
            // direct-tcpip channel — each hop's own transport must be shut down too. Reverse
            // (innermost-first) order, same hang guard per hop.
            upstream.asReversed().forEach { hopClient ->
                withTimeoutOrNull(DISCONNECT_TIMEOUT_MILLIS) { runInterruptible { hopClient.disconnect() } }
                    ?: runCatching { hopClient.close() }
            }
            Unit
        }
    }

    /** Read at most [limit] bytes from the stream (remainder discarded; session.use closes it). */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = read(chunk, 0, minOf(chunk.size, limit - total))
            if (n < 0) break
            out.write(chunk, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private companion object {
        const val EXEC_TIMEOUT_SECONDS = 30L
        const val KEEPALIVE_REQUEST = "keepalive@openssh.com"
        const val PING_TIMEOUT_MILLIS = 5_000L
        const val DISCONNECT_TIMEOUT_MILLIS = 5_000L
        const val MAX_EXEC_OUTPUT_BYTES = 1 * 1024 * 1024 // 1 MiB per stdout and per stderr
    }
}

/**
 * Bind a local listener for forwards (`-L`, `-D`): bound ourselves so the actual port can be read
 * when bindPort=0, and "port in use" is caught as [PortForwardException] before the accept loop
 * starts. The socket is closed on a failed bind — its fd would otherwise linger until GC.
 */
internal fun bindForwardListener(
    bindHost: String,
    bindPort: Int,
    newSocket: () -> ServerSocket = ::ServerSocket,
): ServerSocket {
    var socket: ServerSocket? = null
    return try {
        newSocket().apply {
            socket = this
            reuseAddress = true
            bind(InetSocketAddress(bindHost, bindPort))
        }
    } catch (e: IOException) {
        runCatching { socket?.close() }
        throw PortForwardException("Failed to bind local port $bindHost:$bindPort", e)
    }
}

/**
 * Open the shell on an already-open [session] channel. On a failed PTY/shell request the channel is
 * closed before rethrowing — abandoned open channels accumulate on the connection until disconnect.
 */
internal fun openShellChannel(session: Session, size: PtySize, term: String): SshjShellChannel =
    try {
        session.allocatePTY(term, size.cols, size.rows, size.widthPx, size.heightPx, emptyMap())
        SshjShellChannel(session, session.startShell())
    } catch (e: Exception) {
        runCatching { session.close() }
        throw e
    }
