package app.skerry.shared.io

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Ktor saves every non-streamed response whole before client code sees it, so a cap applied to
 * the body afterwards bounds nothing. The plugin has to stop the read itself.
 */
class ResponseSizeLimitTest {

    private fun client(body: suspend (HttpRequestData) -> ByteReadChannel, headers: Map<String, String> = emptyMap()) =
        HttpClient(
            MockEngine { request ->
                respond(
                    content = body(request),
                    status = HttpStatusCode.OK,
                    headers = Headers.build { headers.forEach { (name, value) -> append(name, value) } },
                )
            },
        ) { install(ResponseSizeLimit) { maxBytes = LIMIT } }

    @Test
    fun `a response within the limit is read whole`() = runTest {
        val body = ByteArray(LIMIT.toInt()) { 7 }
        assertContentEquals(body, client({ ByteReadChannel(body) }).get(URL).bodyAsBytes())
    }

    @Test
    fun `a response past the limit fails without being read whole`() = runTest {
        val source = MeteredChannel(HUGE)
        val failure = assertFails { client({ source }).get(URL) }
        assertNotNull(failure.responseTooLarge(), "failed with $failure")
        assertTrue(source.consumed <= LIMIT + 1, "took ${source.consumed} bytes for a limit of $LIMIT")
        assertTrue(source.cancelled, "the connection was left feeding the body")
    }

    @Test
    fun `a declared length past the limit fails before the body is read`() = runTest {
        val source = MeteredChannel(16)
        val http = client({ source }, headers = mapOf(HttpHeaders.ContentLength to "${LIMIT + 1}"))
        assertNotNull(assertFails { http.get(URL) }.responseTooLarge())
        assertEquals(0L, source.consumed)
        assertTrue(source.cancelled, "the connection was left feeding the body")
    }

    @Test
    fun `a streamed response is capped too`() = runTest {
        val http = client({ ByteReadChannel(ByteArray(HUGE)) })
        val failure = assertFails { http.prepareGet(URL).execute { it.bodyAsChannel().readRemaining() } }
        assertNotNull(failure.responseTooLarge(), "failed with $failure")
    }

    @Test
    fun `a body cut off mid-read fails instead of reading as whole`() = runTest {
        val source = ByteChannel()
        source.writeFully(ByteArray(10))
        source.flush()
        source.cancel(IOException("connection reset"))
        assertFails { client({ source }).get(URL).bodyAsBytes() }
    }

    @Test
    fun `a cap at the top of the range still reads the body`() = runTest(timeout = 10.seconds) {
        val body = ByteArray(100) { 3 }
        val http = HttpClient(MockEngine { respond(ByteReadChannel(body), HttpStatusCode.OK) }) {
            install(ResponseSizeLimit) { maxBytes = Long.MAX_VALUE }
        }
        assertContentEquals(body, http.get(URL).bodyAsBytes())
    }

    @Test
    fun `a client cannot install the plugin without a cap`() {
        assertFails { HttpClient(MockEngine { respond("") }) { install(ResponseSizeLimit) } }
    }

    /** The WebSocket session is built on a 101 answer's connection, which is no body to cap. */
    @Test
    fun `a switching protocols answer is left alone`() = runTest {
        val source = MeteredChannel(HUGE)
        val http = HttpClient(MockEngine { respond(source, HttpStatusCode.SwitchingProtocols) }) {
            install(ResponseSizeLimit) { maxBytes = LIMIT }
        }
        http.prepareGet(URL).execute {
            assertEquals(HttpStatusCode.SwitchingProtocols, it.status)
            assertFalse(source.cancelled, "the upgraded connection was cut off")
            assertEquals(0L, source.consumed)
        }
    }

    private companion object {
        const val URL = "https://example.com/"
        const val LIMIT = 4096L
        const val HUGE = 1_000_000
    }
}

/** A body that is all there at once and counts how much of it the client took. */
@OptIn(InternalAPI::class)
private class MeteredChannel(private val length: Int) : ByteReadChannel {
    private val buffer = Buffer().apply { write(ByteArray(length)) }

    val consumed: Long get() = length - buffer.size
    val cancelled: Boolean get() = closedCause != null

    override var closedCause: Throwable? = null
        private set
    override val isClosedForRead: Boolean get() = cancelled || buffer.exhausted()
    override val readBuffer: Source get() = buffer

    override suspend fun awaitContent(min: Int): Boolean = !buffer.exhausted()

    override fun cancel(cause: Throwable?) {
        closedCause = cause ?: IOException("cancelled")
    }
}
