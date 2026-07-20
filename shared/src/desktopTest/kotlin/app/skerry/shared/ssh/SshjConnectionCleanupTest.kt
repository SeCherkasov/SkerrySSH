package app.skerry.shared.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.Message
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.connection.channel.direct.Session

/** Failure paths of channel/listener setup must not leak the half-open resource. */
class SshjConnectionCleanupTest {

    @Test
    fun `a failed bind closes the socket it created`() {
        ServerSocket().use { taken ->
            taken.bind(InetSocketAddress("127.0.0.1", 0))
            var created: ServerSocket? = null

            assertFailsWith<PortForwardException> {
                bindForwardListener("127.0.0.1", taken.localPort) { ServerSocket().also { created = it } }
            }

            assertTrue(created!!.isClosed)
        }
    }

    @Test
    fun `a rejected pty closes the session channel`() {
        val session = PtyRejectingSession()

        assertFailsWith<ConnectionException> {
            openShellChannel(session, PtySize(cols = 80, rows = 24, widthPx = 640, heightPx = 480), "xterm")
        }

        assertTrue(session.closed)
    }
}

/** Stub session whose PTY request fails, like a server rejecting pty-req would. */
private class PtyRejectingSession : Session {
    var closed = false

    override fun allocatePTY(
        term: String,
        cols: Int,
        rows: Int,
        width: Int,
        height: Int,
        modes: MutableMap<PTYMode, Int>,
    ): Unit = throw ConnectionException("pty rejected")

    override fun close() {
        closed = true
    }

    override fun allocateDefaultPTY(): Unit = throw UnsupportedOperationException()
    override fun exec(command: String): Session.Command = throw UnsupportedOperationException()
    override fun reqX11Forwarding(authProto: String, authCookie: String, screen: Int): Unit =
        throw UnsupportedOperationException()
    override fun setEnvVar(name: String, value: String): Unit = throw UnsupportedOperationException()
    override fun startShell(): Session.Shell = throw UnsupportedOperationException()
    override fun startSubsystem(name: String): Session.Subsystem = throw UnsupportedOperationException()
    override fun getAutoExpand(): Boolean = throw UnsupportedOperationException()
    override fun getID(): Int = throw UnsupportedOperationException()
    override fun getInputStream(): InputStream = throw UnsupportedOperationException()
    override fun getLocalMaxPacketSize(): Int = throw UnsupportedOperationException()
    override fun getLocalWinSize(): Long = throw UnsupportedOperationException()
    override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()
    override fun getRecipient(): Int = throw UnsupportedOperationException()
    override fun getRemoteCharset(): Charset = throw UnsupportedOperationException()
    override fun getRemoteMaxPacketSize(): Int = throw UnsupportedOperationException()
    override fun getRemoteWinSize(): Long = throw UnsupportedOperationException()
    override fun getType(): String = throw UnsupportedOperationException()
    override fun isOpen(): Boolean = throw UnsupportedOperationException()
    override fun setAutoExpand(autoExpand: Boolean): Unit = throw UnsupportedOperationException()
    override fun join(): Unit = throw UnsupportedOperationException()
    override fun join(timeout: Long, unit: TimeUnit): Unit = throw UnsupportedOperationException()
    override fun isEOF(): Boolean = throw UnsupportedOperationException()
    override fun getLoggerFactory(): LoggerFactory = throw UnsupportedOperationException()
    override fun handle(msg: Message, buf: SSHPacket): Unit = throw UnsupportedOperationException()
    override fun notifyError(error: SSHException): Unit = throw UnsupportedOperationException()
}
