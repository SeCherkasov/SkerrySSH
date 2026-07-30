package app.skerry.shared.rdp.egfx

import app.skerry.shared.rdp.RdpRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The H.264 codecs over a decoder that hands over pictures the test wrote: what reaches the surface,
 * which parts of it, and how the two frames of a 4:4:4 message combine into full-resolution chroma.
 */
class AvcCodecTest {

    private val decoders = FakeH264Decoders()
    private val codec = AvcCodec(decoders)

    @Test
    fun `a 4 to 2 to 0 frame paints the regions the server declared and nothing else`() {
        val surface = GraphicsSurface(1, 8, 8)
        decoders.frames += flatFrame(8, 8, luma = 255)

        val touched = codec.decodeAvc420(
            avc420Message(listOf(RdpRect(0, 0, 4, 4)), byteArrayOf(1)),
            surface,
        )

        assertEquals(listOf(RdpRect(0, 0, 4, 4)), touched)
        assertEquals(WHITE, surface.pixels[0])
        assertEquals(WHITE, surface.pixels[3 * 8 + 3], "the last pixel of the region")
        assertEquals(0, surface.pixels[4], "a pixel outside the region was painted")
        assertEquals(0, surface.pixels[4 * 8], "a pixel below the region was painted")
    }

    @Test
    fun `chroma is upsampled from the quarter-resolution planes`() {
        val surface = GraphicsSurface(1, 4, 4)
        val frame = flatFrame(4, 4, luma = 128)
        // One chroma sample covers a 2x2 block of pixels; give the second block a different one.
        frame.u[1] = 200.toByte()
        frame.v[1] = 60.toByte()
        decoders.frames += frame

        codec.decodeAvc420(avc420Message(listOf(RdpRect(0, 0, 4, 4)), byteArrayOf(1)), surface)

        assertEquals(H264Color.yuvToArgb(128, 128, 128), surface.pixels[0])
        assertEquals(H264Color.yuvToArgb(128, 200, 60), surface.pixels[2], "the second chroma sample")
        assertEquals(H264Color.yuvToArgb(128, 200, 60), surface.pixels[3], "its second column")
        assertEquals(H264Color.yuvToArgb(128, 200, 60), surface.pixels[4 + 2], "its second row")
        assertEquals(H264Color.yuvToArgb(128, 128, 128), surface.pixels[4 + 1])
    }

    @Test
    fun `a region reaching past the surface is clipped instead of refused`() {
        val surface = GraphicsSurface(1, 8, 8)
        decoders.frames += flatFrame(16, 16, luma = 255)

        val touched = codec.decodeAvc420(
            avc420Message(listOf(RdpRect(4, 4, 16, 16)), byteArrayOf(1)),
            surface,
        )

        assertEquals(listOf(RdpRect(4, 4, 4, 4)), touched)
        assertEquals(WHITE, surface.pixels[7 * 8 + 7])
    }

    @Test
    fun `a region outside the picture the decoder produced is dropped`() {
        val surface = GraphicsSurface(1, 64, 64)
        // A server may send a frame smaller than the surface; the region list still names the surface.
        decoders.frames += flatFrame(16, 16, luma = 255)

        val touched = codec.decodeAvc420(
            avc420Message(listOf(RdpRect(32, 32, 16, 16)), byteArrayOf(1)),
            surface,
        )

        assertTrue(touched.isEmpty(), "a region outside the decoded picture reached the surface")
        assertEquals(0, surface.pixels[32 * 64 + 32])
    }

    @Test
    fun `a region wider than the picture is cut at its edge, not read past its rows`() {
        val surface = GraphicsSurface(1, 64, 64)
        decoders.frames += flatFrame(16, 16, luma = 255)

        val touched = codec.decodeAvc420(
            avc420Message(listOf(RdpRect(0, 0, 64, 16)), byteArrayOf(1)),
            surface,
        )

        assertEquals(listOf(RdpRect(0, 0, 16, 16)), touched)
        assertEquals(WHITE, surface.pixels[15], "the last column of the picture")
        assertEquals(0, surface.pixels[16], "a column the picture does not reach was painted")
    }

