package app.skerry.shared.serial

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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serial port transport implementing the same [SshTransport] contract as SSH/Telnet, so the
 * terminal/session stack is reused unchanged. SSH-only capabilities (SFTP, forwarding, exec)
 * are absent and throw [UnsupportedOperationException].
 *
 * Configuration comes via [SshTarget]: [SshTarget.host] is the device name, [SshTarget.port] is
 * the baud rate. Serial has no authentication — [SshAuth] is ignored. Opening is delegated to the
 * platform [SerialSystem]; [openPort] is injectable for tests (defaults to a real port).
 */
class SerialTransport(
    private val openPort: (SerialConfig) -> SerialPortHandle = { SerialSystem.open(it) },
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val config = SerialConfig(portName = target.host, baudRate = target.port)
            val handle = try {
                openPort(config)
            } catch (e: SerialUnavailableException) {
                throw SshConnectionException(e.message ?: "Failed to open port ${target.host}", e)
            }
            SerialConnection(handle)
        }
}

/**
 * Connection over a single open port: one interactive stream. SSH capabilities serial lacks
 * (exec, SFTP, forwarding) are thrown by the base [StreamOnlyConnection].
 */
private class SerialConnection(private val handle: SerialPortHandle) : StreamOnlyConnection("Serial") {

    private val shellOpened = AtomicBoolean(false)

    override val isConnected: Boolean get() = handle.isOpen

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Port already opened its stream" }
        return SerialShellChannel(handle)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { handle.close() } }
    }
}

/**
 * Interactive serial port stream. The read-loop/close scaffolding lives in
 * [app.skerry.shared.ssh.StreamShellChannel]: native serial reads ignore Thread.interrupt
 * (unblockReadOnCancel = true — cancelling the collector closes the port), and `read < 0` means
 * the device disconnected, not "server closed the shell" (eofOnStreamEnd = false). Writes are
 * serialized via [writeLock]. Serial has no window size — [resize] is a no-op.
 */
private class SerialShellChannel(private val handle: SerialPortHandle) :
    StreamShellChannel(unblockReadOnCancel = true, eofOnStreamEnd = false) {

    private val writeLock = Mutex()

    override val isOpen: Boolean get() = handle.isOpen

    override fun readBlocking(buffer: ByteArray): Int = handle.read(buffer)

    override fun closeSource() {
        runCatching { handle.close() } // unblocks read in output
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                handle.write(data)
                countBytesUp(data.size)
            } catch (e: IOException) {
                throw SshConnectionException("Failed to write to serial port", e)
            }
        }
    }

    override suspend fun resize(size: PtySize) { /* serial port has no window size */ }
}
