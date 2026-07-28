package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpWriter
import app.skerry.shared.rdp.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * ClearCodec (MS-RDPEGFX 2.2.4.1).
 *
 * The four annotated network dumps in section 4.1.1 of the specification carry this file: they are
 * real server output with every field named, so a test built on them fails when the decoder disagrees
 * with Windows rather than when it disagrees with a test encoder written from the same misreading.
 * The paths the dumps cannot reach on their own — a cache hit needs a previous packet to have filled
 * the entry — are driven by streams assembled here, one behaviour at a time.
 */
class ClearCodecTest {

    private val codec = ClearCodec()

    @Test
    fun `example 2 decodes an RLEX subcodec`() {
        val pixels = codec.decode(EXAMPLE_2, width = 78, height = 17)

        assertEquals(78 * 17, pixels.size)
        // The first segment: a run of four palette entry 0 followed by the suite 0, 1.
        assertEquals(List(5) { WHITE } + BLACK, pixels.take(6))
        // The sixteenth segment, which the specification decodes by hand: an empty run and a suite
        // of three. Its output starts once the fifteen segments before it have placed 624 pixels.
        assertEquals(listOf(0xFFFFDB90.toInt(), 0xFF3A0000.toInt(), 0xFF3A90DB.toInt()), pixels.slice(624..626))
    }

    @Test
    fun `example 3 decodes a residual layer`() {
        val pixels = codec.decode(EXAMPLE_3, width = 64, height = 24)

        // Three runs cover the image exactly: 1408 pixels of 0xfefefe, then 64 white, then 64 more
        // of 0xfefefe. The band that follows repaints rows 3 to 11 in its own background, which is
        // the same colour, so what the runs left is what stays.
        assertEquals(64 * 24, pixels.size)
        assertEquals(List(1408) { NEAR_WHITE }, pixels.take(1408))
        assertEquals(List(64) { WHITE }, pixels.slice(1408 until 1472))
        assertEquals(List(64) { NEAR_WHITE }, pixels.slice(1472 until 1536))
    }

    @Test
    fun `example 4 decodes a band whose first V-Bar carries its pixels`() {
        val pixels = codec.decode(EXAMPLE_4, width = 7, height = 15)

        // The first V-Bar is a short V-Bar cache miss: fifteen pixels, one per row of column 0.
        assertEquals(0xFFFFFFFF.toInt(), pixels[0])
        assertEquals(0xFFFFFFB6.toInt(), pixels[3 * 7])
        assertEquals(0xFF66B6FF.toInt(), pixels[6 * 7])
        assertEquals(0xFF3A90DB.toInt(), pixels[10 * 7])
        assertEquals(0xFFB6FFFF.toInt(), pixels[11 * 7])
        // The other six are cache hits on entries no earlier packet in this test filled. The band's
        // background stands in for them, which is what keeps a desynchronised cache off the wire.
        assertEquals(BLACK, pixels[1])
    }

    @Test
    fun `a V-Bar cache hit repeats the column a previous band stored`() {
        val stored = codec.decode(
            stream(bands = band(0, 0, 0, 2, bkg = RED, vBars = listOf(shortVBarMiss(yOn = 0, GREEN, BLUE, GREEN)))),
            width = 1,
            height = 3,
        )
        assertEquals(listOf(GREEN, BLUE, GREEN), stored.toList())

        val repeated = codec.decode(
            stream(bands = band(0, 0, 0, 2, bkg = RED, vBars = listOf(vBarHit(0)))),
            width = 1,
            height = 3,
        )

        assertEquals(listOf(GREEN, BLUE, GREEN), repeated.toList())
    }

    @Test
    fun `a short V-Bar cache hit places the stored pixels at a new offset`() {
        codec.decode(
            stream(bands = band(0, 0, 0, 2, bkg = RED, vBars = listOf(shortVBarMiss(yOn = 0, GREEN)))),
            width = 1,
            height = 3,
        )

        val moved = codec.decode(
            stream(bands = band(0, 0, 0, 2, bkg = RED, vBars = listOf(shortVBarHit(index = 0, yOn = 1)))),
            width = 1,
            height = 3,
        )

        // The stored pixel lands one row down; the background of this band fills the rest.
        assertEquals(listOf(RED, GREEN, RED), moved.toList())
    }

