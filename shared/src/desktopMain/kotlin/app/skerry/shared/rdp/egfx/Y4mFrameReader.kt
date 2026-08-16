package app.skerry.shared.rdp.egfx

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * Reads YUV4MPEG2 pictures off [input] on a thread of its own, with plain blocking reads, and hands
 * each completed picture over a rendezvous. This replaces the `available()` + `Thread.sleep(1)`
 * spin (F-04): the sleep added up to a millisecond per step to every frame and burned a core doing
 * nothing, while a blocking read costs neither — the deadline the spin existed for moves to the
 * consumer's timed wait in [awaitFrame].
 *
 * Two plane sets rotate. The rendezvous is what makes that safe: the reader's hand-over of picture
 * N+1 cannot complete until the consumer comes back for it, and by then the consumer is done with
 * picture N — so the set being filled is never the set in the caller's hands, which is the
 * [H264Decoder] "valid until the next call" contract.
 *
 * Failure is prompt, not deadline-bound: EOF or a process exit surfaces on the reader thread's
 * blocking read immediately and is handed over the same rendezvous.
 */
internal class Y4mFrameReader(
    input: InputStream,
    /** The process's exit story, if it has one ("exited with 1"); null while it is alive. */
    private val exitDescription: () -> String?,
    private val trace: (String) -> Unit,
    private val pictureTimeoutNanos: Long = PICTURE_TIMEOUT_NANOS,
) : AutoCloseable {

    /** DataInputStream for its readFully: "read N bytes or throw EOF", blocking, no spin. */
    private val input = DataInputStream(input)

    /** Completed pictures or the failure that ended the stream; rendezvous — see the class note. */
    private val handover = SynchronousQueue<Any>()

    @Volatile
    private var closed = false

    private val thread = Thread(::readLoop, "skerry-h264-reader").apply {
        isDaemon = true
        start()
    }

    /**
     * The next picture, or throws what stopped the stream — timeout, EOF, a refused size, or the
     * reader being closed under it. Polled in slices so a teardown from another thread unparks
     * this within one slice instead of sitting out the whole deadline.
     */
    fun awaitFrame(): YuvFrame {
        val deadline = System.nanoTime() + pictureTimeoutNanos
        while (true) {
            if (closed) throw IOException("the decoder was closed")
            val slice = minOf(POLL_SLICE_NANOS, deadline - System.nanoTime())
            if (slice <= 0) throw IOException(exitDescription() ?: "the decoder produced no picture in time")
            val item = handover.poll(slice, TimeUnit.NANOSECONDS) ?: continue
            return when (item) {
                is YuvFrame -> item
                is Throwable -> throw item
                else -> throw IOException("unexpected hand-over $item")
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { input.close() } // unblocks the reader's readFully
        thread.interrupt()
        // A consumer parked in awaitFrame would otherwise sit out the full deadline after a
        // teardown; with a SynchronousQueue, offer succeeds exactly when someone is waiting.
        handover.offer(IOException("the decoder was closed"))
    }

    private fun readLoop() {
        try {
            val header = readLine()
            val size = y4mPictureSize(header)
            trace("the decoder produces ${size.width}x${size.height}, header '$header'")
            val planes = Array(2) { PlaneSet(size.width, size.height) }
            var next = 0
            while (!closed) {
                handover.put(readFrame(planes[next]))
                next = (next + 1) % planes.size
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt() // close() interrupting a parked put/read — just leave
        } catch (failure: Exception) {
            if (closed) return // the session tore the stream down itself; nobody is waiting
            // The consumer is (or will be) parked in awaitFrame: hand the reason over the same
            // rendezvous so it surfaces on the next call, not at the deadline. A process that
            // exited tells its own story in preference to the raw read error.
            val reported = exitDescription()?.let { IOException(it, failure) } ?: failure
            runCatching { handover.put(reported) }
        }
    }

    private fun readFrame(planes: PlaneSet): YuvFrame {
        val marker = readLine()
        if (!marker.startsWith("FRAME")) throw IOException("the decoder wrote '$marker' where a picture starts")
        input.readFully(planes.luma)
        input.readFully(planes.chromaU)
        input.readFully(planes.chromaV)
        return planes.frame
    }

    private fun readLine(): String {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("the decoder closed its output")
            if (byte == '\n'.code) return line.toString()
            if (line.length >= MAX_LINE) throw IOException("a header line past $MAX_LINE bytes")
            line.append(byte.toChar())
        }
    }

    /** One picture's storage, allocated once per slot; a couple of MB per frame otherwise. */
    private class PlaneSet(width: Int, height: Int) {
        val luma = ByteArray(width * height)
        private val chromaSize = ((width + 1) / 2) * ((height + 1) / 2)
        val chromaU = ByteArray(chromaSize)
        val chromaV = ByteArray(chromaSize)
        val frame = YuvFrame(luma, chromaU, chromaV, width, height, chromaStride = (width + 1) / 2)
    }

    internal companion object {
        const val MAX_LINE = 256
        const val PICTURE_TIMEOUT_NANOS = 5_000_000_000L

        /** Teardown latency bound for a parked [awaitFrame]; pure park time, no busy work. */
        const val POLL_SLICE_NANOS = 100_000_000L
    }
}
