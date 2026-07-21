package app.skerry.shared.vnc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class TightBytes(private val data: ByteArray) : VncSource {
    private var pos = 0
    override suspend fun readFully(dst: ByteArray, offset: Int, len: Int) {
        data.copyInto(dst, offset, pos, pos + len)
        pos += len
    }
}

private fun tp(r: Int, g: Int, b: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte()) // TPIXEL R,G,B
private const val R = 0xFFFF0000.toInt()
private const val G = 0xFF00FF00.toInt()
private const val B = 0xFF0000FF.toInt()

private fun join(vararg parts: ByteArray): ByteArray {
    val out = ArrayList<Byte>()
    parts.forEach { p -> p.forEach { out.add(it) } }
    return out.toByteArray()
}

private val noStream: (Int) -> Inflater = { error("zlib stream must not be used in a raw-path test") }
private val noReset: (Int) -> Unit = {}

class TightDecoderTest {

    @Test
    fun fill_paints_solid() = runTest {
        val data = join(byteArrayOf(0x80.toByte()), tp(0xFF, 0, 0)) // mode 8 = fill, red
        val fb = VncFramebuffer(2, 2)
        decodeTight(TightBytes(data), fb, VncRect(0, 0, 2, 2), noStream, noReset, null)
        assertEquals(R, fb.pixels[0])
        assertEquals(R, fb.pixels[3])
    }

    @Test
    fun basic_copy_raw_below_threshold() = runTest {
        // 1x2 copy = 6 bytes < 12 → raw. mode 0 (basic, no filter flag → copy, stream 0).
        val data = join(byteArrayOf(0x00), tp(0xFF, 0, 0), tp(0, 0xFF, 0))
        val fb = VncFramebuffer(1, 2)
        decodeTight(TightBytes(data), fb, VncRect(0, 0, 1, 2), noStream, noReset, null)
        assertEquals(R, fb.pixels[0])
        assertEquals(G, fb.pixels[1])
    }

    @Test
    fun basic_palette_two_colours_raw() = runTest {
        // 4x2 palette, 1 bit/pixel, rowSize 1 → 2 bytes raw. mode 4 (filter flag), filterId 1 (palette).
        val data = join(
            byteArrayOf(0x40),       // control: basic, explicit filter, stream 0
            byteArrayOf(1),          // filter id = palette
            byteArrayOf(1),          // numColors-1 = 1 → 2 colours
            tp(0xFF, 0, 0), tp(0, 0xFF, 0), // palette: red, green
            byteArrayOf(0x50.toByte()),     // row0: 0,1,0,1
            byteArrayOf(0xA0.toByte()),     // row1: 1,0,1,0
        )
        val fb = VncFramebuffer(4, 2)
        decodeTight(TightBytes(data), fb, VncRect(0, 0, 4, 2), noStream, noReset, null)
        assertEquals(R, fb.pixels[0]) // (0,0)
        assertEquals(G, fb.pixels[1]) // (1,0)
        assertEquals(G, fb.pixels[4]) // (0,1)
        assertEquals(R, fb.pixels[5]) // (1,1)
    }

    @Test
    fun basic_gradient_raw() = runTest {
        // 1x3 gradient = 9 bytes < 12 → raw. First pixel residual is the value; next rows predict from 'up'.
        val data = join(
            byteArrayOf(0x40), byteArrayOf(2), // basic + filter, filterId 2 (gradient)
            byteArrayOf(10, 20, 30),           // pixel (0,0)
            byteArrayOf(0, 0, 0),              // pixel (0,1): pred = up → same
            byteArrayOf(0, 0, 0),              // pixel (0,2): pred = up → same
        )
        val fb = VncFramebuffer(1, 3)
        decodeTight(TightBytes(data), fb, VncRect(0, 0, 1, 3), noStream, noReset, null)
        val expected = (0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30
        assertEquals(expected, fb.pixels[0])
        assertEquals(expected, fb.pixels[1])
        assertEquals(expected, fb.pixels[2])
    }

    @Test
    fun jpeg_uses_the_image_decoder() = runTest {
        val decoded = DecodedImage(intArrayOf(R, G, B, 0xFFFFFFFF.toInt()), 2, 2)
        val decoder = VncImageDecoder { decoded }
        val data = join(byteArrayOf(0x90.toByte()), byteArrayOf(4), byteArrayOf(1, 2, 3, 4)) // mode 9, len 4, dummy jpeg
        val fb = VncFramebuffer(2, 2)
        decodeTight(TightBytes(data), fb, VncRect(0, 0, 2, 2), noStream, noReset, decoder)
        assertEquals(R, fb.pixels[0])
        assertEquals(G, fb.pixels[1])
        assertEquals(B, fb.pixels[2])
        assertEquals(0xFFFFFFFF.toInt(), fb.pixels[3])
    }
}
