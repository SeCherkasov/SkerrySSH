package app.skerry.shared.io

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * A plain-TCP server for one connection that breaks HTTP on purpose, written against blocking
 * sockets so a client that stops reading stops it too: what [sent] reaches is what the client let
 * through, not what a server-side buffer swallowed.
 */
internal class HostilePeer(
    private val server: ServerSocket = loopbackServer(),
    private val script: HostilePeer.(InputStream, OutputStream) -> Unit,
) : AutoCloseable {
    private val finished = CountDownLatch(1)

    @Volatile
    private var peer: Socket? = null

    /** What the script counts: bytes of a header flood, frames of a frame flood. */
    val sent = AtomicLong()

    /** The accepted connection, for a script that layers TLS on it. */
    val accepted: Socket get() = checkNotNull(peer)

    val port: Int get() = server.localPort
    val httpUrl: String get() = "http://127.0.0.1:$port"
    val wsUrl: String get() = "ws://127.0.0.1:$port/"

    init {
        thread(isDaemon = true, name = "hostile-peer") {
            try {
                server.accept().use { socket ->
                    peer = socket
                    script(socket.getInputStream().buffered(), socket.getOutputStream())
                }
            } catch (_: IOException) {
                // The client hung up, which is what these peers are for.
            } finally {
                finished.countDown()
            }
        }
    }

    /** Waits for [sent] to stop moving for [quietMillis], or [maxMillis] in all; returns it. */
    fun settle(quietMillis: Long = 1_000, maxMillis: Long = 8_000): Long {
        val deadline = System.currentTimeMillis() + maxMillis
        var last = sent.get()
        var quietSince = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline && !finished.await(100, TimeUnit.MILLISECONDS)) {
            val now = sent.get()
            if (now != last) {
                last = now
                quietSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietSince >= quietMillis) {
                break
            }
        }
        return sent.get()
    }

    override fun close() {
        peer?.close()
        server.close()
    }

    companion object {
        /** How much a header flood writes at most, so a client with no bound fails the test rather than the JVM. */
        const val FLOOD_CAP_BYTES = 64L * 1024 * 1024

        fun loopbackServer(): ServerSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

        /** Answers [statusLine] and then header lines that never end. */
        fun headerFlood(statusLine: String) = HostilePeer { input, output ->
            readHead(input)
            output.write("$statusLine\r\n".encodeToByteArray())
            val chunk = "X-Padding: flood\r\n".repeat(4096).encodeToByteArray()
            while (sent.get() < FLOOD_CAP_BYTES) {
                output.write(chunk)
                sent.addAndGet(chunk.size.toLong())
            }
            output.flush()
            // Holds the connection open, header section unfinished, until the client gives up.
            while (input.read() != -1) Unit
        }

        /**
         * Completes a WebSocket handshake, sends [first] as a text frame, then [count] frames of
         * [frameBytes] each with [opcode], as fast as the client takes them.
         */
        fun frameFlood(
            first: String,
            opcode: Int,
            frameBytes: Int,
            count: Int,
            server: ServerSocket = loopbackServer(),
        ) = HostilePeer(server) { input, output ->
            // One write, so the first frame arrives in the same read as the upgrade answer.
            output.write(upgradeAnswer(readHead(input)) + frame(0x1, first.encodeToByteArray()))
            output.flush()
            val payload = ByteArray(frameBytes) { '0'.code.toByte() }
            val flood = frame(opcode, payload)
            repeat(count) {
                output.write(flood)
                sent.incrementAndGet()
            }
            output.flush()
            while (input.read() != -1) Unit
        }

        /** `101` answer to the upgrade request [requestHead], accepting its key. */
        fun upgradeAnswer(requestHead: String, accept: String = acceptFor(requestHead)): ByteArray =
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").encodeToByteArray()

        private fun acceptFor(requestHead: String): String {
            val key = requestHead.lineSequence()
                .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(':')?.trim().orEmpty()
            return Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest("${key}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".encodeToByteArray()),
            )
        }

        /** An unmasked final frame, as a server sends it. */
        fun frame(opcode: Int, payload: ByteArray): ByteArray {
            val header = when {
                payload.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
                payload.size <= 0xFFFF -> byteArrayOf(
                    (0x80 or opcode).toByte(), 126, (payload.size shr 8).toByte(), payload.size.toByte(),
                )
                else -> error("frames past 64 KiB are not needed here")
            }
            return header + payload
        }

        /** Reads a request head up to its blank line and returns it. */
        fun readHead(input: InputStream): String {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b == -1) throw IOException("request ended inside its head")
                head.append(b.toChar())
            }
            return head.toString()
        }
    }
}
