package app.skerry.shared.rdp

/**
 * Interleaved RLE, the bitmap compression of MS-RDPEGDI 3.1.9 — the format legacy bitmap updates
 * arrive in when the server is not using a codec.
 *
 * The name is literal: most runs are coded against the *previous scanline*, so the decoder carries
 * the last decoded row as a running background. Three code shapes share one byte space — regular
 * (3-bit code, 5-bit length), lite (4-bit code, 4-bit length) and mega-mega (a code byte and a
 * 16-bit length) — and a zero length in the short forms means "read another byte and add the bias".
 *
 * Two rules are easy to miss and produce a picture that looks almost right:
 * - on the FIRST decoded scanline there is no previous row, so background runs write black and
 *   foreground runs write the foreground colour directly rather than XOR-ing;
 * - a run that *sets* the foreground colour makes the next background run start with one XOR-ed
 *   pixel (the `insertFgPel` rule).
 *
 * The input is a remote peer's bytes: every write is bounds-checked against the destination.
 */
object InterleavedRle {

    /**
     * Decode [data] into an ARGB array of [width]×[height]. [bytesPerPixel] is 1, 2 or 3 (8-, 16-
     * and 24-bit sessions); [palette] resolves indices in 8-bit sessions.
     *
     * @throws RdpProtocolException the stream is malformed or overruns the bitmap
     */
    fun decode(data: ByteArray, width: Int, height: Int, bytesPerPixel: Int, palette: IntArray?): IntArray {
        RdpImageBounds.requireSize(width, height, "an RLE bitmap")
        if (bytesPerPixel !in 1..3) throw RdpProtocolException("unsupported bitmap depth $bytesPerPixel")
        return Decoder(data, width, height, bytesPerPixel, palette).run()
    }

    /** RDP's 16-bit pixels are RGB565; low bits are replicated so full-scale values stay full-scale. */
    fun rgb565ToArgb(pixel: Int): Int {
        val red = (pixel shr 11) and 0x1F
        val green = (pixel shr 5) and 0x3F
        val blue = pixel and 0x1F
        return ALPHA or
            (((red shl 3) or (red shr 2)) shl 16) or
            (((green shl 2) or (green shr 4)) shl 8) or
            ((blue shl 3) or (blue shr 2))
    }

