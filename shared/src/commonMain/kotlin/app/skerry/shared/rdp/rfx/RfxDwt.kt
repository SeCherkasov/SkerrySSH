package app.skerry.shared.rdp.rfx

/**
 * The inverse discrete wavelet transform of RemoteFX (MS-RDPRFX 3.1.8.1.5) with the dequantization
 * and differential decoding that precede it.
 *
 * A 64×64 tile arrives as 4096 coefficients laid out finest detail first (MS-RDPRFX 3.1.8.1.6):
 * HL1, LH1 and HH1 (32×32 each), then the level-2 bands (16×16), then the level-3 bands (8×8) and
 * finally the 8×8 low-pass image. Reconstruction runs the levels backwards — level 3 turns the 8×8
 * image into 16×16, level 2 into 32×32, level 1 into the tile — and each level writes over the
 * bands it consumed, which is why the whole transform runs inside the one buffer.
 *
 * The filter is the 5/3 pair in the codec's own scaling: the detail bands are stored halved, so the
 * synthesis doubles them. Samples are 11.5 fixed-point by the time they leave here.
 */
object RfxDwt {

    /** Tile side in pixels; fixed by the codec. */
    const val TILE_SIZE = 64

    /** Coefficients in a tile: the sub-bands of all three levels add up to the tile's pixels. */
    const val TILE_COEFFICIENTS = TILE_SIZE * TILE_SIZE

    /** Where the level-3 low-pass image sits, and how big it is. */
    const val LOW_PASS_OFFSET = 4032
    const val LOW_PASS_SIZE = 64

    /**
     * The ten quantization factors in the order TS_RFX_CODEC_QUANT packs them (MS-RDPRFX 2.2.2.1.5),
     * each paired with the band it scales: offset into the coefficient buffer and coefficient count.
     */
    private val QUANT_BANDS = arrayOf(
        LOW_PASS_OFFSET to LOW_PASS_SIZE, // LL3
        3904 to 64, // LH3
        3840 to 64, // HL3
        3968 to 64, // HH3
        3328 to 256, // LH2
        3072 to 256, // HL2
        3584 to 256, // HH2
        1024 to 1024, // LH1
        0 to 1024, // HL1
        2048 to 1024, // HH1
    )

    /** Where each level's four bands start, and the side of one band there. */
    private val LEVELS = listOf(3840 to 8, 3072 to 16, 0 to 32)

    /**
     * The low-pass band is coded as differences between neighbouring coefficients (MS-RDPRFX
     * 3.1.8.1.8) — it is the one band smooth enough for that to pay — so it is summed back before
     * anything else touches it.
     */
    fun differentialDecodeLowPass(coefficients: IntArray) {
        require(coefficients.size >= TILE_COEFFICIENTS) { "a tile is $TILE_COEFFICIENTS coefficients" }
        for (index in LOW_PASS_OFFSET + 1 until LOW_PASS_OFFSET + LOW_PASS_SIZE) {
            coefficients[index] += coefficients[index - 1]
        }
    }

    /**
     * Undo quantization in place. [quants] holds ten 4-bit factors packed two per byte, the low
     * nibble first; each is a shift left of one less than its value, so 1 means "untouched".
     */
    fun dequantize(coefficients: IntArray, quants: ByteArray) {
        require(quants.size >= QUANT_SET_SIZE) { "a quantization set is $QUANT_SET_SIZE bytes" }
        require(coefficients.size >= TILE_COEFFICIENTS) { "a tile is $TILE_COEFFICIENTS coefficients" }
        QUANT_BANDS.forEachIndexed { index, (offset, size) ->
            val shift = quantAt(quants, index) - 1
            if (shift <= 0) return@forEachIndexed
            for (i in offset until offset + size) coefficients[i] = coefficients[i] shl shift
        }
    }

    private fun quantAt(quants: ByteArray, index: Int): Int {
        val byte = quants[index / 2].toInt() and 0xFF
        return if (index % 2 == 0) byte and 0x0F else (byte shr 4) and 0x0F
    }

    /**
     * Reconstruct the tile from its [coefficients] (already dequantized), in place: on return the
     * buffer holds 64×64 samples in row order.
     */
    fun inverseTransform(coefficients: IntArray) {
        require(coefficients.size >= TILE_COEFFICIENTS) { "a tile is $TILE_COEFFICIENTS coefficients" }
        val scratch = IntArray(TILE_COEFFICIENTS)
        for ((base, side) in LEVELS) inverseLevel(coefficients, base, side, scratch)
    }

    /**
     * One level of the 2D synthesis. The four bands at [base] are HL, LH, HH and LL of side [side];
     * the level reconstructs a 2·[side] square over them — the LL band it reads is whatever the
     * coarser level left there.
     *
     * Horizontal first, then vertical, mirroring an analysis that filtered columns and then rows.
     * The other order looks nearly right and drifts by one wherever the passes interact.
     */
    private fun inverseLevel(buffer: IntArray, base: Int, side: Int, scratch: IntArray) {
        val full = side * 2
        val hl = base
        val lh = base + side * side
        val hh = base + 2 * side * side
        val ll = base + 3 * side * side
        // The scratch holds the low half (LL+HL reconstructed) then the high half (LH+HH).
        val low = 0
        val high = side * full

        for (y in 0 until side) {
            synthesizeRow(buffer, ll + y * side, buffer, hl + y * side, 1, scratch, low + y * full, 1, side)
            synthesizeRow(buffer, lh + y * side, buffer, hh + y * side, 1, scratch, high + y * full, 1, side)
        }
        for (x in 0 until full) {
            synthesizeRow(scratch, low + x, scratch, high + x, full, buffer, base + x, full, side)
        }
    }

    /**
     * The 5/3 synthesis filter over one line of [side] low and [side] high coefficients, writing
     * 2·[side] samples. Even samples subtract the detail around them, odd samples are the doubled
     * detail plus the average of their neighbours; both edges repeat the sample next to them, which
     * is what keeps the transform closed at the tile border.
     */
    private fun synthesizeRow(
        lowBuffer: IntArray,
        lowOffset: Int,
        highBuffer: IntArray,
        highOffset: Int,
        sourceStride: Int,
        out: IntArray,
        outOffset: Int,
        outStride: Int,
        side: Int,
    ) {
        fun low(index: Int) = lowBuffer[lowOffset + index * sourceStride]
        fun high(index: Int) = highBuffer[highOffset + index * sourceStride]
        fun setOut(index: Int, value: Int) {
            out[outOffset + index * outStride] = value
        }

        fun outAt(index: Int) = out[outOffset + index * outStride]

        setOut(0, low(0) - ((2 * high(0) + 1) shr 1))
        for (n in 1 until side) setOut(2 * n, low(n) - ((high(n - 1) + high(n) + 1) shr 1))
        for (n in 0 until side - 1) {
            setOut(2 * n + 1, (high(n) shl 1) + ((outAt(2 * n) + outAt(2 * n + 2)) shr 1))
        }
        setOut(2 * side - 1, (high(side - 1) shl 1) + outAt(2 * side - 2))
    }

    /** A quantization set is five bytes of packed nibbles. */
    const val QUANT_SET_SIZE = 5
}
