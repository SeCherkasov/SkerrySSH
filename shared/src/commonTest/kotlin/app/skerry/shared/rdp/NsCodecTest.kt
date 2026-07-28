package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * NSCodec.
 *
 * Section 4 of MS-RDPNSC prints a compressed stream and the 600 bytes it decompresses to, which is
 * the one test worth having: it fixes the run-length decoder, the plane geometry, the colour loss
 * recovery and the inverse colour transform against real server output all at once, and it fails on
 * the two errors that produce a picture rather than a crash — the wrong chroma shift and reading the
 * chroma byte as signed before shifting it.
 */
class NsCodecTest {

    @Test
    fun `the example stream decodes to the bitmap the specification prints`() {
        val pixels = NsCodec.decode(RdpReader(EXAMPLE), width = 15, height = 10)

        assertEquals(argb(EXAMPLE_DECOMPRESSED), pixels.toList())
    }

    @Test
    fun `a plane that arrives at its full length is not run-length decoded`() {
        // 2x1, no subsampling: every plane is two bytes long, so none of them is encoded. A run
        // decoder let loose on these would read the pair as a run and paint one colour twice.
        val stream = stream(
            colorLossLevel = 1,
            subsampled = false,
            luma = byteArrayOf(0x40, 0x60),
            orange = byteArrayOf(0, 0),
            green = byteArrayOf(0, 0),
        )

        val pixels = NsCodec.decode(RdpReader(stream), width = 2, height = 1)

        assertEquals(listOf(0xFF404040.toInt(), 0xFF606060.toInt()), pixels.toList())
    }

    @Test
    fun `chroma is recovered by one shift less than the colour loss level`() {
        // Orange chroma 0x20 at level 3 shifts left twice to 0x80, which is -128 as a signed byte.
        val stream = stream(
            colorLossLevel = 3,
            subsampled = false,
            luma = byteArrayOf(0x7F.toByte()),
            orange = byteArrayOf(0x20),
            green = byteArrayOf(0),
        )

        val pixels = NsCodec.decode(RdpReader(stream), width = 1, height = 1)

        // red = 127 - 128, green = 127, blue = 127 + 128
        assertEquals(0xFF007FFF.toInt(), pixels[0])
    }

    @Test
    fun `subsampled chroma covers a two by two block`() {
        val stream = stream(
            colorLossLevel = 1,
            subsampled = true,
            // Subsampling pads the luma plane to eight columns; only the first two are the image.
            luma = ByteArray(8 * 2) { 0x80.toByte() },
            orange = ByteArray(4 * 1),
            green = byteArrayOf(0x10, 0, 0, 0),
        )

        val pixels = NsCodec.decode(RdpReader(stream), width = 2, height = 2)

        // The one chroma sample colours all four pixels: green chroma 0x10 raises green and lowers
        // the other two channels by the same amount.
        assertEquals(List(4) { 0xFF709070.toInt() }, pixels.toList())
    }

    @Test
    fun `a value five bytes from the end of a plane is a literal, not a run`() {
        // The last four bytes of a plane are never encoded, so a pair of equal bytes that straddles
        // that boundary is two raw bytes. A decoder that reads them as a run swallows the byte after
        // them as a run length and loses the rest of the plane.
        val stream = stream(
            colorLossLevel = 1,
            subsampled = false,
            luma = hex("10 10 05 aa aa bb cc dd"),
            orange = ByteArray(12),
            green = ByteArray(12),
        )

        val pixels = NsCodec.decode(RdpReader(stream), width = 12, height = 1)

        val expected = List(7) { 0xFF101010.toInt() } +
            listOf(0xFFAAAAAA.toInt(), 0xFFAAAAAA.toInt(), 0xFFBBBBBB.toInt(), 0xFFCCCCCC.toInt(), 0xFFDDDDDD.toInt())
        assertEquals(expected, pixels.toList())
    }

    @Test
    fun `a plane longer than the image it describes is rejected`() {
        val stream = stream(
            colorLossLevel = 1,
            subsampled = false,
            luma = ByteArray(3),
            orange = ByteArray(1),
            green = ByteArray(1),
        )

        assertFailsWith<RdpProtocolException> { NsCodec.decode(RdpReader(stream), width = 1, height = 1) }
    }

