package app.skerry.shared.rdp.rfx

import app.skerry.shared.rdp.RdpProtocolException
import app.skerry.shared.rdp.RdpWriter
import app.skerry.shared.rdp.hex
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RemoteFX, checked where it can be checked without a server.
 *
 * Two kinds of test here. The entropy coder is pinned by bit streams worked out by hand from the
 * specification, because a decoder checked only against an encoder written from the same
 * misunderstanding proves nothing. The transform is checked both by round trip and — more
 * importantly — against the *layout* the wire uses: which sub-band sits where in the coefficient
 * buffer, and which quantization nibble scales it.
 */
class RemoteFxTest {

    // ---- entropy coding ----

    @Test
    fun `RLGR reads a run terminator as a sign bit and a magnitude`() {
        // 1 0 001 : no run doublings, a one-bit run remainder of 0, a positive sign, and the
        // Golomb-Rice word 0-1 which codes 1, so the coefficient is 2.
        val decoded = Rlgr.decode(byteArrayOf(0x88.toByte()), count = 1, mode = Rlgr.Mode.Rlgr1)

        assertContentEquals(intArrayOf(2), decoded)
    }

    @Test
    fun `RLGR counts a run of zeroes before the value that ends it`() {
        // 01 0 0 01 : one doubling (k is 1, so two zeroes), remainder 0, positive, magnitude 2.
        val decoded = Rlgr.decode(byteArrayOf(0x44), count = 3, mode = Rlgr.Mode.Rlgr1)

        assertContentEquals(intArrayOf(0, 0, 2), decoded)
    }

    @Test
    fun `RLGR falls into Golomb-Rice mode once a value has dropped k to zero`() {
        // The same stream as above, read for one coefficient more: the value that ended the run
        // leaves k at zero, so the padding bits are read as a Golomb-Rice zero rather than a run.
        val decoded = Rlgr.decode(byteArrayOf(0x88.toByte()), count = 2, mode = Rlgr.Mode.Rlgr1)

        assertContentEquals(intArrayOf(2, 0), decoded)
    }

    @Test
    fun `RLGR reads a Golomb-Rice magnitude however long its prefix is`() {
        // 1 0 0 0 0 : the run terminator, a remainder of 0, a positive sign and the Golomb-Rice
        // word 0-0, which codes 1 and drops k to zero. Golomb-Rice mode then reads seventy 1 bits
        // before the terminating 0, and with the remainder width down to zero that prefix is the
        // whole magnitude: 70, which folds to +35. A prefix is not a run here — capping its length
        // rejects exactly the large coefficients a full-quality tile is made of.
        val stream = hex("87 ff ff ff ff ff ff ff ff e0")

        val decoded = Rlgr.decode(stream, count = 4, mode = Rlgr.Mode.Rlgr1)

        assertContentEquals(intArrayOf(1, 35, 0, 0), decoded)
    }

    @Test
    fun `RLGR decoding into a dirty buffer leaves no stale coefficients`() {
        // F-05 reuses one buffer across tiles. A stream that ends early must leave zeroes behind
        // it — the previous tile's coefficients bleeding through would paint ghosts of it.
        val out = IntArray(3) { 99 }

        Rlgr.decode(byteArrayOf(0x88.toByte()), out, Rlgr.Mode.Rlgr1)

        assertContentEquals(intArrayOf(2, 0, 0), out)
    }

    @Test
    fun `RLGR into a buffer decodes the same values as the allocating form`() {
        val out = IntArray(3)

        Rlgr.decode(byteArrayOf(0x44), out, Rlgr.Mode.Rlgr1)

        assertContentEquals(Rlgr.decode(byteArrayOf(0x44), count = 3, mode = Rlgr.Mode.Rlgr1), out)
    }

    @Test
    fun `RLGR stops at the end of its input instead of inventing coefficients`() {
        val decoded = Rlgr.decode(ByteArray(0), count = RfxDwt.TILE_COEFFICIENTS, mode = Rlgr.Mode.Rlgr1)

        // An empty plane is a legitimate "nothing changed here": all zeroes, no exception.
        assertEquals(RfxDwt.TILE_COEFFICIENTS, decoded.size)
        assertTrue(decoded.all { it == 0 })
    }

    // ---- coefficient layout ----