    @Test
    fun `a short V-Bar cache hit also fills the V-Bar the next hit can name`() {
        codec.decode(
            stream(bands = band(0, 0, 0, 1, bkg = RED, vBars = listOf(shortVBarMiss(yOn = 0, GREEN, GREEN)))),
            width = 1,
            height = 2,
        )
        // Entry 0 of the V-Bar storage now holds the column above; the short V-Bar hit below writes
        // entry 1, which the last stream names.
        codec.decode(
            stream(bands = band(0, 0, 0, 1, bkg = BLUE, vBars = listOf(shortVBarHit(index = 0, yOn = 1)))),
            width = 1,
            height = 2,
        )

        val third = codec.decode(
            stream(bands = band(0, 0, 0, 1, bkg = RED, vBars = listOf(vBarHit(1)))),
            width = 1,
            height = 2,
        )

        assertEquals(listOf(BLUE, GREEN), third.toList())
    }

    @Test
    fun `a V-Bar cache hit leaves the storage cursor where it was`() {
        codec.decode(
            stream(bands = band(0, 0, 0, 0, bkg = RED, vBars = listOf(shortVBarMiss(yOn = 0, GREEN)))),
            width = 1,
            height = 1,
        )
        // The hit reads entry 0 without consuming a slot, so the miss beside it writes entry 1 —
        // exactly where the server, which never advances its own cursor on a hit, expects it.
        codec.decode(
            stream(
                bands = band(0, 1, 0, 0, bkg = RED, vBars = listOf(vBarHit(0), shortVBarMiss(yOn = 0, BLUE))),
            ),
            width = 2,
            height = 1,
        )

        val hit = codec.decode(
            stream(bands = band(0, 0, 0, 0, bkg = RED, vBars = listOf(vBarHit(1)))),
            width = 1,
            height = 1,
        )

        assertEquals(BLUE, hit[0])
    }

    @Test
    fun `a cache reset rewinds the storage cursors`() {
        codec.decode(
            stream(bands = band(0, 0, 0, 0, bkg = RED, vBars = listOf(shortVBarMiss(yOn = 0, GREEN)))),
            width = 1,
            height = 1,
        )
        codec.decode(
            stream(
                flags = FLAG_CACHE_RESET,
                bands = band(0, 0, 0, 0, bkg = RED, vBars = listOf(shortVBarMiss(yOn = 0, BLUE))),
            ),
            width = 1,
            height = 1,
        )

        val hit = codec.decode(
            stream(bands = band(0, 0, 0, 0, bkg = RED, vBars = listOf(vBarHit(0)))),
            width = 1,
            height = 1,
        )

        // The second stream wrote entry 0 again, because the reset put the cursor back to zero.
        assertEquals(BLUE, hit[0])
    }

    @Test
    fun `a glyph hit repeats the image the packet that named the index decoded`() {
        val decoded = codec.decode(EXAMPLE_4, width = 7, height = 15)

        val fromGlyph = codec.decode(
            hex("03 0c 78 00"), // GLYPH_INDEX | GLYPH_HIT, glyph 120 — the slot example 4 filled
            width = 7,
            height = 15,
        )

        assertEquals(decoded.toList(), fromGlyph.toList())
    }

    @Test
    fun `a glyph stream still paints the image it stores`() {
        val glyph = codec.decode(
            stream(glyphIndex = 5, residual = residualRun(GREEN, 4)),
            width = 2,
            height = 2,
        )

        assertEquals(List(4) { GREEN }, glyph.toList())
    }

    @Test
    fun `an uncompressed subcodec is placed at the offset it names`() {
        val tile = RdpWriter(12)
            .bgr(GREEN).bgr(BLUE)
            .toByteArray()
        val stream = stream(
            residual = residualRun(RED, 4),
            subcodecs = subcodec(xStart = 0, yStart = 1, width = 2, height = 1, subCodecId = 0x00, data = tile),
        )

        val pixels = codec.decode(stream, width = 2, height = 2)

        assertEquals(listOf(RED, RED, GREEN, BLUE), pixels.toList())
    }