    @Test
    fun `nothing is painted when the decoder produced no picture`() {
        val surface = GraphicsSurface(1, 8, 8)

        val touched = codec.decodeAvc420(
            avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)),
            surface,
        )

        assertTrue(touched.isEmpty())
        assertEquals(0, surface.pixels[0])
    }

    @Test
    fun `a picture survives the split into the two frames of a 4 to 4 to 4 message`() {
        val image = colourfulImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV1()

        codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(1)),
                second = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(2)),
            ),
            surface,
            version2 = false,
        )

        assertPicture(image, surface)
    }

    @Test
    fun `a picture survives the second chroma packing as well`() {
        val image = colourfulImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV2()

        codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(1)),
                second = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(2)),
            ),
            surface,
            version2 = true,
        )

        assertPicture(image, surface)
    }

    @Test
    fun `the odd chroma rows come from the auxiliary luma plane in blocks of eight`() {
        // Pinned directly rather than through the round trip: the two are written from the same
        // clause, and a mistake made in both directions would cancel out.
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += flatFrame(32, 32, luma = 128)
        val aux = flatFrame(32, 32, luma = 0, chroma = 0)
        aux.y.fill(200.toByte(), 0, 32) // the first row of the auxiliary luma plane
        decoders.frames += aux

        codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(1)),
                second = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(2)),
            ),
            surface,
            version2 = false,
        )

        assertEquals(
            H264Color.yuvToArgb(128, 200, 0),
            surface.pixels[32],
            "row 1 of U should come from row 0 of the auxiliary luma plane, and row 1 of V from row 8",
        )
        assertEquals(
            H264Color.yuvToArgb(128, 0, 0),
            surface.pixels[1],
            "the odd columns of an even row come from the auxiliary chroma planes",
        )
    }

    @Test
    fun `a chroma-only message paints over the luma of the message before it`() {
        val image = colourfulImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV1()
        codec.decodeAvc444(bothHalves(), surface, version2 = false)

        // Only the chroma changed, so the server sends that half alone. The luma it is painted over
        // is the one this surface already had; a decoder that dropped it would paint black.
        decoders.frames += image.auxFrameV1()
        val touched = codec.decodeAvc444(
            avc444Message(AVC444_CHROMA_ONLY, first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(3))),
            surface,
            version2 = false,
        )

        assertEquals(listOf(RdpRect(0, 0, 32, 32)), touched)
        assertPicture(image, surface)
    }

    @Test
    fun `a luma-only message paints the picture its own chroma describes`() {
        // The first message of a session may be luma-only, and then three chroma samples in four have
        // never been sent: what the picture looks like has to come out of that frame alone.
        val image = blockColourImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()

        val touched = codec.decodeAvc444(
            avc444Message(AVC444_LUMA_ONLY, first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(1))),
            surface,
            version2 = false,
        )

        assertEquals(listOf(RdpRect(0, 0, 32, 32)), touched)
        assertPicture(image, surface)
    }

    @Test
    fun `both halves of a message go through one decoder in the order the wire carries them`() {
        val surface = GraphicsSurface(1, 8, 8)
        decoders.frames += flatFrame(8, 8, luma = 128)
        decoders.frames += flatFrame(8, 8, luma = 128)

        codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(11)),
                second = avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(22)),
            ),
            surface,
            version2 = false,
        )

        val decoder = decoders.opened.single()
        assertEquals(listOf(listOf<Byte>(11), listOf<Byte>(22)), decoder.accessUnits.map { it.toList() })
    }

    @Test
    fun `a surface that goes away takes its decoder with it`() {
        val surface = GraphicsSurface(1, 8, 8)
        decoders.frames += flatFrame(8, 8, luma = 128)
        codec.decodeAvc420(avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)), surface)

        codec.forgetSurface(1)

        assertTrue(decoders.opened.single().closed, "the decoder outlived its surface")
        decoders.frames += flatFrame(8, 8, luma = 128)
        codec.decodeAvc420(avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)), surface)
        assertEquals(2, decoders.opened.size, "a forgotten surface did not open a new decoder")
    }

    @Test
    fun `a surface rebuilt at another size starts a new decoder`() {
        decoders.frames += flatFrame(8, 8, luma = 128)
        codec.decodeAvc420(
            avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)),
            GraphicsSurface(1, 8, 8),
        )

        decoders.frames += flatFrame(16, 16, luma = 128)
        codec.decodeAvc420(
            avc420Message(listOf(RdpRect(0, 0, 16, 16)), byteArrayOf(1)),
            GraphicsSurface(1, 16, 16),
        )

        assertEquals(2, decoders.opened.size, "the picture of a surface that no longer exists was decoded")
        assertTrue(decoders.opened.first().closed, "the decoder of the old geometry was left open")
        assertNotSame(decoders.opened.first(), decoders.opened.last())
    }

    @Test
    fun `a server cannot make this client hold a decoder per surface without end`() {
        // Nothing in the protocol bounds the number of surfaces, and a decoder is a process or a
        // hardware codec instance. The ones already open keep working; the rest get nothing.
        repeat(20) { index ->
            decoders.frames += flatFrame(8, 8, luma = 128)
            codec.decodeAvc420(
                avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)),
                GraphicsSurface(index, 8, 8),
            )
        }

        assertTrue(decoders.opened.size <= 8, "${decoders.opened.size} decoders were opened at once")
    }

    @Test
    fun `a platform that would not open a decoder ends the session instead of freezing the screen`() {
        // The client already told the server it could take H.264 and the server sends this surface no
        // other way, so there is nothing left to draw with and no way to renegotiate. Painting nothing
        // would leave a screen that never updates and a doomed open retried on every frame.
        val refusing = AvcCodec(object : H264DecoderFactory {
            override val available = true
            override fun open(width: Int, height: Int): H264Decoder? = null
        })

        assertFailsWith<IllegalStateException> {
            refusing.decodeAvc420(
                avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)),
                GraphicsSurface(1, 8, 8),
            )
        }
    }

    @Test
    fun `closing the codec gives back every decoder the session still holds`() {
        // A decoder is a process or one of a device's few hardware codecs, and a server has no reason
        // to delete the surface it drew the desktop on before the session ends.
        for (id in 1..3) {
            decoders.frames += flatFrame(8, 8, luma = 128)
            codec.decodeAvc420(avc420Message(listOf(RdpRect(0, 0, 8, 8)), byteArrayOf(1)), GraphicsSurface(id, 8, 8))
        }

        codec.close()

        assertEquals(3, decoders.opened.size)
        assertTrue(decoders.opened.all { it.closed }, "a decoder outlived the session")
    }

    @Test
    fun `a 4 to 4 to 4 message paints each region once, not once per half`() {
        // Both halves describe the same pixels. Painting after each would run the colour transform and
        // the blit twice over the whole frame, and the first pass would never be seen.
        val image = colourfulImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV1()

        val touched = codec.decodeAvc444(bothHalves(), surface, version2 = false)

        assertEquals(listOf(RdpRect(0, 0, 32, 32)), touched, "the region was reported once per half")
        assertPicture(image, surface)
    }

    @Test
    fun `every rectangle of a region list is painted`() {
        val surface = GraphicsSurface(1, 16, 16)
        decoders.frames += flatFrame(16, 16, luma = 255)

        val touched = codec.decodeAvc420(
            avc420Message(listOf(RdpRect(0, 0, 4, 4), RdpRect(8, 8, 4, 4)), byteArrayOf(1)),
            surface,
        )

        assertEquals(listOf(RdpRect(0, 0, 4, 4), RdpRect(8, 8, 4, 4)), touched)
        assertEquals(WHITE, surface.pixels[0], "the first rectangle")
        assertEquals(WHITE, surface.pixels[8 * 16 + 8], "the second rectangle")
        assertEquals(0, surface.pixels[4 * 16 + 4], "a pixel between them was painted")
    }

    @Test
    fun `a 4 to 4 to 4 region away from the origin is assembled at its own offset`() {
        val image = colourfulImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV1()
        val region = RdpRect(8, 16, 16, 16)

        val touched = codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(region), byteArrayOf(1)),
                second = avc420Message(listOf(region), byteArrayOf(2)),
            ),
            surface,
            version2 = false,
        )

        assertEquals(listOf(region), touched)
        assertPicture(image, surface, region)
        assertEquals(0, surface.pixels[0], "a pixel outside the region was painted")
    }

    @Test
    fun `an odd-sized 4 to 4 to 4 region is assembled without running off its edge`() {
        val image = colourfulImage(32, 32)
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV1()
        // Nothing on the wire says a region is even-sized, and the chroma of a 4:2:0 picture covers
        // pixels in pairs: the last row and column of an odd region have no partner to share with.
        val region = RdpRect(0, 0, 5, 7)

        val touched = codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(region), byteArrayOf(1)),
                second = avc420Message(listOf(region), byteArrayOf(2)),
            ),
            surface,
            version2 = false,
        )

        assertEquals(listOf(region), touched)
        assertEquals(0, surface.pixels[7 * 32], "the row below the region was painted")
    }

    @Test
    fun `the second packing works on a surface whose width is not a multiple of 32`() {
        // The V half of an auxiliary row starts at the width rounded up to 32, never past the picture:
        // a 48-wide surface is where those two answers differ.
        val image = colourfulImage(48, 16)
        val surface = GraphicsSurface(1, 48, 16)
        decoders.frames += image.mainFrame()
        decoders.frames += image.auxFrameV2()

        codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(RdpRect(0, 0, 48, 16)), byteArrayOf(1)),
                second = avc420Message(listOf(RdpRect(0, 0, 48, 16)), byteArrayOf(2)),
            ),
            surface,
            version2 = true,
        )

        assertPicture(image, surface)
    }

    @Test
    fun `the odd chroma columns of the second packing come from the halves of the auxiliary luma row`() {
        // Pinned directly, like the first packing's: the round trip alone would agree with a mistake
        // made in both directions.
        val surface = GraphicsSurface(1, 32, 32)
        decoders.frames += flatFrame(32, 32, luma = 128)
        val aux = flatFrame(32, 32, luma = 0, chroma = 0)
        aux.y.fill(200.toByte(), 0, 16) // the left half of row 0: U of every odd column
        aux.y.fill(60.toByte(), 16, 32) // its right half: V of the same columns
        aux.u[0] = 90.toByte() // the U plane's left half: U of the even columns of row 1
        decoders.frames += aux

        codec.decodeAvc444(
            avc444Message(
                AVC444_BOTH,
                first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(1)),
                second = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(2)),
            ),
            surface,
            version2 = true,
        )

        assertEquals(H264Color.yuvToArgb(128, 200, 60), surface.pixels[1], "an odd column of an even row")
        assertEquals(H264Color.yuvToArgb(128, 90, 0), surface.pixels[32], "an even column of an odd row")
    }

    // ---- helpers ----

    private fun bothHalves(): ByteArray = avc444Message(
        AVC444_BOTH,
        first = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(1)),
        second = avc420Message(listOf(RdpRect(0, 0, 32, 32)), byteArrayOf(2)),
    )

    /** A picture of one luma and one chroma value, at the stride a real decoder would pad to. */
    private fun flatFrame(width: Int, height: Int, luma: Int, chroma: Int = 128): YuvFrame {
        val stride = width + STRIDE_PADDING
        val chromaStride = (width + 1) / 2 + STRIDE_PADDING
        return YuvFrame(
            y = ByteArray(stride * height) { luma.toByte() },
            u = ByteArray(chromaStride * ((height + 1) / 2)) { chroma.toByte() },
            v = ByteArray(chromaStride * ((height + 1) / 2)) { chroma.toByte() },
            yStride = stride,
            chromaStride = chromaStride,
            width = width,
            height = height,
        )
    }

    /** Saturated colours that differ inside every 2×2 block, which is what 4:4:4 exists for. */
    private fun colourfulImage(width: Int, height: Int): Yuv444Image {
        val palette = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), WHITE)
        val image = Yuv444Image(width, height)
        for (row in 0 until height) {
            for (x in 0 until width) {
                image[x, row] = palette[(x + 2 * row) % palette.size]
            }
        }
        return image
    }

    /** Colours that a 4:2:0 picture can carry exactly: one per 2×2 block, so no sample is lost. */
    private fun blockColourImage(width: Int, height: Int): Yuv444Image {
        val palette = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), WHITE)
        val image = Yuv444Image(width, height)
        for (row in 0 until height) {
            for (x in 0 until width) {
                image[x, row] = palette[(x / 2 + row / 2) % palette.size]
            }
        }
        return image
    }

    private fun assertPicture(
        expected: Yuv444Image,
        surface: GraphicsSurface,
        within: RdpRect = RdpRect(0, 0, expected.width, expected.height),
    ) {
        for (row in within.y until within.y + within.height) {
            for (x in within.x until within.x + within.width) {
                val index = row * expected.width + x
                val want = H264Color.yuvToArgb(
                    expected.y[index].toInt() and 0xFF,
                    expected.u[index].toInt() and 0xFF,
                    expected.v[index].toInt() and 0xFF,
                )
                val got = surface.pixels[index]
                assertTrue(
                    channelsClose(want, got),
                    "pixel ($x, $row): expected ${want.toUInt().toString(16)}, " +
                        "got ${got.toUInt().toString(16)}",
                )
            }
        }
    }

    /**
     * The chroma of one pixel in four is not transmitted but recovered from the average of its block,
     * and the recovery is exact only up to the truncation of that average.
     */
    private fun channelsClose(expected: Int, actual: Int): Boolean =
        (0..2).all { shift ->
            val want = (expected shr (shift * 8)) and 0xFF
            val got = (actual shr (shift * 8)) and 0xFF
            abs(want - got) <= CHANNEL_TOLERANCE
        }

    private class FakeH264Decoders : H264DecoderFactory {
        override val available = true
        val opened = mutableListOf<FakeH264Decoder>()
        val frames = ArrayDeque<YuvFrame>()

        override fun open(width: Int, height: Int): H264Decoder =
            FakeH264Decoder(frames).also { opened += it }
    }

    private class FakeH264Decoder(private val frames: ArrayDeque<YuvFrame>) : H264Decoder {
        val accessUnits = mutableListOf<ByteArray>()
        var closed = false

        override fun decode(accessUnit: ByteArray): YuvFrame? {
            accessUnits += accessUnit
            return frames.removeFirstOrNull()
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Every real decoder pads its planes; a codec that assumes width == stride is wrong. */
        const val STRIDE_PADDING = 3

        const val CHANNEL_TOLERANCE = 10
    }
}
