package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Interleaved RLE (MS-RDPEGDI 3.1.9), the format a 16-bit session's bitmap updates arrive in.
 *
 * The order codes and their run-length rules are the whole format: read a length by the wrong rule
 * and the decoder does not fail there, it drifts — the next byte of pixel data is taken for an
 * order code, and the error surfaces as "unknown code" somewhere further along. That is exactly how
 * a Windows session came out unreadable while xrdp's 32-bit one (planar, a different codec) was
 * fine, so the rules are pinned here one by one.
 */
class InterleavedRleTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun decode(vararg bytes: Int, width: Int, height: Int = 1): IntArray =
        InterleavedRle.decode(
            ByteArray(bytes.size) { bytes[it].toByte() },
            width,
            height,
            bytesPerPixel = 2,
            palette = null,
        )

    @Test
    fun `mega-mega set foreground run carries a 16-bit length`() {
        // 0xF6, not 0xF8: the mega orders are 0xF0 plus the order number, and reading the wrong one
        // as "set foreground" swallows a pixel that belongs to the next order.
        val pixels = decode(0xF6, 0x04, 0x00, 0xFF, 0xFF, width = 4)

        assertContentEquals(IntArray(4) { white }, pixels)
    }

    @Test
    fun `mega-mega dithered run is 0xF8`() {
        // Two pixels per length unit: length 2 fills four pixels, alternating.
        val pixels = decode(0xF8, 0x02, 0x00, 0xFF, 0xFF, 0x00, 0x00, width = 4)

        assertContentEquals(intArrayOf(white, black, white, black), pixels)
    }

    @Test
    fun `a foreground-background image counts blocks of eight pixels`() {
        // Header 0x41: order 2 (FGBG image), in-header length 1 — which means 8 pixels, one bitmask
        // byte, not one pixel. The bits run least-significant first.
        val pixels = decode(0x41, 0x0F, width = 8)

        assertContentEquals(intArrayOf(white, white, white, white, black, black, black, black), pixels)
    }

    @Test
    fun `a foreground-background image with a zero header length adds one, not the run bias`() {
        // 0x40 escapes to the next byte: 7 + 1 = 8 pixels. Adding the regular bias (32) instead
        // would read four mask bytes and run past the end of this bitmap.
        val pixels = decode(0x40, 0x07, 0x0F, width = 8)

        assertContentEquals(intArrayOf(white, white, white, white, black, black, black, black), pixels)
    }

    @Test
    fun `a colour run repeats one pixel`() {
        // Order 3 with an in-header length of 4, then one 16-bit pixel (RGB565 white).
        assertContentEquals(IntArray(4) { white }, decode(0x64, 0xFF, 0xFF, width = 4))
    }

    @Test
    fun `a regular run with a zero header length adds the bias`() {
        // Order 0 (background) on the first line writes black; 0x00 escapes to "next byte + 32".
        val pixels = decode(0x00, 0x02, width = 34)

        assertEquals(34, pixels.size)
        assertContentEquals(IntArray(34) { black }, pixels)
    }

    @Test
    fun `the second scanline is drawn against the one below it`() {
        // Bitmaps arrive bottom-up: four white pixels, then a background run that copies them.
        val pixels = decode(0xF6, 0x04, 0x00, 0xFF, 0xFF, 0x04, width = 4, height = 2)

        assertContentEquals(IntArray(4) { white }, pixels.copyOfRange(4, 8), "the bottom row")
        assertContentEquals(IntArray(4) { white }, pixels.copyOfRange(0, 4), "copied onto the row above")
    }
}