    @Test
    fun `a residual run past the end of the image is rejected`() {
        val stream = stream(residual = residualRun(GREEN, 5))

        assertFailsWith<RdpProtocolException> { codec.decode(stream, width = 2, height = 2) }
    }

    @Test
    fun `a residual run long enough to wrap the image index is rejected`() {
        // The run length escapes twice: 0xFF, then 0xFFFF, then the length itself. Two billion
        // pixels placed at a non-zero index overflow the sum an additive bounds check computes.
        val overflowingRun = RdpWriter(8).bgr(RED).bytes(hex("FF FF FF FF FF FF 7F")).toByteArray()
        val stream = stream(residual = residualRun(GREEN, 1) + overflowingRun)

        assertFailsWith<RdpProtocolException> { codec.decode(stream, width = 2, height = 2) }
    }

    @Test
    fun `an RLEX segment long enough to wrap the tile index is rejected`() {
        val palette = RdpWriter(8).u8(1).bgr(GREEN).toByteArray()
        val segment = RdpWriter(8).u8(0).bytes(hex("FF FF FF FF FF FF 7F")).toByteArray()
        val stream = stream(subcodecs = subcodec(0, 0, 2, 2, subCodecId = 0x02, data = palette + segment))

        assertFailsWith<RdpProtocolException> { codec.decode(stream, width = 2, height = 2) }
    }

    @Test
    fun `an image larger than any screen is refused before it is allocated`() {
        assertFailsWith<RdpProtocolException> {
            codec.decode(stream(residual = residualRun(GREEN, 1)), width = 65535, height = 32000)
        }
    }

    @Test
    fun `an unknown subcodec is rejected`() {
        val stream = stream(subcodec(0, 0, 1, 1, subCodecId = 0x7F, data = ByteArray(3)))

        assertFailsWith<RdpProtocolException> { codec.decode(stream, width = 1, height = 1) }
    }

    @Test
    fun `a band taller than a V-Bar may be is rejected`() {
        val stream = stream(bands = band(0, 0, 0, 52, bkg = RED, vBars = listOf(vBarHit(0))))

        assertFailsWith<RdpProtocolException> { codec.decode(stream, width = 1, height = 53) }
    }

    @Test
    fun `the glyph storage of one index does not answer for another`() {
        codec.decode(stream(glyphIndex = 7, residual = residualRun(GREEN, 1)), width = 1, height = 1)

        val other = codec.decode(hex("03 01 08 00"), width = 1, height = 1)

        assertNotEquals(GREEN, other[0])
    }

    // -- stream construction ------------------------------------------------------------------

    private fun stream(
        subcodecs: ByteArray = ByteArray(0),
        residual: ByteArray = ByteArray(0),
        bands: ByteArray = ByteArray(0),
        flags: Int = 0,
        glyphIndex: Int? = null,
    ): ByteArray = RdpWriter(32).apply {
        u8(flags or if (glyphIndex != null) FLAG_GLYPH_INDEX else 0)
        u8(0) // seqNumber: nothing in the decoder depends on it
        if (glyphIndex != null) u16le(glyphIndex)
        u32le(residual.size)
        u32le(bands.size)
        u32le(subcodecs.size)
        bytes(residual)
        bytes(bands)
        bytes(subcodecs)
    }.toByteArray()

    private fun residualRun(argb: Int, runLength: Int): ByteArray =
        RdpWriter(4).bgr(argb).u8(runLength).toByteArray()

    private fun band(
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        bkg: Int,
        vBars: List<ByteArray>,
    ): ByteArray = RdpWriter(16).apply {
        u16le(xStart)
        u16le(xEnd)
        u16le(yStart)
        u16le(yEnd)
        bgr(bkg)
        vBars.forEach { bytes(it) }
    }.toByteArray()

    private fun vBarHit(index: Int): ByteArray = RdpWriter(2).u16le(0x8000 or index).toByteArray()

    private fun shortVBarHit(index: Int, yOn: Int): ByteArray =
        RdpWriter(3).u16le(0x4000 or index).u8(yOn).toByteArray()

