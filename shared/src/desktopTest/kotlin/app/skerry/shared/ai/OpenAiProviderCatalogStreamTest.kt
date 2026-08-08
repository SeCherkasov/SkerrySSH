package app.skerry.shared.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a mock engine cannot show. It hands the body over as one in-memory value, so it can tell
 * neither a streaming read from a buffered one, nor whether the client the app actually builds
 * follows redirects — a test that passes its own `followRedirects = false` client proves only that
 * the status mapping works. Both tests here go through the constructor the app uses, over loopback.
 */
class OpenAiProviderCatalogStreamTest {

    private val server = ServerSocket(0)
    private val victim = ServerSocket(0)
    private val pushed = AtomicLong(0)
    private val threads = mutableListOf<Thread>()

    @AfterTest
    fun tearDown() {
        server.close()
        victim.close()
        threads.forEach { it.join(2_000) }
    }

    /** Runs [handle] for one connection on [socket] in the background; request headers are drained first. */
    private fun serve(socket: ServerSocket, handle: (Socket) -> Unit) {
        val thread = Thread {
            runCatching {
                val client = socket.accept()
                val request = client.getInputStream().bufferedReader()
                val headers = buildList {
                    while (true) {
                        val line = request.readLine() ?: break
                        if (line.isEmpty()) break
                        add(line)
                    }
                }
                requestHeaders.set(headers)
                handle(client)
            }
        }
        thread.isDaemon = true
        thread.start()
        threads += thread
    }

    private val requestHeaders = AtomicReference<List<String>>(emptyList())

    @Test
    fun `the catalog read stops at the cap instead of pulling the whole body into memory`() = runTest {
        serve(server) { client ->
            val out = client.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: $FLOOD_BYTES\r\n\r\n".toByteArray())
            val chunk = ByteArray(64 * 1024) { 'x'.code.toByte() }
            var written = 0L
            while (written < FLOOD_BYTES) {
                out.write(chunk) // throws once the client drops the connection at the cap
                written += chunk.size
                pushed.set(written)
            }
        }

        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-secret", baseUrl = "http://127.0.0.1:${server.localPort}"))
        val failure = withContext(Dispatchers.IO) {
            assertFailsWith<AiException> { provider.listModels() }.also { provider.close() }
        }
        assertEquals(AiException.Kind.PROTOCOL, failure.kind, "an over-cap body is a protocol failure, not a network one")

        // Deliberately generous: what is being told apart is "read the cap and hung up" from "read
        // all 64 MiB", so the assertion does not depend on how much the kernel happened to buffer.
        assertTrue(
            pushed.get() < CAP_WITH_SOCKET_SLACK,
            "the client pulled ${pushed.get()} bytes; the cap is ${OpenAiProvider.MAX_CATALOG_BYTES}",
        )
    }

    @Test
    fun `the catalog request does not carry the key to a redirect target`() = runTest {
        val victimWasContacted = AtomicBoolean(false)
        serve(victim) { client ->
            victimWasContacted.set(true)
            client.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"data\":[]}".toByteArray())
        }
        serve(server) { client ->
            client.getOutputStream().write(
                "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${victim.localPort}/models\r\nContent-Length: 0\r\n\r\n".toByteArray(),
            )
        }

        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-secret", baseUrl = "http://127.0.0.1:${server.localPort}"))
        val failure = withContext(Dispatchers.IO) {
            assertFailsWith<AiException> { provider.listModels() }.also { provider.close() }
        }

        assertEquals(AiException.Kind.PROTOCOL, failure.kind)
        assertTrue(requestHeaders.get().any { it.startsWith("Authorization", ignoreCase = true) }, "sanity: the key is sent to the endpoint itself")
        assertFalse(victimWasContacted.get(), "the redirect target must never be contacted — it would receive the API key")
    }

    @Test
    fun `a catalog request to a dead port is a network failure, not an unknown one`() = runTest {
        val dead = ServerSocket(0).also { it.close() }
        val provider = OpenAiProvider(OpenAiConfig(apiKey = "sk-secret", baseUrl = "http://127.0.0.1:${dead.localPort}"))

        val failure = withContext(Dispatchers.IO) {
            assertFailsWith<AiException> { provider.listModels() }.also { provider.close() }
        }

        assertEquals(AiException.Kind.NETWORK, failure.kind, "the same classification chat() gives the same endpoint")
        assertNull(failure.message?.takeIf { it.contains("sk-secret") }, "the key must not travel in an error message")
    }

    private companion object {
        const val FLOOD_BYTES = 64L * 1024 * 1024
        const val CAP_WITH_SOCKET_SLACK = 16L * 1024 * 1024
    }
}
