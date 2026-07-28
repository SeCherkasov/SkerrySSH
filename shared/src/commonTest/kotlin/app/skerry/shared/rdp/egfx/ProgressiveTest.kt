package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpRect
import app.skerry.shared.rdp.RdpWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The progressive codec.
 *
 * The tests are built around one property that makes the codec checkable by hand: a tile whose
 * detail bands are all zero and whose low-pass band is constant reconstructs to a flat colour. That
 * lets a stream be written here with no encoder at all — an empty entropy payload is a legitimate
 * "all zero" tile — and it still exercises parsing, the extrapolated wavelet transform, the colour
 * transform, the clipping against the region and the tile's place on the surface.
 */
class ProgressiveTest {

    private val surface = GraphicsSurface(id = 1, width = 128, height = 128)
    private val codec = Progressive()

    @Test
    fun `a tile of zero coefficients is mid grey`() {
        val stream = frame(region(tiles = firstTile(xIdx = 0, yIdx = 0)))

        val damaged = codec.decode(stream, surface, DESTINATION)

        assertEquals(GREY, surface.pixels[0])
        assertEquals(GREY, surface.pixels[63 * 128 + 63])
        assertEquals(0, surface.pixels[64], "the tile painted past its own 64 columns")
        assertEquals(listOf(RdpRect(0, 0, 64, 64)), damaged)
    }

    @Test
    fun `a tile is painted at the position its indices name`() {
        val stream = frame(region(rects = listOf(RdpRect(0, 0, 128, 128)), tiles = firstTile(xIdx = 1, yIdx = 1)))

        codec.decode(stream, surface, DESTINATION)

        assertEquals(GREY, surface.pixels[64 * 128 + 64])
        assertEquals(0, surface.pixels[63 * 128 + 63], "the tile painted outside its cell")
    }

    @Test
    fun `the region's rectangles clip what a tile may paint`() {
        val stream = frame(region(rects = listOf(RdpRect(0, 0, 10, 10)), tiles = firstTile(xIdx = 0, yIdx = 0)))

        val damaged = codec.decode(stream, surface, DESTINATION)

        assertEquals(GREY, surface.pixels[9 * 128 + 9])
        assertEquals(0, surface.pixels[10 * 128 + 10], "the tile painted outside the region")
        assertEquals(listOf(RdpRect(0, 0, 10, 10)), damaged)
    }

    @Test
    fun `the destination rectangle offsets both the region and its tiles`() {
        val destination = RdpRect(64, 0, 64, 64)
        val stream = frame(region(rects = listOf(RdpRect(0, 0, 64, 64)), tiles = firstTile(xIdx = 0, yIdx = 0)))

        codec.decode(stream, surface, destination)

        assertEquals(GREY, surface.pixels[64])
        assertEquals(0, surface.pixels[0], "the tile ignored the destination offset")
    }

    @Test
    fun `an upgrade pass adds the bits it carries to the tile already decoded`() {
        // Two progressive qualities: the first pass is coded one bit coarser than the second, so the
        // upgrade carries exactly one bit per coefficient. The low-pass band reads those bits from
        // the raw stream — all ones here — and every other band from the run-length stream, which is
        // empty and therefore says "everything stays zero".
        val first = frame(region(tiles = firstTile(xIdx = 0, yIdx = 0, quality = 0)))
        codec.decode(first, surface, DESTINATION)
        assertEquals(GREY, surface.pixels[0])

        val upgrade = frame(region(tiles = upgradeTile(xIdx = 0, yIdx = 0, quality = 1)))
        val damaged = codec.decode(upgrade, surface, DESTINATION)

        // Each low-pass coefficient gained 1 << 5, which is one step of luma on the screen.
        assertEquals(0xFF818181.toInt(), surface.pixels[0])
        assertEquals(0xFF818181.toInt(), surface.pixels[63 * 128 + 63])
        assertEquals(listOf(RdpRect(0, 0, 64, 64)), damaged)
    }

    @Test
    fun `an upgrade for a tile that was never sent is dropped`() {
        val stream = frame(region(tiles = upgradeTile(xIdx = 0, yIdx = 0, quality = 1)))

        val damaged = codec.decode(stream, surface, DESTINATION)

        assertTrue(damaged.isEmpty())
        assertEquals(0, surface.pixels[0])
    }

