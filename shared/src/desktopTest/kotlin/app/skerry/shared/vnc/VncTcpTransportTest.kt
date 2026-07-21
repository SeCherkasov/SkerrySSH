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

    /** Serve a full None-security handshake (1x1 desktop), consume client messages up to the first
     *  FramebufferUpdateRequest, then hand the socket to [afterHandshake]. */
    private fun serveHandshakeThen(afterHandshake: (Socket) -> Unit) = serve { socket ->
        val out = socket.getOutputStream()
        val din = DataInputStream(socket.getInputStream())
        out.write("RFB 003.008\n".encodeToByteArray()); out.flush()
        din.readFully(ByteArray(12))
        out.write(byteArrayOf(1, RfbCodec.SEC_NONE.toByte())); out.flush()
        din.read()
        out.write(u32(0)); out.flush()
        din.read() // ClientInit
        out.write(u16(1)); out.write(u16(1)); out.write(ByteArray(16))
        out.write(u32(1)); out.write("d".encodeToByteArray()); out.flush()
        // Drain SetPixelFormat/SetEncodings until the initial FramebufferUpdateRequest, so the
        // client is fully past the handshake before afterHandshake acts on the socket.
        var sawRequest = false
        while (!sawRequest) {
            when (din.read()) {
                0 -> din.readFully(ByteArray(19))
                2 -> {
                    din.readFully(ByteArray(1))
                    val n = din.readUnsignedShort()
                    din.readFully(ByteArray(4 * n))
                }
                3 -> {
                    din.readFully(ByteArray(9))
                    sawRequest = true
                }
                else -> return@serve
            }
        }
        afterHandshake(socket)
    }

    private suspend fun collectClosed(session: VncSession): VncUpdate.Closed {
        val closed = CompletableFuture<VncUpdate.Closed>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                session.updates.collect { if (it is VncUpdate.Closed) closed.complete(it) }
            }.onFailure { closed.completeExceptionally(it) }
        }
        return try {
            withContext(Dispatchers.IO) { closed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } finally {
            collector.cancel()
            session.close()
        }
    }

    @Test
    fun `server EOF surfaces as a clean close`() = runBlocking {
        serveHandshakeThen { socket -> socket.close() } // orderly FIN right after the handshake

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        assertEquals(true, collectClosed(session).cleanExit)
    }

    @Test
    fun `garbage on the stream surfaces as a dirty close`() = runBlocking {
        serveHandshakeThen { socket ->
            socket.getOutputStream().apply { write(99); flush() } // unknown message type
        }

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        assertEquals(false, collectClosed(session).cleanExit)
    }

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

    @Test
    fun `a resize triggers a full framebuffer request`() = runBlocking {
        // After a resize the framebuffer content is undefined (RFB DesktopSize semantics), so the
        // follow-up request must be non-incremental — an incremental one could leave the new,
        // larger screen mostly black until something changes remotely.
        val followUp = CompletableFuture<Int>() // incremental flag of the request after the resize
        serve { socket ->
            val out = socket.getOutputStream()
            val din = DataInputStream(socket.getInputStream())
            out.write("RFB 003.008\n".encodeToByteArray()); out.flush()
            din.readFully(ByteArray(12))
            out.write(byteArrayOf(1, RfbCodec.SEC_NONE.toByte())); out.flush()
            din.read()
            out.write(u32(0)); out.flush()
            din.read() // ClientInit
            out.write(u16(2)); out.write(u16(1)); out.write(ByteArray(16))
            out.write(u32(1)); out.write("d".encodeToByteArray()); out.flush()
            // A resize-only update: one DesktopSize pseudo-rect to 4x2.
            out.write(byteArrayOf(0, 0)); out.write(u16(1))
            out.write(u16(0)); out.write(u16(0)); out.write(u16(4)); out.write(u16(2))
            out.write(u32(RfbCodec.ENC_DESKTOP_SIZE)); out.flush()
            // Parse the client stream until the SECOND FramebufferUpdateRequest (the first is the
            // initial full request from the handshake; the second reacts to the resize).
            var requests = 0
            while (!followUp.isDone) {
                when (din.read()) {
                    0 -> din.readFully(ByteArray(19))                       // SetPixelFormat
                    2 -> {                                                  // SetEncodings
                        din.readFully(ByteArray(1))
                        val n = din.readUnsignedShort()
                        din.readFully(ByteArray(4 * n))
                    }
                    3 -> {                                                  // FramebufferUpdateRequest
                        val body = ByteArray(9)
                        din.readFully(body)
                        requests++
                        if (requests == 2) followUp.complete(body[0].toInt())
                    }
                    else -> break                                           // EOF / unexpected
                }
            }
        }

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        val sawResize = CompletableFuture<Unit>()
        // collect, not first{}: cancelling on the Resize would close the socket before the transport
        // gets to write the follow-up request this test is about.
        val collector = launch(Dispatchers.IO) {
            runCatching {
                session.updates.collect { if (it is VncUpdate.Resize) sawResize.complete(Unit) }
            }.onFailure { sawResize.completeExceptionally(it) }
        }
        try {
            withContext(Dispatchers.IO) { sawResize.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            assertEquals(0, withContext(Dispatchers.IO) { followUp.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) })
        } finally {
            collector.cancel()
            session.close()
        }
        Unit
    }
}