    @Test
    fun `the low-pass band sits at the end of the coefficient buffer`() {
        // Only the 8x8 low-pass image is set, to a constant: the tile must come out flat, because
        // every detail band is zero. Were the bands ordered the other way round, this buffer would
        // describe fine detail instead and the tile would be anything but flat.
        val coefficients = IntArray(RfxDwt.TILE_COEFFICIENTS)
        for (index in RfxDwt.LOW_PASS_OFFSET until RfxDwt.LOW_PASS_OFFSET + RfxDwt.LOW_PASS_SIZE) {
            coefficients[index] = 100
        }

        RfxDwt.inverseTransform(coefficients)

        val uneven = coefficients.indices.firstOrNull { coefficients[it] != 100 }
        assertEquals(null, uneven, "sample $uneven is not flat")
    }

    @Test
    fun `the band at offset zero is the finest horizontal detail`() {
        // HL1 is the level-1 horizontal detail band, so one of its coefficients disturbs a handful
        // of samples in the tile's corner. A coefficient of the low-pass band — which is what sits
        // here if the buffer is read the other way round — would instead tint an eighth of the tile.
        val coefficients = IntArray(RfxDwt.TILE_COEFFICIENTS)
        coefficients[0] = 40

        RfxDwt.inverseTransform(coefficients)

        val changed = coefficients.indices.filter { coefficients[it] != 0 }
        assertTrue(changed.isNotEmpty(), "the band had no effect at all")
        assertTrue(changed.all { it / RfxDwt.TILE_SIZE < 2 }, "detail reached row 2: $changed")
        assertTrue(changed.all { it % RfxDwt.TILE_SIZE < 4 }, "detail reached column 4: $changed")
        // Neighbouring samples swing in opposite directions: that is what "detail" means here.
        assertTrue(coefficients[0] < 0 && coefficients[1] > 0, "the corner is not an edge")
    }

    @Test
    fun `the low-pass band is summed before anything else reads it`() {
        val coefficients = IntArray(RfxDwt.TILE_COEFFICIENTS)
        coefficients[RfxDwt.LOW_PASS_OFFSET] = 10
        coefficients[RfxDwt.LOW_PASS_OFFSET + 1] = 5
        coefficients[RfxDwt.LOW_PASS_OFFSET + 2] = -3

        RfxDwt.differentialDecodeLowPass(coefficients)

        assertEquals(10, coefficients[RfxDwt.LOW_PASS_OFFSET])
        assertEquals(15, coefficients[RfxDwt.LOW_PASS_OFFSET + 1])
        assertEquals(12, coefficients[RfxDwt.LOW_PASS_OFFSET + 2])
    }

    @Test
    fun `each quantization nibble scales the band the spec assigns it`() {
        // TS_RFX_CODEC_QUANT packs LL3, LH3, HL3, HH3, LH2, HL2, HH2, LH1, HL1, HH1 — two per byte,
        // low nibble first. Only HL1 is given a factor here, and HL1 is the band at offset 0.
        val coefficients = IntArray(RfxDwt.TILE_COEFFICIENTS) { 1 }
        val quants = byteArrayOf(0x11, 0x11, 0x11, 0x11, 0x13)

        RfxDwt.dequantize(coefficients, quants)

        assertEquals(4, coefficients[0], "HL1 was not scaled")
        assertEquals(4, coefficients[1023], "HL1 was not scaled to its end")
        assertEquals(1, coefficients[1024], "LH1 was scaled by HL1's factor")
        assertEquals(1, coefficients[RfxDwt.LOW_PASS_OFFSET], "the low-pass band was scaled")
    }

    // ---- transform ----

    @Test
    fun `a flat tile survives the round trip exactly`() {
        val flat = IntArray(RfxDwt.TILE_COEFFICIENTS) { 64 }

        val restored = RfxForwardTransform.transform(flat).also { RfxDwt.inverseTransform(it) }

        assertContentEquals(flat, restored)
    }