    @Test
    fun `a difference tile is an ordinary first pass on a surface nothing has been drawn on`() {
        // Both ends open a surface with zero coefficients, so a first pass that carries
        // RFX_TILE_DIFFERENCE is not evidence that anything was lost. Refusing one because the tile
        // has no earlier pass — the obvious guard against a difference landing on state that was
        // thrown away — would drop the opening frame of every surface a server encodes this way.
        val stream = frame(region(tiles = firstTile(xIdx = 0, yIdx = 0, difference = true)))

        val damaged = codec.decode(stream, surface, DESTINATION)

        assertEquals(GREY, surface.pixels[0])
        assertEquals(listOf(RdpRect(0, 0, 64, 64)), damaged)
    }

    @Test
    fun `a difference tile adds to the coefficients the tile already holds`() {
        // Why losing tile state is visible rather than merely wasteful: a tile whose content has
        // not changed arrives as a difference of zero, and zero coefficients are mid grey. Held
        // state reconstructs the picture; dropped state paints a grey square the server, having
        // nothing new to say about that tile, never paints over.
        codec.decode(frame(region(tiles = firstTile(xIdx = 0, yIdx = 0, quality = 0))), surface, DESTINATION)
        codec.decode(frame(region(tiles = upgradeTile(xIdx = 0, yIdx = 0, quality = 1))), surface, DESTINATION)
        assertEquals(0xFF818181.toInt(), surface.pixels[0])

        val unchanged = frame(region(tiles = firstTile(xIdx = 0, yIdx = 0, difference = true)))
        codec.decode(unchanged, surface, DESTINATION)

        assertEquals(0xFF818181.toInt(), surface.pixels[0], "the tile fell back to zero coefficients")
    }

    @Test
    fun `a tile outside the surface's grid is dropped rather than painted somewhere else`() {
        val stream = frame(region(rects = listOf(RdpRect(0, 0, 128, 128)), tiles = firstTile(xIdx = 40, yIdx = 40)))

        val damaged = codec.decode(stream, surface, DESTINATION)

        assertTrue(damaged.isEmpty())
        assertTrue(surface.pixels.all { it == 0 })
    }

    @Test
    fun `state is dropped with the surface it belonged to`() {
        codec.decode(frame(region(tiles = firstTile(xIdx = 0, yIdx = 0, quality = 0))), surface, DESTINATION)
        codec.forgetSurface(surface.id)

        val damaged = codec.decode(frame(region(tiles = upgradeTile(0, 0, quality = 1))), surface, DESTINATION)

        assertTrue(damaged.isEmpty(), "an upgrade was applied to a tile whose state was dropped")
    }

    @Test
    fun `a surface rebuilt at another size does not inherit the tiles of the old one`() {
        // What a resolution change looks like from here: the server drops the surface and creates
        // one of the new size, and may well hand it the same id. Its tile state belongs to the old
        // desktop — a grid of a different width, and coefficients from a picture that is gone — so
        // refining it would paint the previous resolution into the new frame.
        codec.decode(frame(region(tiles = firstTile(xIdx = 0, yIdx = 0, quality = 0))), surface, DESTINATION)

        val resized = GraphicsSurface(id = surface.id, width = 256, height = 256)
        val damaged = codec.decode(frame(region(tiles = upgradeTile(0, 0, quality = 1))), resized, DESTINATION)

        assertTrue(damaged.isEmpty(), "an upgrade refined a tile decoded at the old size")
        assertTrue(resized.pixels.all { it == 0 })
    }

    @Test
    fun `the extrapolated layout keeps the low-pass image at the end of the buffer`() {
        val buffer = IntArray(Progressive.TILE_COEFFICIENTS)
        for (index in 4015 until Progressive.TILE_COEFFICIENTS) buffer[index] = 64

        ProgressiveDwt.inverse(buffer)

        val uneven = buffer.indices.firstOrNull { buffer[it] != 64 }
        assertEquals(null, uneven, "sample $uneven is not flat")
    }

    @Test
    fun `the extrapolated band at offset zero is the finest horizontal detail`() {
        val buffer = IntArray(Progressive.TILE_COEFFICIENTS)
        buffer[0] = 40

        ProgressiveDwt.inverse(buffer)

        val changed = buffer.indices.filter { buffer[it] != 0 }
        assertTrue(changed.isNotEmpty(), "the band had no effect at all")
        assertTrue(changed.all { it / 64 < 2 && it % 64 < 4 }, "detail spread past the corner: $changed")
    }

    @Test
    fun `an unknown block type is refused`() {
        val stream = block(0xDEAD, ByteArray(4))

        assertFailsWith<RdpProtocolException> { codec.decode(stream, surface, DESTINATION) }
    }

    @Test
    fun `a block claiming more bytes than the stream holds is refused`() {
        val stream = RdpWriter(8).u16le(0xCCC4).u32le(9999).toByteArray()

        assertFailsWith<RdpProtocolException> { codec.decode(stream, surface, DESTINATION) }
    }