    private fun shortVBarMiss(yOn: Int, vararg pixels: Int): ByteArray = RdpWriter(16).apply {
        u16le(((yOn + pixels.size) shl 8) or yOn)
        pixels.forEach { bgr(it) }
    }.toByteArray()

    private fun subcodec(
        xStart: Int,
        yStart: Int,
        width: Int,
        height: Int,
        subCodecId: Int,
        data: ByteArray,
    ): ByteArray = RdpWriter(16).apply {
        u16le(xStart)
        u16le(yStart)
        u16le(width)
        u16le(height)
        u32le(data.size)
        u8(subCodecId)
        bytes(data)
    }.toByteArray()

    private fun RdpWriter.bgr(argb: Int): RdpWriter =
        u8(argb and 0xFF).u8((argb ushr 8) and 0xFF).u8((argb ushr 16) and 0xFF)

    private companion object {
        const val FLAG_GLYPH_INDEX = 0x01
        const val FLAG_CACHE_RESET = 0x04

        val WHITE = 0xFFFFFFFF.toInt()
        val BLACK = 0xFF000000.toInt()
        val NEAR_WHITE = 0xFFFEFEFE.toInt()
        val RED = 0xFFFF0000.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val BLUE = 0xFF0000FF.toInt()

        /** MS-RDPEGFX 4.1.1.2: a 78x17 bitmap carried entirely by one RLEX subcodec. */
        val EXAMPLE_2 = hex(
            """
            00 0d 00 00 00 00 00 00 00 00 82 00 00 00 00 00
            00 00 4e 00 11 00 75 00 00 00 02 0e ff ff ff 00
            00 00 db ff ff 00 3a 90 ff b6 66 66 b6 ff b6 66
            00 90 db ff 00 00 3a db 90 3a 3a 90 db 66 00 00
            ff ff b6 64 64 64 11 04 11 4c 11 4c 11 4c 11 4c
            11 4c 00 47 13 00 01 01 04 00 01 00 00 47 16 00
            11 02 00 47 29 00 11 01 00 49 0a 00 01 00 04 00
            01 00 00 4a 0a 00 09 00 01 00 00 47 05 00 01 01
            1c 00 01 00 11 4c 11 4c 11 4c 00 47 0d 4d 00 4d
            """,
        )

        /** MS-RDPEGFX 4.1.1.3: a 64x24 bitmap of three residual runs and one band of cache hits. */
        val EXAMPLE_3 = hex(
            """
            00 df 0e 00 00 00 8b 00 00 00 00 00 00 00 fe fe
            fe ff 80 05 ff ff ff 40 fe fe fe 40 00 00 3f 00
            03 00 0b 00 fe fe fe c5 d0 c6 d0 c7 d0 68 d4 69
            d4 6a d4 6b d4 6c d4 6d d4 1a d4 1a d4 a6 d0 6e
            d4 6f d4 70 d4 71 d4 72 d4 73 d4 74 d4 21 d4 22
            d4 23 d4 24 d4 25 d4 d9 d0 da d0 db d0 c5 d0 c5
            d0 dc d0 c2 d0 21 d4 22 d4 23 d4 24 d4 25 d4 c9
            d0 ca d0 5a d4 2b d1 28 d1 2c d1 75 d4 27 d4 28
            d4 29 d4 2a d4 1a d4 1a d4 1a d4 b7 d0 b8 d0 b9
            d0 ba d0 bb d0 bc d0 bd d0 be d0 bf d0 c0 d0 c1
            d0 c2 d0 c3 d0 c4 d0
            """,
        )

        /** MS-RDPEGFX 4.1.1.4: a 7x15 glyph of one band, stored at glyph index 120. */
        val EXAMPLE_4 = hex(
            """
            01 0b 78 00 00 00 00 00 46 00 00 00 00 00 00 00
            00 00 06 00 00 00 0e 00 00 00 00 00 0f ff ff ff
            ff ff ff ff ff ff b6 ff ff ff ff ff ff ff ff ff
            b6 66 ff ff ff ff ff ff ff b6 66 db 90 3a ff ff
            b6 ff ff ff ff ff ff ff ff ff 46 91 47 91 48 91
            49 91 4a 91 1b 91
            """,
        )
    }
}
