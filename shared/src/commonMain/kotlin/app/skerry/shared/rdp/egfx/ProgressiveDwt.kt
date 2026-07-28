package app.skerry.shared.rdp.egfx

/**
 * The inverse wavelet transform of the progressive codec in its reduce-extrapolate mode
 * (MS-RDPEGFX 3.2.8.1.3).
 *
 * It differs from RemoteFX's transform in one idea with wide consequences: the bands are one sample
 * wider than half their level, so the filter never has to guess what lies past the tile edge. That
 * makes the band sizes uneven — 31×33 here, 9×9 there — and the whole layout of the coefficient
 * buffer follows from it (see [Progressive] for the offsets).
 *
 * Arithmetic follows the specification's sample code literally, division included: `/ 2` truncates
 * towards zero, which for a negative coefficient is not the same as a shift, and the difference
 * compounds through three levels.
 */
internal object ProgressiveDwt {

    /** Reconstruct a 64×64 tile from [buffer], in place. */
    fun inverse(buffer: IntArray) {
        require(buffer.size >= Progressive.TILE_COEFFICIENTS) { "a tile is 4096 coefficients" }
        val scratch = IntArray(Progressive.TILE_COEFFICIENTS)
        decodeLevel(buffer, base = 3807, level = 3, scratch)
        decodeLevel(buffer, base = 3007, level = 2, scratch)
        decodeLevel(buffer, base = 0, level = 1, scratch)
    }

    /** Samples of the low band at [level]; one more than half, which is what "extrapolate" buys. */
    fun lowCount(level: Int): Int = (64 shr level) + 1

    fun highCount(level: Int): Int = if (level == 1) 31 else (64 + (1 shl (level - 1))) shr level

    private fun decodeLevel(buffer: IntArray, base: Int, level: Int, scratch: IntArray) {
        val low = lowCount(level)
        val high = highCount(level)
        val step = low + high

        val hl = base
        val lh = hl + high * low
        val hh = lh + low * high
        val ll = hh + high * high

        // Horizontal first: the low half of the result comes from LL and HL, the high half from
        // LH and HH, and both land in the scratch buffer as full-width rows.
        for (row in 0 until low) {
            synthesize(buffer, ll + row * low, 1, buffer, hl + row * high, 1, scratch, row * step, 1, low, high)
        }
        for (row in 0 until high) {
            synthesize(
                buffer, lh + row * low, 1, buffer, hh + row * high, 1,
                scratch, (low + row) * step, 1, low, high,
            )
        }
        // Vertical, column by column, back over the bands that were just consumed.
        for (column in 0 until step) {
            synthesize(
                scratch, column, step, scratch, low * step + column, step,
                buffer, base + column, step, low, high,
            )
        }
    }

    /**
     * One line of the 5/3 synthesis. The even samples subtract the detail around them and the odd
     * ones add twice the detail to the average of their neighbours; the tail depends on how the two
     * band lengths compare, which is where the extrapolated extra sample is spent.
     */
    private fun synthesize(
        lowBuffer: IntArray,
        lowOffset: Int,
        lowStride: Int,
        highBuffer: IntArray,
        highOffset: Int,
        highStride: Int,
        out: IntArray,
        outOffset: Int,
        outStride: Int,
        lowCount: Int,
        highCount: Int,
    ) {
        fun low(index: Int) = lowBuffer[lowOffset + index * lowStride]
        fun high(index: Int) = highBuffer[highOffset + index * highStride]
        fun write(index: Int, value: Int) {
            out[outOffset + index * outStride] = clamp(value)
        }

        var h0 = high(0)
        var l0 = low(0)
        var x0 = clamp(l0 - h0)
        var x2 = x0

        for (j in 0 until highCount - 1) {
            val h1 = high(j + 1)
            l0 = low(j + 1)
            x2 = clamp(l0 - ((h0 + h1) / 2))
            write(2 * j, x0)
            write(2 * j + 1, ((x0 + x2) / 2) + 2 * h0)
            x0 = x2
            h0 = h1
        }

        val tail = 2 * (highCount - 1)
        when {
            lowCount <= highCount -> {
                write(tail, x2)
                write(tail + 1, x2 + 2 * h0)
            }

            lowCount <= highCount + 1 -> {
                x0 = clamp(low(highCount) - h0)
                write(tail, x2)
                write(tail + 1, ((x0 + x2) / 2) + 2 * h0)
                write(tail + 2, x0)
            }

            else -> {
                x0 = clamp(low(highCount) - (h0 / 2))
                write(tail, x2)
                write(tail + 1, ((x0 + x2) / 2) + 2 * h0)
                write(tail + 2, x0)
                write(tail + 3, (x0 + low(highCount + 1)) / 2)
            }
        }
    }

    /** Coefficients live in 16 bits; the codec saturates rather than wrapping. */
    private fun clamp(value: Int): Int = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
}