    @Test
    fun `a region whose tile data runs past the block is refused`() {
        val body = RdpWriter(32).apply {
            u8(64) // tileSize
            u16le(1) // numRects
            u8(1) // numQuant
            u8(0) // numProgQuant
            u8(1) // flags
            u16le(1) // numTiles
            u32le(9999) // tileDataSize
            u16le(0).u16le(0).u16le(64).u16le(64)
            bytes(QUANT_SIX)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { codec.decode(block(0xCCC4, body), surface, DESTINATION) }
    }

    // ---- stream builders ----

    private fun block(blockType: Int, body: ByteArray): ByteArray =
        RdpWriter(body.size + 6).u16le(blockType).u32le(body.size + 6).bytes(body).toByteArray()

    /** A frame around one region, as a server sends it: context, frame begin, region, frame end. */
    private fun frame(region: ByteArray): ByteArray =
        block(0xCCC3, RdpWriter(4).u8(0).u16le(64).u8(1).toByteArray()) + // context, sub-band diffing
            block(0xCCC1, RdpWriter(6).u32le(1).u16le(1).toByteArray()) + // frame begin
            region +
            block(0xCCC2, ByteArray(0)) // frame end

    private fun region(
        rects: List<RdpRect> = listOf(RdpRect(0, 0, 64, 64)),
        tiles: ByteArray,
    ): ByteArray {
        val body = RdpWriter(tiles.size + 64).apply {
            u8(64) // tileSize
            u16le(rects.size)
            u8(1) // one quantization set
            u8(2) // two progressive qualities: one bit apart
            u8(1) // RFX_DWT_REDUCE_EXTRAPOLATE
            u16le(1)
            u32le(tiles.size)
            for (rect in rects) {
                u16le(rect.x).u16le(rect.y).u16le(rect.width).u16le(rect.height)
            }
            bytes(QUANT_SIX)
            u8(0).bytes(QUANT_ONE).bytes(QUANT_ONE).bytes(QUANT_ONE) // quality 0: a bit coarser
            u8(1).bytes(QUANT_ZERO).bytes(QUANT_ZERO).bytes(QUANT_ZERO) // quality 1: full precision
            bytes(tiles)
        }.toByteArray()
        return block(0xCCC4, body)
    }

    /** A first-pass tile with empty entropy payloads, which means every coefficient is zero. */
    private fun firstTile(
        xIdx: Int,
        yIdx: Int,
        quality: Int = FULL_QUALITY,
        difference: Boolean = false,
    ): ByteArray {
        val body = RdpWriter(24).apply {
            u8(0).u8(0).u8(0) // quantIdxY, quantIdxCb, quantIdxCr
            u16le(xIdx)
            u16le(yIdx)
            u8(if (difference) 0x01 else 0x00) // RFX_TILE_DIFFERENCE
            if (quality != FULL_QUALITY) u8(quality)
            u16le(0).u16le(0).u16le(0) // yLen, cbLen, crLen
            u16le(0) // tailLen
        }.toByteArray()
        return block(if (quality == FULL_QUALITY) 0xCCC5 else 0xCCC6, body)
    }

    /**
     * An upgrade tile whose raw stream is all ones and whose sign stream is empty: the low-pass
     * band gains one bit everywhere, the detail bands stay where they are.
     */
    private fun upgradeTile(xIdx: Int, yIdx: Int, quality: Int): ByteArray {
        val raw = ByteArray(LOW_PASS_RAW_BYTES) { 0xFF.toByte() }
        val body = RdpWriter(raw.size + 24).apply {
            u8(0).u8(0).u8(0)
            u16le(xIdx)
            u16le(yIdx)
            u8(quality)
            u16le(0).u16le(raw.size) // ySrlLen, yRawLen
            u16le(0).u16le(0) // cbSrlLen, cbRawLen
            u16le(0).u16le(0) // crSrlLen, crRawLen
            bytes(raw)
        }.toByteArray()
        return block(0xCCC7, body)
    }

    private companion object {
        val DESTINATION = RdpRect(0, 0, 64, 64)

        /** Y, Cb and Cr all zero: the samples are centred, so nothing decoded is mid grey. */
        val GREY = 0xFF808080.toInt()

        const val FULL_QUALITY = 0xFF

        /** The low-pass band of the extrapolated layout holds 81 coefficients. */
        const val LOW_PASS_RAW_BYTES = 11

        val QUANT_SIX = ByteArray(5) { 0x66 }
        val QUANT_ONE = ByteArray(5) { 0x11 }
        val QUANT_ZERO = ByteArray(5)
    }
}
