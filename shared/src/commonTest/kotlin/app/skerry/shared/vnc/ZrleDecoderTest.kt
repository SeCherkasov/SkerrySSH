package app.skerry.shared.vnc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Source that yields a u32 zero length (no compressed bytes to read); the fake inflater supplies the tile stream. */
private class ZeroLengthSource : VncSource {
    private val zero = byteArrayOf(0, 0, 0, 0)
    private var pos = 0
    override suspend fun readFully(dst: ByteArray, offset: Int, len: Int) {
        zero.copyInto(dst, offset, pos, pos + len)
        pos += len
    }
}

/** Inflater that ignores its input and returns a fixed pre-inflated tile stream — isolates decode from zlib. */
private fun fakeInflaterYielding(inflated: ByteArray) = object : Inflater {
    override fun inflate(input: ByteArray) = inflated
    override fun close() {}
}

private fun cp(r: Int, g: Int, b: Int) = byteArrayOf(r.toByte(), g.toByte(), b.toByte()) // CPIXEL: R,G,B
private const val RED = 0xFFFF0000.toInt()
private const val GREEN = 0xFF00FF00.toInt()
private const val BLUE = 0xFF0000FF.toInt()

private fun cat(vararg parts: ByteArray): ByteArray {
    val out = ArrayList<Byte>()
    parts.forEach { p -> p.forEach { out.add(it) } }
    return out.toByteArray()
}

class ZrleDecoderTest {

    @Test
    fun solid_tile_fills_the_rect() = runTest {
        val inflated = cat(byteArrayOf(1), cp(0xFF, 0, 0)) // subencoding 1 = solid red
        val fb = VncFramebuffer(2, 2)
        decodeZrle(ZeroLengthSource(), fb, VncRect(0, 0, 2, 2), fakeInflaterYielding(inflated))
        assertEquals(RED, fb.pixels[0])
        assertEquals(RED, fb.pixels[3])
    }

    @Test
    fun raw_tile_decodes_cpixels() = runTest {
        val inflated = cat(byteArrayOf(0), cp(0xFF, 0, 0), cp(0, 0xFF, 0), cp(0, 0, 0xFF), cp(0xFF, 0xFF, 0xFF))
        val fb = VncFramebuffer(2, 2)
        decodeZrle(ZeroLengthSource(), fb, VncRect(0, 0, 2, 2), fakeInflaterYielding(inflated))
        assertEquals(RED, fb.pixels[0])
        assertEquals(GREEN, fb.pixels[1])
        assertEquals(BLUE, fb.pixels[2])
        assertEquals(0xFFFFFFFF.toInt(), fb.pixels[3])
    }

    @Test
    fun plain_rle_fills_a_run() = runTest {
        // subencoding 128 (plain RLE): one run of red covering all 4 pixels (length byte 3 → run 4).
        val inflated = cat(byteArrayOf(128.toByte()), cp(0xFF, 0, 0), byteArrayOf(3))
        val fb = VncFramebuffer(2, 2)
        decodeZrle(ZeroLengthSource(), fb, VncRect(0, 0, 2, 2), fakeInflaterYielding(inflated))
        assertEquals(RED, fb.pixels[0])
        assertEquals(RED, fb.pixels[3])
    }

    @Test
    fun packed_palette_2_colours() = runTest {
        // subencoding 2 (palette size 2, 1 bit/pixel). Palette: red, green. Row0=[red,green], Row1=[green,red].
        val inflated = cat(
            byteArrayOf(2),
            cp(0xFF, 0, 0), cp(0, 0xFF, 0),   // palette
            byteArrayOf(0x40.toByte()),        // row0: 0,1
            byteArrayOf(0x80.toByte()),        // row1: 1,0
        )
        val fb = VncFramebuffer(2, 2)
        decodeZrle(ZeroLengthSource(), fb, VncRect(0, 0, 2, 2), fakeInflaterYielding(inflated))
        assertEquals(RED, fb.pixels[0])   // (0,0)
        assertEquals(GREEN, fb.pixels[1]) // (1,0)
        assertEquals(GREEN, fb.pixels[2]) // (0,1)
        assertEquals(RED, fb.pixels[3])   // (1,1)
    }

    @Test
    fun palette_rle_mixes_singles_and_runs() = runTest {
        // subencoding 130 (palette RLE, size 2). Palette red,green. First: index 0 (single red). Then run of green (idx 129 = 128+1, length byte 2 → run 3).
        val inflated = cat(
            byteArrayOf(130.toByte()),
            cp(0xFF, 0, 0), cp(0, 0xFF, 0),
            byteArrayOf(0),                    // single red at pixel 0
            byteArrayOf(129.toByte(), 2),      // green run of 3 for the remaining pixels
        )
        val fb = VncFramebuffer(2, 2)
        decodeZrle(ZeroLengthSource(), fb, VncRect(0, 0, 2, 2), fakeInflaterYielding(inflated))
        assertEquals(RED, fb.pixels[0])
        assertEquals(GREEN, fb.pixels[1])
        assertEquals(GREEN, fb.pixels[3])
    }
}
