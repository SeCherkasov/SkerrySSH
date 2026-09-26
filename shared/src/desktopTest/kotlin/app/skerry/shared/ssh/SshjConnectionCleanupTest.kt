package app.skerry.shared.ssh

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.schmizz.sshj.SSHClient
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

    /**
     * Issue #311: the listener must keep the platform's default bind exclusivity. `SO_REUSEADDR` is
     * a TIME_WAIT convenience on Unix — where the JDK sets it for a `ServerSocket` anyway — but on
     * Windows asking for it drops `SO_EXCLUSIVEADDRUSE`, and any other local process can then bind
     * the same `127.0.0.1:port` and receive the tunnel's connections instead. Asserted through the
     * setter rather than the socket option: the hijack itself only reproduces on Windows, while
     * "we never ask for it" is the same statement on every platform.
     */
    @Test
    fun `the forward listener never asks for SO_REUSEADDR`() {
        val socket = ReuseAddressWatchingSocket()

        bindForwardListener("127.0.0.1", 0) { socket }.use {
            assertFalse(socket.reuseAddressRequested, "SO_REUSEADDR costs the exclusive bind on Windows")
        }
    }

    /**
     * The graceful disconnect writes SSH_MSG_DISCONNECT, and a write into a dead peer's full TCP
     * buffer neither finishes nor answers an interrupt — `runInterruptible` then waits for it as long
     * as it takes, and the timeout around it never fires. Only closing the socket ends that write.
     */
    @Test
    fun `disconnect returns within its bound when the graceful disconnect is stuck`() = runBlocking<Unit> {
        val target = StuckDisconnectClient()
        val hop = StuckDisconnectClient()
        val connection = SshjConnection(
            target,
            cipher = null,
            serverVersion = null,
            upstream = listOf(hop),
            disconnectTimeoutMillis = 200,
        )

        val started = System.nanoTime()
        connection.disconnect()
        val millis = (System.nanoTime() - started) / 1_000_000

        assertTrue(millis < 3_000, "disconnect took ${millis}ms")
        assertTrue(target.socketClosed, "the target's socket was never closed")
        assertTrue(hop.socketClosed, "the jump host's socket was never closed")
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

/**
 * A client whose graceful disconnect behaves like a write into a full send buffer: interrupts do not
 * end it, closing the socket does. Capped so a regression fails the test instead of hanging it.
 */
private class StuckDisconnectClient : SSHClient() {
    private val released = CountDownLatch(1)
    private val socket = object : Socket() {
        override fun close() {
            released.countDown()
            super.close()
        }
    }

    val socketClosed: Boolean get() = released.count == 0L

    override fun getSocket(): Socket = socket

    override fun disconnect() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (released.count > 0 && System.nanoTime() < deadline) {
            try {
                released.await(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                // A blocked socket write does not answer an interrupt either.
            }
        }
    }
}

/** Records whether the bind helper asked for SO_REUSEADDR, whatever the platform's default is. */
private class ReuseAddressWatchingSocket : ServerSocket() {
    var reuseAddressRequested = false

    override fun setReuseAddress(on: Boolean) {
        if (on) reuseAddressRequested = true
        super.setReuseAddress(on)
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
