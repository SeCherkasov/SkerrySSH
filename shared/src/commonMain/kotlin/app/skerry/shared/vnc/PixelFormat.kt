package app.skerry.shared.vnc

/**
 * The pixel format we force the server into via SetPixelFormat right after the handshake: 32 bits
 * per pixel, depth 24, big-endian, true-colour, 8 bits each for R/G/B at shifts 16/8/0. With this
 * fixed layout every encoding decoder converts a pixel the SAME trivial way — read 4 bytes
 * big-endian, keep the low 24 bits, force alpha opaque — instead of honouring an arbitrary
 * server-chosen format. This shrinks all five decoders (Raw/Hextile/ZRLE/Tight) and their tests.
 *
 * ZRLE and Tight use a "compressed pixel" (CPIXEL) of 3 bytes when all of R/G/B fit in one byte
 * each and one of the top/bottom bytes is unused — which is exactly our layout — so [CPIXEL_BYTES]
 * is 3 there while raw pixels are 4 bytes.
 */
object CanonicalPixelFormat {
    const val BITS_PER_PIXEL = 32
    const val DEPTH = 24
    const val BIG_ENDIAN = 1
    const val TRUE_COLOUR = 1
    const val RED_MAX = 255
    const val GREEN_MAX = 255
    const val BLUE_MAX = 255
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BLUE_SHIFT = 0

    /** Bytes per full pixel on the wire (Raw / Hextile). */
    const val PIXEL_BYTES = 4

    /** Bytes per compressed pixel (CPIXEL) in ZRLE/Tight for our layout: the unused 4th byte is dropped. */
    const val CPIXEL_BYTES = 3

    /** The 16-byte PIXEL_FORMAT structure sent in SetPixelFormat (and in ClientInit setup). */
    fun toBytes(): ByteArray = byteArrayOf(
        BITS_PER_PIXEL.toByte(),
        DEPTH.toByte(),
        BIG_ENDIAN.toByte(),
        TRUE_COLOUR.toByte(),
        (RED_MAX ushr 8).toByte(), RED_MAX.toByte(),
        (GREEN_MAX ushr 8).toByte(), GREEN_MAX.toByte(),
        (BLUE_MAX ushr 8).toByte(), BLUE_MAX.toByte(),
        RED_SHIFT.toByte(),
        GREEN_SHIFT.toByte(),
        BLUE_SHIFT.toByte(),
        0, 0, 0, // padding
    )

    /**
     * Convert a 4-byte big-endian pixel at [src]\[[offset]] to opaque ARGB. With our layout the
     * high byte is unused padding and the low three bytes are R,G,B — so the result is
     * `0xFF000000 | (RGB)`.
     */
    fun argbFromPixel(src: ByteArray, offset: Int): Int {
        val r = src[offset + 1].toInt() and 0xFF
        val g = src[offset + 2].toInt() and 0xFF
        val b = src[offset + 3].toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Convert a 3-byte CPIXEL at [src]\[[offset]] to opaque ARGB. RFB drops the least-significant
     * byte of the 4-byte big-endian pixel for our format, leaving R,G,B in order.
     */
    fun argbFromCPixel(src: ByteArray, offset: Int): Int {
        val r = src[offset].toInt() and 0xFF
        val g = src[offset + 1].toInt() and 0xFF
        val b = src[offset + 2].toInt() and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
