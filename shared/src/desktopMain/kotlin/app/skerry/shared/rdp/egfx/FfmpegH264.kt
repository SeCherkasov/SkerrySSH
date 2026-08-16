package app.skerry.shared.rdp.egfx

import app.skerry.shared.process.resolveExecutableOnPath
import app.skerry.shared.rdp.RdpImageBounds
import app.skerry.shared.rdp.RdpProtocolException
import java.io.IOException
import java.io.OutputStream
import java.lang.ProcessBuilder.Redirect

/**
 * H.264 on the desktop, through the `ffmpeg` the machine already has.
 *
 * The rest of this RDP stack decodes in Kotlin, and this codec is the one place where that stops
 * being reasonable: an H.264 decoder is tens of thousands of lines of arithmetic that a session
 * cannot afford to get subtly wrong, and the JVM ships none. A process is the cheapest correct
 * answer — nothing to link, nothing to bundle, and a parser fed by an untrusted server sits outside
 * this one's address space.
 *
 * No `ffmpeg` on PATH means [available] is false, the client never tells the server it can take
 * H.264, and the session runs on the codecs it runs on today. That is the whole failure mode.
 */
class FfmpegH264Decoders(
    private val trace: (String) -> Unit = h264Trace,
    /**
     * Ask ffmpeg for `-hwaccel auto` (F-29): the platform's VA-API/NVDEC/DXVA2 when one works,
     * ffmpeg's own silent fall-back to software when it does not. Off pins the decode to software
     * outright — the app-level Rendering setting drives it together with the render backend.
     */
    private val hardwareDecode: Boolean = true,
) : H264DecoderFactory {

    private val binary: String? by lazy {
        resolveExecutableOnPath(BINARY).also { found ->
            trace(if (found == null) "no $BINARY on PATH, H.264 stays off" else "H.264 through $found")
        }
    }

    override val available: Boolean get() = binary != null

    override val description: String
        get() = if (hardwareDecode) "$BINARY (hwaccel auto)" else "$BINARY (software)"

    override fun open(width: Int, height: Int): H264Decoder? {
        val binary = binary ?: return null
        return runCatching { FfmpegH264Decoder(binary, hardwareDecode, trace) }
            .onFailure { trace("$BINARY would not start: $it") }
            .getOrNull()
    }

    private companion object {
        const val BINARY = "ffmpeg"
    }
}

/** The size of the pictures a YUV4MPEG2 stream will carry. */
internal class Y4mPictureSize(val width: Int, val height: Int)

/**
 * Read `YUV4MPEG2 W1920 H1088 F30:1 Ip A1:1 C420mpeg2` — the one line that says how large the pictures
 * behind it are, and therefore how much is about to be allocated for them.
 *
 * The size is the stream's, not the surface's: it comes out of parameter sets a hostile server wrote,
 * so it goes through the same bound every other codec in this stack allocates behind. A per-side limit
 * is not one — 16384 by 16384 is inside it and costs 400 MB, and an `OutOfMemoryError` is an `Error`
 * that the session's own catch never sees.
 *
 * @throws IOException the line is not a 4:2:0 stream header this can read
 * @throws RdpProtocolException it is, and the size it declares is not one to allocate
 */
internal fun y4mPictureSize(header: String): Y4mPictureSize {
    val tags = header.split(' ')
    if (tags.firstOrNull() != "YUV4MPEG2") throw IOException("the decoder wrote '$header', not a stream header")
    // The pipeline asks for 4:2:0 and that is all this file unpacks. The sub-tags after it name where
    // the chroma sits — `C420jpeg` and `C420mpeg2` are both 4:2:0 and differ by a sub-pixel offset
    // nothing here depends on — so the tag is matched by its prefix.
    val colour = tags.firstOrNull { it.startsWith("C") } ?: "C420"
    if (!colour.startsWith("C420")) throw IOException("the decoder produced $colour, not 4:2:0")
    val width = tags.dimension("W")
    val height = tags.dimension("H")
    RdpImageBounds.requireSize(width, height, "an H.264 picture")
    return Y4mPictureSize(width, height)
}

/** The number after [tag]; how large it may be is [RdpImageBounds]' to say, not this. */
private fun List<String>.dimension(tag: String): Int =
    firstOrNull { it.startsWith(tag) }?.drop(1)?.toIntOrNull()
        ?: throw IOException("the decoder declared no $tag")

/**
 * One `ffmpeg` process decoding one surface's stream: access units into its stdin, pictures out of
 * its stdout as YUV4MPEG2, which frames every picture and states its size.
 *
 * Two details are load-bearing. An access unit delimiter is appended to every frame, because the
 * H.264 parser only closes a picture when it sees the start of the next one — without it every
 * picture arrives one message late, and the last update to a desktop that then goes still would never
 * be shown. And `-fflags nobuffer` is *not* passed, though it looks like exactly the right flag:
 * it discards what the demuxer consumed while probing, which silently loses the first pictures of
 * the stream.
 *
 * Called only from the session's read loop, one access unit at a time, which is what makes the
 * one-picture-per-call exchange below safe without a lock.
 */
