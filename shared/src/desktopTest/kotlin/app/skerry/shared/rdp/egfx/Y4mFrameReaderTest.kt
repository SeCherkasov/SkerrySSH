package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The blocking reader that replaced the available()+sleep(1) spin (F-04), driven over in-memory
 * streams — no ffmpeg. What must hold: pictures come out complete and correctly sized, a frozen
 * stream fails by the deadline instead of hanging the caller, and a closed stream fails promptly
 * with the process's own story.
 */
class Y4mFrameReaderTest {

    private val readers = mutableListOf<Y4mFrameReader>()

    @AfterTest
    fun closeReaders() {
        readers.forEach { it.close() }
    }

    private fun reader(
        input: java.io.InputStream,
        exit: () -> String? = { null },
        timeoutNanos: Long = 2_000_000_000L,
    ): Y4mFrameReader = Y4mFrameReader(input, exit, trace = {}, pictureTimeoutNanos = timeoutNanos)
        .also { readers += it }

    /** A 4x2 4:2:0 stream: header + [frames] pictures whose luma bytes count up from [seed]. */
    private fun stream(frames: Int, seed: Int = 0): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("YUV4MPEG2 W4 H2 F30:1 Ip A1:1 C420mpeg2\n".encodeToByteArray())
        repeat(frames) { frame ->
            out.write("FRAME\n".encodeToByteArray())
            out.write(ByteArray(8) { (seed + frame * 100 + it).toByte() })  // luma 4x2
            out.write(ByteArray(2) { (10 + frame).toByte() })               // chroma U 2x1
            out.write(ByteArray(2) { (20 + frame).toByte() })               // chroma V 2x1
        }
        return out.toByteArray()
    }

    @Test
    fun `a picture comes out complete, sized by the stream header`() {
        val frame = reader(ByteArrayInputStream(stream(frames = 1))).awaitFrame()

        assertEquals(4, frame.width)
        assertEquals(2, frame.height)
        assertEquals(2, frame.chromaStride)
        assertContentEquals(ByteArray(8) { it.toByte() }, frame.y.copyOf(8))
        assertContentEquals(byteArrayOf(10, 10), frame.u.copyOf(2))
        assertContentEquals(byteArrayOf(20, 20), frame.v.copyOf(2))
    }

    @Test
    fun `the second picture is the second picture, not a stale buffer`() {
        val r = reader(ByteArrayInputStream(stream(frames = 2)))
        r.awaitFrame()

        val second = r.awaitFrame()

        assertContentEquals(ByteArray(8) { (100 + it).toByte() }, second.y.copyOf(8))
        assertContentEquals(byteArrayOf(11, 11), second.u.copyOf(2))
    }

    @Test
    fun `a frozen stream fails by the deadline instead of hanging the session`() {
        // A pipe nobody writes: the old spin would have burned a core here; the reader must give
        // up within the timeout, and the caller's thread must never hang.
        val pipe = PipedInputStream(PipedOutputStream())
        val started = TimeSource.Monotonic.markNow()

        val failure = assertFailsWith<IOException> {
            reader(pipe, timeoutNanos = 300_000_000L).awaitFrame()
        }

        assertTrue("no picture in time" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue(started.elapsedNow().inWholeMilliseconds < 2_000, "the deadline did not bound the wait")
    }

    @Test
    fun `a stream that ends mid-picture reports the decoder's exit, promptly`() {
        val truncated = stream(frames = 1).copyOf(44) // the 40-byte header + "FRAM"
        val started = TimeSource.Monotonic.markNow()

        val failure = assertFailsWith<IOException> {
            reader(ByteArrayInputStream(truncated), exit = { "exited with 1" }, timeoutNanos = 5_000_000_000L)
                .awaitFrame()
        }

        assertTrue("exited with 1" in failure.message.orEmpty(), failure.message.orEmpty())
        // Prompt: EOF surfaces on the reader thread's blocking read, not at the 5 s deadline.
        assertTrue(started.elapsedNow().inWholeMilliseconds < 2_000, "EOF waited for the deadline")
    }

    @Test
    fun `closing the reader wakes a parked consumer instead of sitting out the deadline`() {
        // Session teardown happens on another thread while the read loop is parked in awaitFrame;
        // it must unpark within a poll slice, not after the (here deliberately long) deadline.
        val pipe = PipedInputStream(PipedOutputStream())
        val r = reader(pipe, timeoutNanos = 60_000_000_000L)
        val outcome = java.util.concurrent.CompletableFuture<Throwable?>()
        val consumer = kotlin.concurrent.thread {
            outcome.complete(runCatching { r.awaitFrame() }.exceptionOrNull())
        }

        Thread.sleep(150) // let the consumer park
        r.close()

        val thrown = outcome.get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(thrown is IOException, "expected the closed-reader IOException, got $thrown")
        consumer.join(1_000)
    }

    @Test
    fun `a hostile picture size is refused before the planes are allocated`() {
        val hostile = "YUV4MPEG2 W16384 H16384 F30:1 Ip A1:1 C420mpeg2\n".encodeToByteArray()

        assertFailsWith<RdpProtocolException> {
            reader(ByteArrayInputStream(hostile)).awaitFrame()
        }
    }
}
