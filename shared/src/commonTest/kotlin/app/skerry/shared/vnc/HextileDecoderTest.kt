package app.skerry.shared.vnc

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feeds fixture bytes to a decoder (whole-buffer). */
private class Bytes(private val data: ByteArray) : VncSource {
    private var pos = 0
    override suspend fun readFully(dst: ByteArray, offset: Int, len: Int) {
        data.copyInto(dst, offset, pos, pos + len)
        pos += len
    }
}

// Full 4-byte pixel [pad, R, G, B] in our canonical format.
private fun px(r: Int, g: Int, b: Int) = byteArrayOf(0, r.toByte(), g.toByte(), b.toByte())
private const val RED = 0xFFFF0000.toInt()
private const val GREEN = 0xFF00FF00.toInt()
private const val BLUE = 0xFF0000FF.toInt()

private fun bytesOf(vararg parts: ByteArray): ByteArray {
    val out = ArrayList<Byte>()
    parts.forEach { p -> p.forEach { out.add(it) } }
    return out.toByteArray()
}

class HextileDecoderTest {

    @Test
    fun background_only_tile_fills_solid() = runTest {
        // One 16x16 tile: subencoding = BackgroundSpecified, bg = red.
        val data = bytesOf(byteArrayOf(2), px(0xFF, 0, 0))
        val fb = VncFramebuffer(16, 16)
        decodeHextile(Bytes(data), fb, VncRect(0, 0, 16, 16))
        assertEquals(RED, fb.pixels[0])
        assertEquals(RED, fb.pixels[16 * 16 - 1])
    }

    @Test
    fun coloured_subrect_paints_over_background() = runTest {
        // subencoding = BG | AnySubrects | SubrectsColoured (2|8|16 = 26).
        val data = bytesOf(
            byteArrayOf(26),
            px(0xFF, 0, 0),          // bg red
            byteArrayOf(1),          // 1 subrect
            px(0, 0xFF, 0),          // subrect green
            byteArrayOf(0x23),       // x=2, y=3
            byteArrayOf(0x10),       // w=2, h=1
        )
        val fb = VncFramebuffer(16, 16)
        decodeHextile(Bytes(data), fb, VncRect(0, 0, 16, 16))
        assertEquals(RED, fb.pixels[0])
        assertEquals(GREEN, fb.pixels[3 * 16 + 2]) // (2,3)
        assertEquals(GREEN, fb.pixels[3 * 16 + 3]) // (3,3)
        assertEquals(RED, fb.pixels[4 * 16 + 2])   // (2,4) still bg
    }

    @Test
    fun raw_tile_carries_full_pixels() = runTest {
        // A 2x2 rect as one raw tile: red, green, blue, white.
        val data = bytesOf(
            byteArrayOf(1), // Raw
            px(0xFF, 0, 0), px(0, 0xFF, 0), px(0, 0, 0xFF), px(0xFF, 0xFF, 0xFF),
        )
        val fb = VncFramebuffer(2, 2)
        decodeHextile(Bytes(data), fb, VncRect(0, 0, 2, 2))
        assertEquals(RED, fb.pixels[0])
        assertEquals(GREEN, fb.pixels[1])
        assertEquals(BLUE, fb.pixels[2])
        assertEquals(0xFFFFFFFF.toInt(), fb.pixels[3])
    }

    @Test
    fun background_persists_across_tiles() = runTest {
        // 32x16 = two tiles wide. First tile sets bg=red; second omits BG → reuses red.
        val data = bytesOf(
            byteArrayOf(2), px(0xFF, 0, 0), // tile 1: bg red
            byteArrayOf(0),                  // tile 2: no bits → fill with carried bg
        )
        val fb = VncFramebuffer(32, 16)
        decodeHextile(Bytes(data), fb, VncRect(0, 0, 32, 16))
        assertEquals(RED, fb.pixels[0])       // tile 1
        assertEquals(RED, fb.pixels[16])      // tile 2, x=16
    }
}
