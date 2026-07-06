package app.skerry.shared.ssh

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Interactive sshj shell channel: reads the PTY into output, writes/resizes/closes via [session].
 * The read-loop/close scaffolding lives in [StreamShellChannel]; sshj's queue read responds to
 * Thread.interrupt, so unblockReadOnCancel isn't needed.
 */
internal class SshjShellChannel(
    private val session: Session,
    private val shell: Session.Shell,
) : StreamShellChannel(unblockReadOnCancel = false) {

    override val isOpen: Boolean
        get() = session.isOpen

    override fun readBlocking(buffer: ByteArray): Int = shell.inputStream.read(buffer)

    override fun closeSource() {
        // Close the input stream first to unblock the output read loop; only then tear down the
        // channel. The output collector reads only shell.inputStream and never touches session,
        // so session.close() is safe even before the read unblocks. runCatching: teardown must
        // not throw.
        runCatching { shell.inputStream.close() }
        runCatching { session.close() }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        try {
            shell.outputStream.write(data)
            shell.outputStream.flush()
            countBytesUp(data.size)
        } catch (e: IOException) {
            throw SshConnectionException("Failed to write to shell channel", e)
        }
    }

    override suspend fun resize(size: PtySize) = withContext(Dispatchers.IO) {
        try {
            shell.changeWindowDimensions(size.cols, size.rows, size.widthPx, size.heightPx)
        } catch (e: IOException) {
            throw SshConnectionException("Failed to resize PTY", e)
        }
    }
}
