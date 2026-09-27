package app.skerry.shared.io

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlin.test.Test
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cap on a body read from an untrusted endpoint. `bodyAsChannel()` hands over a channel that
 * Ktor's own writer is still filling, so the cap must hold however that writer's flushes interleave
 * with the reads.
 */
class ReadAtMostTest {

    @Test
    fun `a body within the limit is returned whole`() = runTest {
        val body = "{\"error\":\"blocked\"}".encodeToByteArray()
        assertContentEquals(body, ScriptedChannel(listOf(body)).readAtMost(body.size))
    }

    @Test
    fun `a body over the limit is refused`() = runTest {
        assertNull(ScriptedChannel(listOf(ByteArray(LIMIT + 1))).readAtMost(LIMIT))
    }

    @Test
    fun `an empty body is an empty result`() = runTest {
        assertContentEquals(ByteArray(0), ScriptedChannel(emptyList()).readAtMost(LIMIT))
    }

    /**
     * A chunked response arrives in several flushes; each one lands after the bytes before it. At
     * 60 000 bytes the buffer also grows twice past its first chunk.
     */
    @Test
    fun `a body arriving in several flushes is joined in order`() = runTest {
        val chunks = List(3) { n -> ByteArray(20_000) { (n + 1).toByte() } }
        val joined = chunks.reduce(ByteArray::plus)
        assertContentEquals(joined, ScriptedChannel(chunks).readAtMost(joined.size))
        assertNull(ScriptedChannel(chunks).readAtMost(joined.size - 1))
    }

    /**
     * Issue #383: Ktor's `readRemaining(max)` looks at the buffer three times per step, so a flush
     * landing after the first look (empty) is taken whole, past the cap. The larger limit makes the
     * buffer grow while the flush is being read, as it does for a 1 MiB model catalog.
     */
    @Test
    fun `a flush landing mid-read is not taken past the limit`() = runTest {
        for (limit in listOf(LIMIT, 100_000)) {
            val channel = ScriptedChannel(listOf(ByteArray(limit * 3)))
            channel.readAtMost(limit)
            assertTrue(channel.consumed <= limit + 1, "took ${channel.consumed} bytes for a limit of $limit")
        }
    }

    /** Ktor's `readAvailable` answers a cancelled channel with -1, exactly as it answers the end. */
    @Test
    fun `a body cut off mid-read fails instead of reading as whole`() = runTest {
        val channel = ByteChannel()
        channel.writeFully(ByteArray(10))
        channel.flush()
        channel.cancel(IOException("connection reset"))
        assertFails { channel.readAtMost(LIMIT) }
    }

    private companion object {
        const val LIMIT = 8 * 1024
    }
}

/**
 * A channel whose writer flushes the next chunk whenever the reader finds the buffer empty — except
 * on the reader's very first look, which always finds nothing yet.
 */
@OptIn(InternalAPI::class)
private class ScriptedChannel(chunks: List<ByteArray>) : ByteReadChannel {
    private val pending = ArrayDeque(chunks)
    private val buffer = Buffer()
    private var looks = 0
    private var delivered = 0L

    val consumed: Long get() = delivered - buffer.size

    private fun flushNext() {
        val chunk = pending.removeFirstOrNull() ?: return
        buffer.write(chunk)
        delivered += chunk.size
    }

    override val closedCause: Throwable? = null
    override val isClosedForRead: Boolean get() = pending.isEmpty() && buffer.exhausted()
    override val readBuffer: Source
        get() {
            if (looks++ > 0 && buffer.exhausted()) flushNext()
            return buffer
        }

    override suspend fun awaitContent(min: Int): Boolean {
        if (buffer.exhausted()) flushNext()
        return !buffer.exhausted()
    }

    override fun cancel(cause: Throwable?) = Unit
}
