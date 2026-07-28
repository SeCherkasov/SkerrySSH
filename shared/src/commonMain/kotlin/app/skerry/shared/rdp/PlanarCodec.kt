package app.skerry.shared.rdp

/**
 * The RDP 6.0 planar codec (MS-RDPEGDI 2.2.2.5.1) — how a 32-bit session's bitmaps arrive, where
 * interleaved RLE does not apply.
 *
 * The image is split into colour planes and each is compressed on its own, which is what makes it
 * work: neighbouring pixels of one channel are far more alike than whole pixels are. Planes are
 * usually delta-coded down each column as well, so a gradient costs almost nothing.
 */
object PlanarCodec {
    private const val FLAG_RLE = 0x10
    private const val FLAG_NO_ALPHA = 0x20
    private const val FLAG_CS = 0x08

    /**
     * Decode [data] into [width]×[height] ARGB pixels.
     *
     * @throws RdpProtocolException the stream is malformed, or uses chroma subsampling — which this
     * client never advertises, and guessing at it would paint wrong colours rather than fail
     */
    fun decode(data: ByteArray, width: Int, height: Int): IntArray {
        RdpImageBounds.requireSize(width, height, "a planar bitmap")
        val reader = RdpReader(data)
        val header = reader.u8()
        if (header and FLAG_CS != 0) {
            throw RdpProtocolException("planar bitmap uses chroma subsampling, which was not negotiated")
        }
        val rle = header and FLAG_RLE != 0
        val hasAlpha = header and FLAG_NO_ALPHA == 0
        val planeSize = width * height

        // Planes arrive alpha-first when present, then red, green, blue.
        if (hasAlpha) readPlane(reader, width, height, rle)
        val red = readPlane(reader, width, height, rle)
        val green = readPlane(reader, width, height, rle)
        val blue = readPlane(reader, width, height, rle)

        val out = IntArray(planeSize)
        for (i in 0 until planeSize) {
            out[i] = OPAQUE or
                ((red[i].toInt() and 0xFF) shl 16) or
                ((green[i].toInt() and 0xFF) shl 8) or
                (blue[i].toInt() and 0xFF)
        }
        return out
    }

    /** One colour plane, raw or run-length coded, with the vertical delta coding undone. */
    private fun readPlane(reader: RdpReader, width: Int, height: Int, rle: Boolean): ByteArray {
        val plane = if (rle) decodeRlePlane(reader, width, height) else reader.bytes(width * height)
        if (rle) undoVerticalDelta(plane, width, height)
        return plane
    }

    /**
     * Run-length coding within a plane, one scanline at a time — a run never crosses a row, which
     * is what lets the delta coding below work per column.
     *
     * A control byte packs the count of literal bytes that follow (high nibble) and the run length
     * after them (low nibble). Two low-nibble values are escapes rather than lengths: 1 means
     * "16 + the high nibble, no literals" and 2 means "32 + the high nibble", which is how a flat
     * row of any width still costs one or two bytes.
     */
    private fun decodeRlePlane(reader: RdpReader, width: Int, height: Int): ByteArray {
        val plane = ByteArray(width * height)
        for (row in 0 until height) {
            var written = 0
            val rowStart = row * width
            while (written < width) {
                if (reader.remaining == 0) return plane
                val control = reader.u8()
                var literals = (control shr 4) and 0x0F
                var runLength = control and 0x0F
                when (runLength) {
                    1 -> {
                        runLength = 16 + literals
                        literals = 0
                    }

                    2 -> {
                        runLength = 32 + literals
                        literals = 0
                    }
                }

                repeat(minOf(literals, width - written)) {
                    if (reader.remaining == 0) return plane
                    plane[rowStart + written] = reader.u8().toByte()
                    written++
                }
                if (runLength == 0 || written >= width) continue
                // A run repeats the byte just written; at the head of a row with nothing before it
                // the repeated value is zero, which is what the encoder assumed.
                val repeated = if (written > 0) plane[rowStart + written - 1] else 0
                repeat(minOf(runLength, width - written)) {
                    plane[rowStart + written] = repeated
                    written++
                }
            }
        }
        return plane
    }

    /**
     * Undo the per-column delta coding: every row but the first holds the difference from the row
     * above, in sign-magnitude form (the low bit is the sign). Without this a photo decodes as a
     * plausible-looking image whose rows drift steadily off.
     */
    private fun undoVerticalDelta(plane: ByteArray, width: Int, height: Int) {
        for (row in 1 until height) {
            val previous = (row - 1) * width
            val current = row * width
            for (column in 0 until width) {
                val encoded = plane[current + column].toInt() and 0xFF
                val delta = if (encoded and 1 != 0) -((encoded + 1) shr 1) else encoded shr 1
                plane[current + column] = ((plane[previous + column].toInt() and 0xFF) + delta).toByte()
            }
        }
    }

    private const val OPAQUE = 0xFF shl 24
}