    @Test
    fun `a colour loss level outside the range the format allows is rejected`() {
        val stream = stream(colorLossLevel = 0, subsampled = false, ByteArray(1), ByteArray(1), ByteArray(1))

        assertFailsWith<RdpProtocolException> { NsCodec.decode(RdpReader(stream), width = 1, height = 1) }
    }

    private fun stream(
        colorLossLevel: Int,
        subsampled: Boolean,
        luma: ByteArray,
        orange: ByteArray,
        green: ByteArray,
    ): ByteArray = RdpWriter(32).apply {
        u32le(luma.size)
        u32le(orange.size)
        u32le(green.size)
        u32le(0) // no alpha plane
        u8(colorLossLevel)
        u8(if (subsampled) 1 else 0)
        u16le(0) // reserved
        bytes(luma)
        bytes(orange)
        bytes(green)
    }.toByteArray()

    /** The decompressed dump as the codec returns it: blue, green, red, alpha per pixel. */
    private fun argb(dump: ByteArray): List<Int> = List(dump.size / 4) {
        val blue = dump[it * 4].toInt() and 0xFF
        val green = dump[it * 4 + 1].toInt() and 0xFF
        val red = dump[it * 4 + 2].toInt() and 0xFF
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private companion object {
        /** MS-RDPNSC 4: a 15x10 image, colour loss level 3, chroma subsampled. */
        val EXAMPLE = hex(
            """
            71 00 00 00 07 00 00 00 0b 00 00 00 07 00 00 00
            03 01 00 00 63 63 01 64 64 00 63 63 02 64 64 00
            63 63 00 64 64 01 63 63 01 64 64 01 63 63 01 64
            64 00 63 63 00 64 64 01 63 63 00 64 64 0c 63 63
            00 64 64 0c 63 63 00 64 64 0c 63 63 00 64 64 0c
            63 64 64 04 63 64 63 63 00 64 64 03 63 64 64 03
            63 63 00 64 63 63 00 64 64 03 65 63 64 64 01 63
            64 64 00 65 64 64 06 63 64 64 00 63 63 00 64 64
            04 64 65 65 65 22 22 22 22 22 22 22 37 37 19 36
            37 37 06 37 37 37 37 ff ff 90 ff ff ff ff
            """,
        )

        val EXAMPLE_DECOMPRESSED = hex(
            """
            ff 3f 0f ff ff 3f 0f ff ff 3f 0f ff ff 40 10 ff
            ff 40 10 ff ff 3f 0f ff ff 3f 0f ff ff 3f 0f ff
            ff 3f 0f ff ff 40 10 ff ff 40 10 ff ff 3f 0f ff
            ff 3f 0f ff ff 40 10 ff ff 40 10 ff ff 3f 0f ff
            ff 3f 0f ff ff 3f 0f ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 3f 0f ff ff 3f 0f ff ff 3f 0f ff
            ff 40 10 ff ff 40 10 ff ff 3f 0f ff ff 3f 0f ff
            ff 40 10 ff ff 40 10 ff ff 3f 0f ff ff 3f 0f ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 3f 0f ff ff 3f 0f ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 3f 0f ff ff 3f 0f ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 3f 0f ff
            ff 3f 0f ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 3f 0f ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 3c 14 ff ff 3b 13 ff ff 40 10 ff ff 3f 0f ff
            ff 3f 0f ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 3f 0f ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 3b 13 ff
            ff 3b 13 ff ff 40 10 ff ff 3f 0f ff ff 3f 0f ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 41 11 ff ff 3f 0f ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 3f 0f ff ff 40 10 ff ff 40 10 ff
            ff 41 11 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 3f 0f ff ff 40 10 ff ff 40 10 ff ff 3f 0f ff
            ff 3f 0f ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 40 10 ff ff 40 10 ff ff 40 10 ff ff 40 10 ff
            ff 41 11 ff ff 41 11 ff
            """,
        )
    }
}
