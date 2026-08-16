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
            }.onFailure {
                // Surfaced, not swallowed: without this a fake-server bug shows up only as the
                // client's opaque 15 s future timeout, with no hint of the actual cause.
                it.printStackTrace()
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

    /**
     * Announce ContinuousUpdates support, wait for the client's enable, stream two updates, then
     * fence; completes [result] with how many FramebufferUpdateRequests preceded the fence reply.
     */
    private fun serveContinuousUpdates(socket: Socket, result: CompletableFuture<Int>) {
        val out = socket.getOutputStream()
        val din = DataInputStream(socket.getInputStream())
        out.write(150); out.flush() // EndOfContinuousUpdates: the support announcement
        // The client answers by enabling the stream (type 150 + 9 body bytes).
        var enabled = false
        while (!enabled) {
            when (din.read()) {
                150 -> {
                    din.readFully(ByteArray(9))
                    enabled = true
                }
                3 -> din.readFully(ByteArray(9)) // requests from before the enable are fine
                else -> {
                    result.completeExceptionally(AssertionError("client died before enabling"))
                    return
                }
            }
        }
        // Two streamed 1x1 Raw updates, then a fence request forcing a client reply.
        repeat(2) {
            out.write(byteArrayOf(0, 0)); out.write(u16(1))
            out.write(u16(0)); out.write(u16(0)); out.write(u16(1)); out.write(u16(1)); out.write(u32(0))
            out.write(byteArrayOf(0, 0xFF.toByte(), 0, 0))
        }
        out.write(248); out.write(ByteArray(3)); out.write(u32(0x80000000.toInt())); out.write(0)
        out.flush()
        var requests = 0
        while (!result.isDone) {
            when (din.read()) {
                3 -> {
                    din.readFully(ByteArray(9))
                    requests++
                }
                248 -> {
                    din.readFully(ByteArray(7)) // padding + flags
                    val payloadLen = din.read()
                    din.readFully(ByteArray(payloadLen))
                    result.complete(requests)
                }
                else -> {
                    result.completeExceptionally(AssertionError("client died before the fence reply"))
                    return
                }
            }
        }
    }

    @Test
    fun `without the extension a plain update still gets the incremental follow-up request`() = runBlocking {
        // The request/response fallback is what every server without ContinuousUpdates lives on:
        // if the follow-up request stopped, those sessions would freeze after the first frame.
        val followUp = CompletableFuture<Int>() // incremental flag of the request after the update
        serveHandshakeThen { socket ->
            val out = socket.getOutputStream()
            val din = DataInputStream(socket.getInputStream())
            out.write(byteArrayOf(0, 0)); out.write(u16(1))
            out.write(u16(0)); out.write(u16(0)); out.write(u16(1)); out.write(u16(1)); out.write(u32(0))
            out.write(byteArrayOf(0, 0xFF.toByte(), 0, 0))
            out.flush()
            when (din.read()) {
                3 -> {
                    val body = ByteArray(9)
                    din.readFully(body)
                    followUp.complete(body[0].toInt())
                }
                else -> followUp.completeExceptionally(AssertionError("expected a FramebufferUpdateRequest"))
            }
        }

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        val collector = launch(Dispatchers.IO) {
            runCatching { session.updates.collect {} }
        }
        try {
            assertEquals(1, withContext(Dispatchers.IO) { followUp.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) })
        } finally {
            collector.cancel()
            session.close()
        }
        Unit
    }

    /**
     * Announce ContinuousUpdates, wait for the enable, send a DesktopSize resize to [w]×[h], then
     * complete [result] with (enable-width, enable-height, incremental flag) of the client's
     * re-enable and follow-up request.
     */
    private fun serveResizeUnderContinuousUpdates(socket: Socket, w: Int, h: Int, result: CompletableFuture<Triple<Int, Int, Int>>) {
        val out = socket.getOutputStream()
        val din = DataInputStream(socket.getInputStream())
        out.write(150); out.flush()
        var enabled = false
        while (!enabled) {
            when (din.read()) {
                150 -> {
                    din.readFully(ByteArray(9))
                    enabled = true
                }
                3 -> din.readFully(ByteArray(9))
                else -> {
                    result.completeExceptionally(AssertionError("client died before enabling"))
                    return
                }
            }
        }
        out.write(byteArrayOf(0, 0)); out.write(u16(1))
        out.write(u16(0)); out.write(u16(0)); out.write(u16(w)); out.write(u16(h))
        out.write(u32(RfbCodec.ENC_DESKTOP_SIZE)); out.flush()
        // The serial client stream must now carry the re-enable, then the full repaint request.
        if (din.read() != 150) {
            result.completeExceptionally(AssertionError("expected the re-enable first"))
            return
        }
        val enable = ByteArray(9)
        din.readFully(enable)
        if (din.read() != 3) {
            result.completeExceptionally(AssertionError("expected the repaint request after the re-enable"))
            return
        }
        val request = ByteArray(9)
        din.readFully(request)
        val enableW = ((enable[5].toInt() and 0xFF) shl 8) or (enable[6].toInt() and 0xFF)
        val enableH = ((enable[7].toInt() and 0xFF) shl 8) or (enable[8].toInt() and 0xFF)
        result.complete(Triple(enableW, enableH, request[0].toInt()))
    }

    @Test
    fun `a resize while streaming re-enables the stream and repaints in full`() = runBlocking {
        // The enabled region is a fixed rectangle: after a resize the codec re-enables it at the
        // new size, and the transport still asks for the (undefined) buffer in full.
        val result = CompletableFuture<Triple<Int, Int, Int>>()
        serveHandshakeThen { socket -> serveResizeUnderContinuousUpdates(socket, 4, 2, result) }

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        val collector = launch(Dispatchers.IO) {
            runCatching { session.updates.collect {} }
        }
        try {
            val (w, h, incremental) = withContext(Dispatchers.IO) { result.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            assertEquals(4, w)
            assertEquals(2, h)
            assertEquals(0, incremental)
        } finally {
            collector.cancel()
            session.close()
        }
        Unit
    }

    @Test
    fun `continuous updates stop the per-update requests`() = runBlocking {
        // Request/response caps the frame rate at one per round trip (V-01). Once the server
        // announces ContinuousUpdates and the client enables the stream, applied updates must NOT
        // be answered with FramebufferUpdateRequests any more. The fence at the end is the ordering
        // proof: the client processes messages serially, so any request it were still sending for
        // the two updates would be written before its fence reply.
        val requestsBeforeFenceReply = CompletableFuture<Int>()
        serveHandshakeThen { socket -> serveContinuousUpdates(socket, requestsBeforeFenceReply) }

        val transport = VncTcpTransport()
        val session = transport.connect(
            SshTarget(host = server.inetAddress.hostAddress, port = server.localPort, username = ""),
            VncAuth.None,
        )
        val collector = launch(Dispatchers.IO) {
            runCatching { session.updates.collect {} }
        }
        try {
            assertEquals(0, withContext(Dispatchers.IO) { requestsBeforeFenceReply.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) })
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
