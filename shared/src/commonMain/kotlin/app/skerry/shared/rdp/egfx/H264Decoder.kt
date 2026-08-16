package app.skerry.shared.rdp.egfx

/**
 * One decoded H.264 picture, planar 4:2:0 — the one format every decoder on either platform can be
 * asked for. [y] is full resolution, [u] and [v] are half in both directions, and each plane may be
 * padded to a stride wider than the picture (both a `MediaCodec` `Image` and a raw ffmpeg frame are).
 *
 * The picture is not the surface: the encoder pads to whole macroblocks, so a 1366×768 surface
 * arrives as a 1376×768 frame and the extra columns are not part of anything drawn.
 */
class YuvFrame(
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray,
    val width: Int,
    val height: Int,
    /** Bytes between two rows of the plane; the default is a picture with no padding at all. */
    val yStride: Int = width,
    val chromaStride: Int = (width + 1) / 2,
) {
    init {
        require(width > 0 && height > 0) { "an empty ${width}x$height picture" }
        require(yStride >= width && chromaStride >= (width + 1) / 2) { "strides narrower than the picture" }
        require(y.size >= yStride * height) { "the luma plane is shorter than its stride and height" }
        val chromaHeight = (height + 1) / 2
        require(u.size >= chromaStride * chromaHeight && v.size >= chromaStride * chromaHeight) {
            "a chroma plane is shorter than its stride and height"
        }
    }
}

/**
 * A platform H.264 decoder driving one stream: access units in, pictures out, in order.
 *
 * One instance decodes one surface's stream and nothing else, because the stream is stateful — a
 * frame is a difference from the pictures before it. AVC444 is still one stream: the server encodes
 * the luma and the chroma frame as consecutive frames of the same sequence, which is why both halves
 * of a 4:4:4 message go through the same decoder, in the order the message carries them.
 */
interface H264Decoder {
    /**
     * Decode one access unit.
     *
     * `null` when it produced no picture, which costs that update and nothing else. Throws when the
     * decoder is gone for good: every picture after that one would be lost too, and a screen frozen
     * without a word is worse than a session that ends saying why.
     *
     * The returned planes are only valid until the next call — a decoder may hand the same buffers
     * over again — so a caller that needs the picture later copies it out.
     */
    fun decode(accessUnit: ByteArray): YuvFrame?

    /** Give the decoder back. Called once, when the surface goes away or the session ends. */
    fun close()
}

/**
 * The platform's H.264 decoders, or the honest answer that it has none.
 *
 * [available] is asked before a single surface exists: it decides whether the client tells the server
 * it can take H.264 at all (see [GraphicsChannel]), and a machine that answers no keeps the session
 * it has today on the progressive and RemoteFX codecs rather than a frozen screen.
 */
interface H264DecoderFactory {
    /** Whether this machine can decode H.264. Asked once per session, so it may probe. */
    val available: Boolean

    /** Human-readable name of the decoder for the diagnostics overlay ("ffmpeg (hwaccel auto)"). */
    val description: String get() = "H.264"

    /**
     * A decoder for one surface's stream, sized to what the picture will be. `null` when the
     * platform would not open one after all — the surface then draws nothing on the H.264 path.
     */
    fun open(width: Int, height: Int): H264Decoder?
}