    @Test
    fun `the inverse transform returns a picture to within the codec's own rounding`() {
        // A picture with edges in both directions: flat areas alone would hide a filter error.
        val original = IntArray(RfxDwt.TILE_COEFFICIENTS) { index ->
            val x = index % RfxDwt.TILE_SIZE
            val y = index / RfxDwt.TILE_SIZE
            when {
                x < 8 -> -128
                y > 40 -> 100
                (x / 4 + y / 4) % 2 == 0 -> 40
                else -> -20
            }
        }

        val restored = RfxForwardTransform.transform(original).also { RfxDwt.inverseTransform(it) }

        // The detail bands are stored halved, so each level loses a bit and the coarser levels'
        // losses are doubled on the way back up. The error lives on the edges; flat areas are exact.
        val worst = original.indices.maxOf { abs(original[it] - restored[it]) }
        assertTrue(worst <= ROUND_TRIP_TOLERANCE, "round trip is off by $worst")
    }

    // ---- colour ----

    @Test
    fun `zero coefficients are mid grey, not black`() {
        // The samples are 11.5 fixed-point and centred on zero, so an untouched plane is 128.
        assertEquals(0xFF808080.toInt(), RfxColor.ycbcrToArgb(0, 0, 0))
    }

    @Test
    fun `luma is read in the fixed-point scale the transform leaves behind`() {
        assertEquals(0xFFFFFFFF.toInt(), RfxColor.ycbcrToArgb((255 - 128) shl 5, 0, 0))
        assertEquals(0xFF000000.toInt(), RfxColor.ycbcrToArgb(-128 shl 5, 0, 0))
    }

    @Test
    fun `chroma moves red and blue in opposite directions`() {
        val blueish = RfxColor.ycbcrToArgb(0, 64 shl 5, 0)

        assertEquals(0xFF806AF1.toInt(), blueish)
    }

    // ---- stream framing ----

    @Test
    fun `a tile set with an unsupported tile size is refused`() {
        val stream = rfxStream(tileSize = 32, quantCount = 1, tiles = 0)

        assertFailsWith<RdpProtocolException> { RemoteFx().decode(stream, 64, 64) }
    }

    @Test
    fun `a tile referencing a quantization set that was never sent is refused`() {
        val tile = RdpWriter(32).apply {
            u16le(0xCAC3) // CBT_TILE
            u32le(6 + 13)
            u8(5) // quantIdxY, beyond the single set below
            u8(0).u8(0)
            u16le(0).u16le(0)
            u16le(0).u16le(0).u16le(0)
        }.toByteArray()
        val stream = rfxStream(tileSize = 64, quantCount = 1, tiles = 1, tileBytes = tile)

        assertFailsWith<RdpProtocolException> { RemoteFx().decode(stream, 64, 64) }
    }

    @Test
    fun `a block claiming more bytes than the stream holds is refused`() {
        val stream = RdpWriter(16).apply {
            u16le(0xCCC7) // WBT_EXTENSION
            u32le(10_000)
        }.toByteArray()

        assertFailsWith<RdpProtocolException> { RemoteFx().decode(stream, 64, 64) }
    }

    @Test
    fun `framing blocks carry no pixels and are skipped`() {
        val stream = RdpWriter(32).apply {
            u16le(0xCCC0) // WBT_SYNC
            u32le(6 + 6)
            u32le(0xCACCACCA.toInt())
            u16le(0x0100)
            u16le(0xCCC4) // WBT_FRAME_BEGIN
            u32le(6 + 8)
            u8(1).u8(0)
            u32le(7) // frameIdx
            u16le(1) // numRegions
        }.toByteArray()

        val pixels = RemoteFx().decode(stream, 2, 2)

        assertContentEquals(IntArray(4), pixels)
    }

    /** A minimal RemoteFX stream containing one tile set. */
    private fun rfxStream(tileSize: Int, quantCount: Int, tiles: Int, tileBytes: ByteArray = ByteArray(0)): ByteArray {
        val body = RdpWriter(64 + tileBytes.size).apply {
            u8(1) // codecId
            u8(0) // channelId
            u16le(0xCAC2) // CBT_TILESET
            u16le(0) // idx
            u16le(0) // properties: RLGR1
            u8(quantCount)
            u8(tileSize)
            u16le(tiles)
            u32le(tileBytes.size)
            repeat(quantCount) { bytes(byteArrayOf(0x11, 0x11, 0x11, 0x11, 0x11)) }
            bytes(tileBytes)
        }.toByteArray()
        return RdpWriter(body.size + 6).apply {
            u16le(0xCCC7) // WBT_EXTENSION
            u32le(body.size + 6)
            bytes(body)
        }.toByteArray()
    }

    private companion object {
        const val ROUND_TRIP_TOLERANCE = 12
    }
}