    private class Decoder(
        data: ByteArray,
        private val width: Int,
        private val height: Int,
        private val bytesPerPixel: Int,
        private val palette: IntArray?,
    ) {
        private val reader = RdpReader(data)
        private val out = IntArray(width * height)

        // Bitmaps arrive bottom-up: the first decoded scanline is the bottom row of the image.
        private var row = height - 1
        private var column = 0
        private var foreground = WHITE
        private var insertFgPel = false

        private val onFirstLine: Boolean get() = row == height - 1

        fun run(): IntArray {
            while (reader.remaining > 0 && row >= 0) {
                val code = reader.u8()
                when {
                    code == SPECIAL_FGBG_1 -> foregroundBackgroundBits(SPECIAL_MASK_1, 8)
                    code == SPECIAL_FGBG_2 -> foregroundBackgroundBits(SPECIAL_MASK_2, 8)
                    code == SPECIAL_WHITE -> put(WHITE)
                    code == SPECIAL_BLACK -> put(BLACK)
                    else -> operation(code)
                }
            }
            return out
        }

        private fun operation(code: Int) {
            val mega = code and 0xF0 == 0xF0
            val kind: Int
            val length: Int
            if (mega) {
                kind = MEGA_KINDS[code and 0x0F] ?: throw RdpProtocolException("unknown RLE code 0x${code.toString(16)}")
                length = reader.u16le()
            } else if (code shr 4 in 0xC..0xE) {
                kind = LITE_KINDS.getValue(code shr 4)
                length = runLength(code and 0x0F, kind, LITE_RUN_BIAS)
            } else {
                kind = when (code shr 5) {
                    0x0 -> KIND_BACKGROUND_RUN
                    0x1 -> KIND_FOREGROUND_RUN
                    0x2 -> KIND_FGBG_IMAGE
                    0x3 -> KIND_COLOR_RUN
                    0x4 -> KIND_COLOR_IMAGE
                    else -> throw RdpProtocolException("unknown RLE code 0x${code.toString(16)}")
                }
                length = runLength(code and 0x1F, kind, REGULAR_RUN_BIAS)
            }
            if (length < 0) throw RdpProtocolException("negative RLE run length")

            when (kind) {
                KIND_BACKGROUND_RUN -> backgroundRun(length)
                KIND_FOREGROUND_RUN -> foregroundRun(length)
                KIND_SET_FOREGROUND_RUN -> {
                    foreground = readPixel()
                    insertFgPel = true
                    foregroundRun(length)
                }

                KIND_COLOR_RUN -> {
                    val color = readPixel()
                    repeat(length) { put(color) }
                }

                KIND_COLOR_IMAGE -> repeat(length) { put(readPixel()) }
                KIND_DITHERED_RUN -> {
                    val first = readPixel()
                    val second = readPixel()
                    // The length counts pixel pairs.
                    repeat(length) {
                        put(first)
                        put(second)
                    }
                }

                KIND_FGBG_IMAGE -> foregroundBackgroundImage(length)
                KIND_SET_FGBG_IMAGE -> {
                    foreground = readPixel()
                    foregroundBackgroundImage(length)
                }
            }
        }

        /**
         * Run length of a short-form order (MS-RDPEGDI 3.1.9.1). A zero in the header means "the
         * next byte carries it", but the bias differs by order: the foreground/background image
         * orders count *blocks of eight pixels* — one bit each — so their in-header length is
         * multiplied by eight and their escape form adds one rather than the run bias.
         */
        private fun runLength(inHeader: Int, kind: Int, bias: Int): Int {
            val isFgBgImage = kind == KIND_FGBG_IMAGE || kind == KIND_SET_FGBG_IMAGE
            return when {
                inHeader != 0 && isFgBgImage -> inHeader * 8
                inHeader != 0 -> inHeader
                isFgBgImage -> reader.u8() + 1
                else -> reader.u8() + bias
            }
        }

        private fun backgroundRun(length: Int) {
            var remaining = length
            if (insertFgPel) {
                // A colour-setting run leaves one XOR-ed pixel at the head of the next background run.
                put(if (onFirstLine) foreground else above() xor foreground)
                remaining--
                insertFgPel = false
            }
            repeat(maxOf(remaining, 0)) { put(if (onFirstLine) BLACK else above()) }
        }

        private fun foregroundRun(length: Int) {
            insertFgPel = false
            repeat(length) { put(if (onFirstLine) foreground else above() xor foreground) }
        }

        private fun foregroundBackgroundImage(length: Int) {
            insertFgPel = false
            var remaining = length
            while (remaining > 0) {
                val bits = reader.u8()
                val count = minOf(8, remaining)
                foregroundBackgroundBits(bits, count)
                remaining -= count
            }
        }

        /** Write [count] pixels driven by the bitmask [bits], least significant bit first. */
        private fun foregroundBackgroundBits(bits: Int, count: Int) {
            for (bit in 0 until count) {
                val useForeground = (bits shr bit) and 1 == 1
                val value = when {
                    onFirstLine && useForeground -> foreground
                    onFirstLine -> BLACK
                    useForeground -> above() xor foreground
                    else -> above()
                }
                put(value)
            }
        }

        /** The pixel directly above the write position — the row decoded just before this one. */
        private fun above(): Int = if (row + 1 < height) out[(row + 1) * width + column] else BLACK

        private fun put(argb: Int) {
            if (row < 0) throw RdpProtocolException("RLE stream overruns the bitmap")
            out[row * width + column] = argb
            column++
            if (column == width) {
                column = 0
                row--
            }
        }

        private fun readPixel(): Int = when (bytesPerPixel) {
            1 -> {
                val index = reader.u8()
                palette?.getOrNull(index) ?: BLACK
            }

            2 -> rgb565ToArgb(reader.u16le())
            else -> {
                val blue = reader.u8()
                val green = reader.u8()
                val red = reader.u8()
                ALPHA or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    private const val ALPHA = 0xFF shl 24
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    private const val REGULAR_RUN_BIAS = 32
    private const val LITE_RUN_BIAS = 16
    private const val SPECIAL_FGBG_1 = 0xF9
    private const val SPECIAL_FGBG_2 = 0xFA
    private const val SPECIAL_WHITE = 0xFD
    private const val SPECIAL_BLACK = 0xFE

    /** The fixed bitmasks the two special foreground/background codes stand for. */
    private const val SPECIAL_MASK_1 = 0x03
    private const val SPECIAL_MASK_2 = 0x05

    private const val KIND_BACKGROUND_RUN = 0
    private const val KIND_FOREGROUND_RUN = 1
    private const val KIND_FGBG_IMAGE = 2
    private const val KIND_COLOR_RUN = 3
    private const val KIND_COLOR_IMAGE = 4
    private const val KIND_SET_FOREGROUND_RUN = 5
    private const val KIND_SET_FGBG_IMAGE = 6
    private const val KIND_DITHERED_RUN = 7

    private val LITE_KINDS = mapOf(
        0xC to KIND_SET_FOREGROUND_RUN,
        0xD to KIND_SET_FGBG_IMAGE,
        0xE to KIND_DITHERED_RUN,
    )

    /**
     * The mega-mega orders (MS-RDPEGDI 2.2.2.5.1): 0xF0 plus the order, with a 16-bit run length.
     * 0xF5 is unused, and 0xF9/0xFA/0xFD/0xFE are the special orders handled before these.
     */
    private val MEGA_KINDS = mapOf(
        0x0 to KIND_BACKGROUND_RUN,
        0x1 to KIND_FOREGROUND_RUN,
        0x2 to KIND_FGBG_IMAGE,
        0x3 to KIND_COLOR_RUN,
        0x4 to KIND_COLOR_IMAGE,
        0x6 to KIND_SET_FOREGROUND_RUN,
        0x7 to KIND_SET_FGBG_IMAGE,
        0x8 to KIND_DITHERED_RUN,
    )
}
