package app.skerry.shared.ssh

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * Interactive sshj shell channel: reads the PTY into output, writes/resizes/closes via [session].
 * The read-loop/close scaffolding lives in [StreamShellChannel]; sshj's queue read responds to
 * Thread.interrupt, so unblockReadOnCancel isn't needed.
 *
 * [shell] is the started channel: a login shell (`startShell`) or a command run on the same PTY
 * (`exec`, container profiles). sshj types those as unrelated `Session.Shell`/`Session.Command`, so
 * the field takes their common [Channel] supertype (both are the same `SessionChannel` object).
 */
internal class SshjShellChannel(
    private val session: Session,
    private val shell: Channel,
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
        // sshj's SessionChannel implements both Shell and Command, so an exec'd channel resizes
        // too; a channel without window support silently keeps its size rather than failing the
        // session over a cosmetic request.
        val window = shell as? Session.Shell ?: return@withContext
        try {
            window.changeWindowDimensions(size.cols, size.rows, size.widthPx, size.heightPx)
        } catch (e: IOException) {
            throw SshConnectionException("Failed to resize PTY", e)
        }
    }
}