private class FfmpegH264Decoder(
    binary: String,
    hardwareDecode: Boolean,
    private val trace: (String) -> Unit,
) : H264Decoder {

    private val process: Process = ProcessBuilder(ffmpegH264Command(binary, hardwareDecode))
        // Discarded rather than read: an unread stderr pipe fills up and stops the decoder dead.
        .redirectError(Redirect.DISCARD)
        .start()

    private val toDecoder: OutputStream = process.outputStream

    /**
     * Pictures come off the process on a thread of the reader's own (F-04): plain blocking reads
     * instead of the old available()+sleep(1) spin, with the stall deadline on the timed wait.
     */
    private val frames = Y4mFrameReader(
        process.inputStream,
        exitDescription = { if (process.isAlive) null else "the decoder exited with ${process.exitValue()}" },
        trace = trace,
    )

    private var stopped = false

    /** Why the decoder died on its own, if it did; the quiet `null` is only for an external close. */
    private var diedWith: IllegalStateException? = null

    override fun decode(accessUnit: ByteArray): YuvFrame? {
        // The contract says a dead decoder THROWS, every time: after an internal failure, a quiet
        // null would read as "no picture this update" and freeze the surface without a word.
        diedWith?.let { throw IllegalStateException("the H.264 decoder stopped earlier", it) }
        if (stopped) return null
        try {
            toDecoder.write(accessUnit)
            toDecoder.write(ACCESS_UNIT_DELIMITER)
            toDecoder.flush()
            return frames.awaitFrame()
        } catch (e: RdpProtocolException) {
            // The size the stream declares was refused before anything was allocated for it. That is
            // the server's doing, and its own reason is the one worth keeping.
            close()
            throw e
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw stopped(e)
        } catch (e: Exception) {
            // Everything, not only the IOException a broken pipe raises: whatever went wrong, the
            // process must not be left running with nobody to read it. Nothing here suspends, so no
            // cancellation passes through this catch.
            throw stopped(e)
        }
    }

    /**
     * The decoder is gone: give it back and say so. A picture this client could not read is one thing,
     * but every frame after this one would be lost too, and a desktop frozen without a word is worse
     * than a session that ends saying why.
     */
    private fun stopped(cause: Exception): IllegalStateException {
        close()
        return IllegalStateException("the H.264 decoder stopped: ${cause.message}", cause)
            .also { diedWith = it }
    }

    override fun close() {
        if (stopped) return
        stopped = true
        runCatching { toDecoder.close() }
        frames.close()
        process.destroy()
    }

    private companion object {
        /**
         * An access unit delimiter with primary_pic_type 0 — six bytes that say "the picture before
         * this is complete" and nothing else.
         */
        val ACCESS_UNIT_DELIMITER = byteArrayOf(0, 0, 0, 1, 9, 0x10)
    }
}

/** The ffmpeg invocation; top-level so the command shape is testable without a binary. */
internal fun ffmpegH264Command(binary: String, hardwareDecode: Boolean): List<String> = buildList {
    add(binary)
    addAll(listOf("-hide_banner", "-loglevel", "quiet", "-nostdin"))
    // Decode with no reordering delay, and never with frame threading: both would hold a
    // picture back, and the exchange above is one picture per access unit.
    addAll(listOf("-flags", "low_delay", "-thread_type", "slice"))
    // Start decoding on the first access unit instead of collecting seconds of stream to
    // guess a frame rate from. The rate is stated instead, and nothing here uses it.
    addAll(listOf("-probesize", "32", "-analyzeduration", "0", "-framerate", "30"))
    // An input option, so it must precede -i. `auto` falls back to software inside ffmpeg when
    // no acceleration works, which is exactly the containment F-29 asked for.
    if (hardwareDecode) addAll(listOf("-hwaccel", "auto"))
    addAll(listOf("-f", "h264", "-i", "pipe:0"))
    // Every picture the decoder produces, exactly once: the frame-rate machinery would
    // otherwise drop or duplicate pictures to fit the rate stated above.
    addAll(listOf("-fps_mode", "passthrough"))
    addAll(listOf("-pix_fmt", "yuv420p"))
    addAll(listOf("-f", "yuv4mpegpipe", "-strict", "-1"))
    // Without this the muxer holds pictures until its 32 KB buffer fills, which on a small
    // surface is several frames of latency.
    addAll(listOf("-flush_packets", "1"))
    add("pipe:1")
}
