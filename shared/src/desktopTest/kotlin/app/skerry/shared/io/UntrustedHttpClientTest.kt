package app.skerry.shared.io

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UntrustedHttpClientTest {

    /**
     * A request the server read and then dropped the connection on is not sent again behind the
     * caller's back: an SRP proof or a pairing claim that reached the server once is already spent.
     * The drop comes on a pooled keep-alive connection, the case an engine retries by default.
     */
    @Test
    fun `a request the connection dropped under is not silently sent again`() {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val requests = AtomicInteger()
        thread(isDaemon = true, name = "dropping-server") {
            runCatching {
                while (true) {
                    val socket = server.accept()
                    thread(isDaemon = true) {
                        socket.use {
                            val input = it.getInputStream()
                            // The first request on a connection is answered and kept alive; the second is read and dropped.
                            repeat(2) { n ->
                                if (!readRequest(input)) return@use
                                requests.incrementAndGet()
                                if (n == 0) {
                                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                    it.getOutputStream().flush()
                                }
                            }
                        }
                    }
                }
            }
        }
        server.use {
            val url = "http://127.0.0.1:${server.localPort}/pair"
            val failure = runBlocking {
                untrustedHttpClient().use { client ->
                    withTimeout(10_000) { client.post(url) { setBody("warm") } }
                    runCatching { withTimeout(10_000) { client.post(url) { setBody("claim") } } }.exceptionOrNull()
                }
            }
            assertTrue(failure != null && failure !is kotlinx.coroutines.TimeoutCancellationException, "got $failure")
            Thread.sleep(300)
            assertEquals(2, requests.get(), "the dropped request reached the server a second time")
        }
    }

    /** Reads one request head and its Content-Length body; false on end of stream. */
    private fun readRequest(input: java.io.InputStream): Boolean {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { it != -1 }?.toChar() ?: return false)
        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        repeat(length) { if (input.read() == -1) return false }
        return true
    }
}
