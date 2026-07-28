package app.skerry.shared.rdp

/**
 * NSCodec (MS-RDPNSC) — the codec Windows falls back to inside ClearCodec for the parts of a
 * rectangle that run-length encoding cannot compress: photographs, gradients, anti-aliased edges.
 *
 * It is a colour-plane codec. The image is split into luminance and two chroma channels, each
 * channel is run-length encoded on its own, and the chroma channels are then thrown away by halves
 * — first in precision (the low bits go, at a level the server picks) and often in resolution too
 * (one chroma sample per 2x2 block). The eye notices none of it and the planes compress far better
 * than interleaved pixels would.
 *
 * Two details are easy to get subtly wrong and are pinned by the specification's own reference
 * bitmap in [NsCodecTest]: a chroma byte is shifted back by the colour loss level *minus one* —
 * the missing shift is the halving that squeezed a 9-bit chroma into a byte — and the result is
 * truncated to eight bits before it is read as signed, so a shift that overflows wraps rather than
 * saturating. Reading the byte as signed first would produce plausible, wrong colours.
 */
object NsCodec {

    /** Decode a [width]x[height] image of opaque ARGB pixels from a bitmap stream. */
    fun decode(reader: RdpReader, width: Int, height: Int): IntArray {
        val lumaByteCount = reader.u32le()
        val orangeByteCount = reader.u32le()
        val greenByteCount = reader.u32le()
        val alphaByteCount = reader.u32le()
        val colorLossLevel = reader.u8()
        val subsampled = reader.u8() != 0
        reader.u16le() // reserved
        if (colorLossLevel < MIN_COLOR_LOSS_LEVEL || colorLossLevel > MAX_COLOR_LOSS_LEVEL) {
            throw RdpProtocolException("an NSCodec colour loss level of $colorLossLevel")
        }
        if (lumaByteCount < 0 || orangeByteCount < 0 || greenByteCount < 0 || alphaByteCount < 0) {
            throw RdpProtocolException("an NSCodec plane of negative length")
        }

        // Subsampling pads the luma plane to a multiple of eight columns so that the chroma planes,
        // which it halves, still tile evenly.
        val lumaWidth = if (subsampled) roundUp(width, 8) else width
        val chromaWidth = if (subsampled) roundUp(width, 8) / 2 else width
        val chromaHeight = if (subsampled) roundUp(height, 2) / 2 else height

        val luma = plane(reader.bytes(lumaByteCount), lumaWidth * height)
        val orange = plane(reader.bytes(orangeByteCount), chromaWidth * chromaHeight)
        val green = plane(reader.bytes(greenByteCount), chromaWidth * chromaHeight)
        // The alpha plane is not read: every surface this codec draws into is opaque, and the plane
        // is located by its byte count rather than by position, so skipping it costs nothing.

        val shift = colorLossLevel - 1
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val lumaRow = y * lumaWidth
            val chromaRow = (if (subsampled) y / 2 else y) * chromaWidth
            for (x in 0 until width) {
                val luminance = luma[lumaRow + x].toInt() and 0xFF
                val chroma = chromaRow + (if (subsampled) x / 2 else x)
                val orangeChroma = recover(orange[chroma], shift)
                val greenChroma = recover(green[chroma], shift)
                val red = clamp(luminance + orangeChroma - greenChroma)
                val greenValue = clamp(luminance + greenChroma)
                val blue = clamp(luminance - orangeChroma - greenChroma)
                out[y * width + x] = OPAQUE or (red shl 16) or (greenValue shl 8) or blue
            }
        }
        return out
    }

    /**
     * One colour plane, raw when it is already the size it should be and run-length decoded when it
     * is shorter. There is no flag for which it is: the byte count is the flag.
     */
    private fun plane(data: ByteArray, expected: Int): ByteArray {
        if (data.size == expected) return data
        if (data.size > expected || data.size < TAIL) {
            throw RdpProtocolException("an NSCodec plane of ${data.size} bytes against $expected expected")
        }
        val out = ByteArray(expected)
        val reader = RdpReader(data)
        var written = 0
        // The encoder always leaves the last four bytes of a plane raw, so a value that close to the
        // end is a literal whatever follows it.
        while (expected - written > TAIL) {
            val value = reader.u8()
            if (expected - written == TAIL + 1 || reader.remaining == 0 || reader.peekU8() != value) {
                out[written++] = value.toByte()
                continue
            }
            reader.u8() // the repeat of the value, which is what marks this a run
            val factor = reader.u8()
            val runLength = if (factor == ESCAPE) reader.u32le() else factor + MIN_RUN_LENGTH
            if (runLength < 0 || written + runLength > expected - TAIL) {
                throw RdpProtocolException("an NSCodec run of $runLength bytes past the plane")
            }
            out.fill(value.toByte(), written, written + runLength)
            written += runLength
        }
        reader.bytes(TAIL).copyInto(out, written)
        return out
    }

    /**
     * A chroma byte back to the signed value the inverse transform wants. See the class comment:
     * the shift is one less than the colour loss level, and it wraps within a byte.
     */
    private fun recover(stored: Byte, shift: Int): Int =
        ((((stored.toInt() and 0xFF) shl shift) and 0xFF).toByte()).toInt()

    private fun clamp(value: Int): Int = value.coerceIn(0, 0xFF)

    private fun roundUp(value: Int, multiple: Int): Int = (value + multiple - 1) / multiple * multiple

    /** The four raw bytes every plane ends with, encoded or not. */
    private const val TAIL = 4

    /** A run of one byte would cost more than the byte, so the shortest run the format has is two. */
    private const val MIN_RUN_LENGTH = 2
    private const val ESCAPE = 0xFF

    private const val MIN_COLOR_LOSS_LEVEL = 1
    private const val MAX_COLOR_LOSS_LEVEL = 7

    private const val OPAQUE = 0xFF shl 24
}
