package app.skerry.shared.telnet

import app.skerry.shared.ssh.PtySize
import app.skerry.shared.ssh.ShellChannel
import app.skerry.shared.ssh.SshAuth
import app.skerry.shared.ssh.SshConnection
import app.skerry.shared.ssh.SshConnectionException
import app.skerry.shared.ssh.SshTarget
import app.skerry.shared.ssh.SshTransport
import app.skerry.shared.ssh.StreamOnlyConnection
import app.skerry.shared.ssh.StreamShellChannel
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Telnet transport (RFC 854) over a plain TCP socket. Lives in the shared JVM node (desktop +
 * Android), same as sshj. Telnet has no authentication: [SshAuth] is ignored, login/password are
 * entered in the terminal itself as ordinary data stream. SSH capabilities Telnet lacks (SFTP,
 * port forwarding, exec, cipher metrics) are marked unsupported and throw
 * [UnsupportedOperationException].
 */
class TelnetTransport(
    private val connectTimeoutMillis: Int = 15_000,
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(target.host, target.port), connectTimeoutMillis)
                socket.tcpNoDelay = true // interactive terminal: no Nagle, per-character responsiveness
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw SshConnectionException("Failed to connect to ${target.host}:${target.port}", e)
            }
            TelnetConnection(socket)
        }
}

/**
 * Connection over a single TCP socket: one interactive stream (shell), no sub-channels. SSH
 * capabilities Telnet lacks (exec, SFTP, forwarding) are thrown by the base [StreamOnlyConnection].
 */
private class TelnetConnection(private val socket: Socket) : StreamOnlyConnection("Telnet") {

    private val shellOpened = AtomicBoolean(false)

    override val isConnected: Boolean
        get() = socket.isConnected && !socket.isClosed

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Telnet connection already opened its stream" }
        return TelnetShellChannel(socket, TelnetCodec(termType = term, cols = size.cols, rows = size.rows))
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
    }
}

/**
 * Interactive Telnet stream: reads the socket, runs it through [TelnetCodec] (strips IAC
 * negotiation, sends replies back on the socket — [transform]), emits application bytes to
 * output. User writes double literal 0xFF bytes ([TelnetCodec.encode]). Negotiation-reply and
 * user writes are serialized via [writeLock] so bytes don't interleave on the socket's shared
 * output stream. The read-loop/close scaffolding lives in [StreamShellChannel]; a raw socket read
 * doesn't respond to Thread.interrupt, so unblockReadOnCancel = true (cancelling the collector
 * closes the socket).
 */
private class TelnetShellChannel(
    private val socket: Socket,
    private val codec: TelnetCodec,
) : StreamShellChannel(unblockReadOnCancel = true) {

    private val writeLock = Mutex()

    override val isOpen: Boolean
        get() = socket.isConnected && !socket.isClosed

    // Server isn't currently echoing input (WONT ECHO) — the upper layer must not write what was
    // typed into history (passwords).
    override val echoSuppressed: Boolean get() = !codec.serverEchoEnabled

    override fun readBlocking(buffer: ByteArray): Int = socket.getInputStream().read(buffer)

    override fun closeSource() {
        runCatching { socket.close() }
    }

    override suspend fun transform(chunk: ByteArray): ByteArray {
        val decoded = codec.consume(chunk)
        if (decoded.reply.isNotEmpty()) writeRaw(decoded.reply)
        return decoded.data
    }

    override suspend fun write(data: ByteArray) {
        writeRaw(codec.encode(data))
        countBytesUp(data.size)
    }

    override suspend fun resize(size: PtySize) {
        // Always remember the size in the codec; but send SB NAWS ONLY if the server negotiated
        // it (DO NAWS) — an unrequested sub-message may be treated as an error by a strict telnet
        // server and close the connection.
        val naws = codec.windowSize(size.cols, size.rows)
        if (codec.nawsNegotiated) {
            // A drop while sending NAWS isn't critical (the size is already remembered in the
            // codec) — swallow only the write error; CancellationException must propagate, that's
            // cancellation, not a failure.
            try {
                writeRaw(naws)
            } catch (_: SshConnectionException) {
            }
        }
    }

    private suspend fun writeRaw(bytes: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                val out = socket.getOutputStream()
                out.write(bytes)
                out.flush()
            } catch (e: IOException) {
                throw SshConnectionException("Failed to write to Telnet stream", e)
            }
        }
    }
}
