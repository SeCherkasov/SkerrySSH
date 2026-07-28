package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The RDP6 planar codec (MS-RDPEGDI 2.2.2.5.1) — how a 32-bit session's bitmaps arrive.
 *
 * The cases here are the ones that produce an almost-right picture when they are wrong: the escape
 * forms of the control byte, runs stopping at the end of a row, and the vertical delta coding.
 */
class PlanarCodecTest {

    @Test
    fun `raw planes are read in R, G, B order`() {
        // header 0x20 = no alpha, no RLE; then three 2x1 planes.
        val data = byteArrayOf(0x20, 0xFF.toByte(), 0x10, 0x00, 0x20, 0x00, 0x30)

        val pixels = PlanarCodec.decode(data, width = 2, height = 1)

        assertEquals(0xFF, (pixels[0] ushr 24) and 0xFF) // opaque: RDP sessions have no transparency
        assertEquals(0xFF, (pixels[0] shr 16) and 0xFF) // red plane
        assertEquals(0x00, (pixels[0] shr 8) and 0xFF) // green plane
        assertEquals(0x00, pixels[0] and 0xFF) // blue plane
        assertEquals(0x10, (pixels[1] shr 16) and 0xFF)
        assertEquals(0x20, (pixels[1] shr 8) and 0xFF)
        assertEquals(0x30, pixels[1] and 0xFF)
    }

    @Test
    fun `an alpha plane is read and dropped, not painted`() {
        // header 0x00 = alpha present; the pixels stay opaque regardless of what it holds.
        val data = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44)

        val pixels = PlanarCodec.decode(data, width = 1, height = 1)

        assertEquals(0xFF, (pixels[0] ushr 24) and 0xFF)
        assertEquals(0x22, (pixels[0] shr 16) and 0xFF) // red, the plane after alpha
    }

    @Test
    fun `a run repeats the byte before it and stops at the end of the row`() {
        // header 0x30 = no alpha + RLE. Per plane: one literal then a run of 3, twice (two rows).
        val plane = byteArrayOf(0x13, 0x7F, 0x13, 0x40)
        val data = byteArrayOf(0x30) + plane + plane + plane

        val pixels = PlanarCodec.decode(data, width = 4, height = 2)

        // Row 0: the literal, then three copies of it.
        assertEquals(0x7F, (pixels[0] shr 16) and 0xFF)
        assertEquals(0x7F, (pixels[3] shr 16) and 0xFF)
        // Row 1 is delta-coded against row 0: 0x40 encodes +32.
        assertEquals(0x7F + 32, (pixels[4] shr 16) and 0xFF)
    }

    @Test
    fun `the low-nibble escapes code long runs without literals`() {
        // 0x01 = "16 + high nibble" run, 0x02 = "32 + high nibble" run.
        val plane = byteArrayOf(0x01, 0x05) // 16-byte run of zero, then 5 literals... row is 16 wide
        val data = byteArrayOf(0x30) + plane + plane + plane

        val pixels = PlanarCodec.decode(data, width = 16, height = 1)

        // A run at the head of a row repeats zero, which is what the encoder assumes.
        assertEquals(16, pixels.size)
        assertEquals(0, (pixels[0] shr 16) and 0xFF)
        assertEquals(0, (pixels[15] shr 16) and 0xFF)
    }

    @Test
    fun `the vertical delta is sign-magnitude, so a row can go down as well as up`() {
        val plane = byteArrayOf(
            0x40, 0x64, // row 0: one literal 0x64, run of 0 -> the row is one pixel wide
            0x40, 0x03, // row 1: delta 0x03 = -2
        )
        val data = byteArrayOf(0x30) + plane + plane + plane

        val pixels = PlanarCodec.decode(data, width = 1, height = 2)

        assertEquals(0x64, (pixels[0] shr 16) and 0xFF)
        assertEquals(0x64 - 2, (pixels[1] shr 16) and 0xFF)
    }

    @Test
    fun `chroma subsampling is refused rather than guessed at`() {
        // Bit 0x08 is CS; this client never advertises it, and decoding it wrong would paint the
        // right shapes in the wrong colours.
        assertFailsWith<RdpProtocolException> {
            PlanarCodec.decode(byteArrayOf(0x28, 0, 0, 0), width = 1, height = 1)
        }
    }

    @Test
    fun `a truncated plane leaves the rest of the bitmap rather than reading past the buffer`() {
        val data = byteArrayOf(0x30, 0x40, 0x55) // one row of one plane, then nothing

        val pixels = PlanarCodec.decode(data, width = 2, height = 2)

        assertEquals(4, pixels.size) // decoded what was there, no exception
    }
}
