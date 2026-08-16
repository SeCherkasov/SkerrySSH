package app.skerry.shared.rdp.egfx

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import app.skerry.shared.rdp.RdpImageBounds
import app.skerry.shared.rdp.RdpProtocolException
import java.nio.ByteBuffer

/**
 * H.264 on Android, through `MediaCodec` — the platform decoder, hardware where the device has one.
 *
 * A device without any AVC decoder is possible (a stripped build, or every instance of the codec
 * already in use), and then [available] is false, the client never tells the server it can take
 * H.264, and the session runs on the codecs it runs on today.
 */
class MediaCodecH264Decoders(private val trace: (String) -> Unit = h264Trace) : H264DecoderFactory {

    override val description: String get() = "MediaCodec"

    override val available: Boolean by lazy {
        val probe = MediaFormat.createVideoFormat(MIME, PROBE_SIZE, PROBE_SIZE)
        val decoder = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe)
        }.getOrNull()
        trace(if (decoder == null) "no $MIME decoder on this device, H.264 stays off" else "H.264 through $decoder")
        decoder != null
    }

    override fun open(width: Int, height: Int): H264Decoder? =
        runCatching { MediaCodecH264Decoder(width, height, trace) }
            .onFailure { trace("no decoder started for ${width}x$height: $it") }
            .getOrNull()

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /** Only to ask the platform whether it has the codec at all; the size does not matter. */
        const val PROBE_SIZE = 640
    }
}

/**
 * One `MediaCodec` decoding one surface's stream: access units in, pictures out, one for one.
 *
 * The size it is configured with is the surface's, which is a hint — the stream carries its own
 * parameter sets and the decoder reports what it actually produced. Low latency is asked for where
 * the platform has the knob: a decoder that reorders or queues pictures would show a desktop that
 * lags by an update and freezes when it goes still.
 *
 * Called only from the session's read loop, one access unit at a time.
 */
private class MediaCodecH264Decoder(
    width: Int,
    height: Int,
    private val trace: (String) -> Unit,
) : H264Decoder {

    private val codec: MediaCodec = start(width, height)

    private val frames = AndroidYuvFrames()
    private val bufferInfo = MediaCodec.BufferInfo()
    private var closed = false

    override fun decode(accessUnit: ByteArray): YuvFrame? {
        if (closed) return null
        return try {
            submit(accessUnit)
            picture()
        } catch (e: RdpProtocolException) {
            // The size the stream declares was refused before anything was allocated for it. That is
            // the server's doing, and its own reason is the one worth keeping.
            close()
            throw e
        } catch (e: Exception) {
            // Everything, not only the IllegalStateException a codec in an error state throws: an
            // access unit larger than the input buffer overflows it too. Either would otherwise leave
            // one of the device's few hardware decoders held by an object nobody uses again. Nothing
            // here suspends, so no cancellation passes through this catch.
            close()
            // A codec in an error state stays in it: every picture after this one would be lost, and a
            // desktop frozen without a word is worse than a session that ends saying why.
            throw IllegalStateException("the H.264 decoder stopped: ${e.message}", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun submit(accessUnit: ByteArray) {
        val index = codec.dequeueInputBuffer(SUBMIT_TIMEOUT_MICROS)
        if (index < 0) error("no input buffer in ${SUBMIT_TIMEOUT_MICROS / 1000} ms")
        val buffer = codec.getInputBuffer(index) ?: error("an input buffer that is not there")
        buffer.clear()
        buffer.put(accessUnit)
        codec.queueInputBuffer(index, 0, accessUnit.size, 0, 0)
    }

    /**
     * The picture for the access unit just submitted, or `null` when the decoder has not produced one
     * — which the caller treats as one lost update rather than a failure.
     */
    private fun picture(): YuvFrame? {
        while (true) {
            val index = codec.dequeueOutputBuffer(bufferInfo, PICTURE_TIMEOUT_MICROS)
            when {
                index >= 0 -> return picture(index)
                // The format is announced before the first picture and whenever the stream changes it;
                // the picture that follows carries the same information, so there is nothing to keep.
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return null
            }
        }
    }

    /** The picture in output buffer [index]; the buffer goes back to the decoder either way. */
    private fun picture(index: Int): YuvFrame? {
        try {
            val image = codec.getOutputImage(index) ?: return null
            // The size is the stream's, not the surface's: it comes out of parameter sets a hostile
            // server wrote, and the planes are allocated from it. Same bound every other codec in this
            // stack allocates behind.
            RdpImageBounds.requireSize(image.cropRect.width(), image.cropRect.height(), "an H.264 picture")
            val frame = frames.frame(image)
            if (frame == null) trace("the decoder produced a picture in a layout this client cannot read")
            return frame
        } finally {
            codec.releaseOutputBuffer(index, false)
        }
    }

    private companion object {
        const val SUBMIT_TIMEOUT_MICROS = 100_000L
        const val PICTURE_TIMEOUT_MICROS = 500_000L

        /**
         * A configured, started decoder. Built here rather than in a property initialiser so that a
         * codec which will not take this format is given back: a throw out of an initialiser leaves no
         * object, and with it no reference through which the instance could ever be released.
         */
        fun start(width: Int, height: Int): MediaCodec {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                // No output Surface: the pixels have to come back here to be turned into the desktop's,
                // since the region list decides which parts of them are painted at all.
                codec.configure(format, null, null, 0)
                codec.start()
                return codec
            } catch (e: Exception) {
                runCatching { codec.release() }
                throw e
            }
        }
    }
}
