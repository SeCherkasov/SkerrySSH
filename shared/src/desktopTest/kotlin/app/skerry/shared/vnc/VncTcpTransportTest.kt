package app.skerry.shared.vnc

import app.skerry.shared.ssh.SshTarget
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val TIMEOUT_MS = 15_000L

/** Integration test for the VNC transport against a raw [ServerSocket] speaking RFB in-process. */
class VncTcpTransportTest {

    private lateinit var server: ServerSocket
    private val clients = mutableListOf<Socket>()

    @BeforeTest
    fun start() {
        server = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
    }

    @AfterTest
    fun stop() {
        clients.forEach { runCatching { it.close() } }
        runCatching { server.close() }
    }

    private fun serve(handle: (Socket) -> Unit) {
        thread(name = "vnc-test-server", isDaemon = true) {
            runCatching {
                val s = server.accept()
                clients.add(s)
                handle(s)
            }
        }
    }

    private fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    @Test
    fun `handshake then a raw update reaches the framebuffer`() = runBlocking {
        val chosenSecurity = CompletableFuture<Int>()
        serve { socket ->
            val out = socket.getOutputStream()
            val din = DataInputStream(socket.getInputStream())
            // Version
            out.write("RFB 003.008\n".encodeToByteArray()); out.flush()
            din.readFully(ByteArray(12)) // client version
            // Security: offer None only
            out.write(byteArrayOf(1, RfbCodec.SEC_NONE.toByte())); out.flush()
            val sec = din.read()
            chosenSecurity.complete(sec)
            // SecurityResult OK
            out.write(u32(0)); out.flush()
            din.read() // ClientInit shared flag
            // ServerInit: 2x1, name "desk"
            out.write(u16(2)); out.write(u16(1)); out.write(ByteArray(16))
            out.write(u32(4)); out.write("desk".encodeToByteArray()); out.flush()
            // One Raw FramebufferUpdate: red then green (big-endian [pad,R,G,B]).
            out.write(byteArrayOf(0, 0)); out.write(u16(1))                 // type, padding, rectCount
            out.write(u16(0)); out.write(u16(0)); out.write(u16(2)); out.write(u16(1)); out.write(u32(0)) // rect, Raw
            out.write(byteArrayOf(0, 0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte(), 0))
            out.flush()
        }

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        // Collect on a separate coroutine (parity with TelnetTransportTest): the blocking read loop
        // drives itself while the main coroutine waits on a Future, instead of parking the runBlocking
        // event loop inside first {}.
        val gotRegion = CompletableFuture<Unit>()
        val collector = launch(Dispatchers.IO) {
            runCatching {
                session.updates.first { it is VncUpdate.Region && it.rects.isNotEmpty() }
                gotRegion.complete(Unit)
            }.onFailure { gotRegion.completeExceptionally(it) }
        }
        try {
            assertEquals("desk", session.serverName)
            withContext(Dispatchers.IO) { gotRegion.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            assertEquals(RfbCodec.SEC_NONE, chosenSecurity.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertEquals(0xFFFF0000.toInt(), session.framebuffer.pixels[0]) // red
            assertEquals(0xFF00FF00.toInt(), session.framebuffer.pixels[1]) // green
        } finally {
            collector.cancel()
            session.close()
        }
        Unit
    }
}
