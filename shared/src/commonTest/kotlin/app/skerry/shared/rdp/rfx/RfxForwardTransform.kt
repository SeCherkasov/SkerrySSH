package app.skerry.shared.rdp.rfx

/**
 * The forward wavelet transform of RemoteFX, for tests only — a client never encodes.
 *
 * It exists so the decoder can be checked against something other than itself: a picture goes
 * through this and the decoder's inverse must give it back. The layout it produces is the one the
 * wire uses (MS-RDPRFX 3.1.8.1.6): HL1 at 0, then LH1, HH1, the level-2 bands, the level-3 bands and
 * the low-pass image at 4032.
 *
 * The transform is not lossless: each level stores its detail band halved, so a round trip can be
 * off by a unit or two per level. That is the codec's own trade, not an artefact of this helper.
 */
object RfxForwardTransform {

    fun transform(samples: IntArray): IntArray {
        require(samples.size == RfxDwt.TILE_COEFFICIENTS) { "a tile is ${RfxDwt.TILE_COEFFICIENTS} samples" }
        val buffer = samples.copyOf()
        val scratch = IntArray(RfxDwt.TILE_COEFFICIENTS)
        forwardLevel(buffer, base = 0, side = 32, scratch)
        forwardLevel(buffer, base = 3072, side = 16, scratch)
        forwardLevel(buffer, base = 3840, side = 8, scratch)
        return buffer
    }

    /** Columns first, then rows — the mirror of the decoder, which does rows and then columns. */
    private fun forwardLevel(buffer: IntArray, base: Int, side: Int, scratch: IntArray) {
        val full = side * 2

        for (x in 0 until full) {
            for (n in 0 until side) {
                val row = 2 * n
                val current = buffer[base + row * full + x]
                val next = buffer[base + (row + 1) * full + x]
                val after = if (n < side - 1) buffer[base + (row + 2) * full + x] else current
                val high = (next - ((current + after) shr 1)) shr 1
                scratch[(side + n) * full + x] = high
                val previousHigh = if (n == 0) high else scratch[(side + n - 1) * full + x]
                scratch[n * full + x] = current + if (n == 0) high else (previousHigh + high) shr 1
            }
        }

        val hl = base
        val lh = base + side * side
        val hh = base + 2 * side * side
        val ll = base + 3 * side * side
        for (y in 0 until side) {
            splitRow(scratch, y * full, buffer, hl + y * side, ll + y * side, side)
            splitRow(scratch, (side + y) * full, buffer, hh + y * side, lh + y * side, side)
        }
    }

    private fun splitRow(
        source: IntArray,
        sourceOffset: Int,
        out: IntArray,
        highOffset: Int,
        lowOffset: Int,
        side: Int,
    ) {
        for (n in 0 until side) {
            val column = 2 * n
            val current = source[sourceOffset + column]
            val next = source[sourceOffset + column + 1]
            val after = if (n < side - 1) source[sourceOffset + column + 2] else current
            val high = (next - ((current + after) shr 1)) shr 1
            out[highOffset + n] = high
            val previousHigh = if (n == 0) high else out[highOffset + n - 1]
            out[lowOffset + n] = current + if (n == 0) high else (previousHigh + high) shr 1
        }
    }
}
