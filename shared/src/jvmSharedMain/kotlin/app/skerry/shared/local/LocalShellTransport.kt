package app.skerry.shared.local

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
 * Local shell transport implementing the same [SshTransport] contract as SSH/Telnet/Serial, so the
 * terminal/session stack is reused unchanged. There is no network and no authentication —
 * [SshAuth] is ignored. SSH-only capabilities (SFTP, forwarding, exec) are absent and throw
 * [UnsupportedOperationException].
 *
 * Configuration comes via [SshTarget]: [SshTarget.host] carries the path to the shell binary to run
 * (a single executable, not a command line — blank → the platform default shell);
 * [SshTarget.port]/[SshTarget.username] are unused. Starting is
 * delegated to the platform [LocalShell]; [start] is injectable for tests (defaults to a real PTY).
 */
class LocalShellTransport(
    private val start: (LocalShellConfig) -> LocalShellHandle = { LocalShell.start(it) },
) : SshTransport {

    override suspend fun connect(target: SshTarget, auth: SshAuth): SshConnection =
        withContext(Dispatchers.IO) {
            val command = target.host.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
            LocalShellConnection(start, command)
        }
}

/**
 * Connection over a single local shell: one interactive stream. The PTY isn't started until
 * [openShell] (which carries the initial window size). SSH capabilities a local shell lacks (exec,
 * SFTP, forwarding) are thrown by the base [StreamOnlyConnection].
 */
private class LocalShellConnection(
    private val start: (LocalShellConfig) -> LocalShellHandle,
    private val command: List<String>,
) : StreamOnlyConnection("Local shell") {

    private val shellOpened = AtomicBoolean(false)

    @Volatile private var handle: LocalShellHandle? = null

    // The PTY is spawned lazily in openShell (it needs the initial window size), so before that the
    // connection reports connected (true) with no process yet — mirror this if adding health checks.
    override val isConnected: Boolean get() = handle?.isOpen ?: true

    override suspend fun openShell(size: PtySize, term: String): ShellChannel {
        check(shellOpened.compareAndSet(false, true)) { "Local shell already started its stream" }
        val started = withContext(Dispatchers.IO) {
            try {
                start(LocalShellConfig(command = command, cols = size.cols, rows = size.rows))
            } catch (e: LocalShellUnavailableException) {
                throw SshConnectionException(e.message ?: "Local terminal is not available", e)
            }
        }
        handle = started
        return LocalShellChannel(started)
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { runCatching { handle?.close() } }
    }
}

/**
 * Interactive local shell stream. The read-loop/close scaffolding lives in [StreamShellChannel]:
 * pty4j's blocking read ignores Thread.interrupt (unblockReadOnCancel = true — cancelling the
 * collector closes the shell), and `read < 0` means the shell process exited, which is a clean EOF
 * like an SSH `exit` (eofOnStreamEnd = true, the default). Writes are serialized via [writeLock].
 * Unlike serial, a local PTY has a window size — [resize] applies it.
 */
private class LocalShellChannel(private val handle: LocalShellHandle) :
    StreamShellChannel(unblockReadOnCancel = true) {

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
                throw SshConnectionException("Failed to write to the local shell", e)
            }
        }
    }

    override suspend fun resize(size: PtySize) = withContext(Dispatchers.IO) {
        // A local shell that has already exited can't be resized — swallow the native error rather
        // than surfacing it as a connection failure (the terminal is already closing).
        runCatching { handle.resize(size.cols, size.rows) }
        Unit
    }
}
