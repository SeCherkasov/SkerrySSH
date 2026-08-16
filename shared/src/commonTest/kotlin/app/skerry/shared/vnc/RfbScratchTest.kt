package app.skerry.shared.vnc

import app.skerry.shared.graphics.RemoteFramebuffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared decoder scratch (V-04): rectangles decoded through one [RfbScratch] must come out
 * exactly as they would from fresh buffers — a narrower rectangle after a wide one is the case
 * where a stale row tail could bleed through.
 */
class RfbScratchTest {

    private fun sourceOf(bytes: ByteArray): VncSource = object : VncSource {
        private var pos = 0
        override suspend fun readFully(dst: ByteArray, offset: Int, len: Int) {
            bytes.copyInto(dst, offset, pos, pos + len)
            pos += len
        }
    }

    /** [count] pixels of [argb] in the canonical wire form (pad, R, G, B). */
    private fun rawPixels(count: Int, argb: Int): ByteArray = ByteArray(count * 4) { index ->
        when (index % 4) {
            1 -> (argb shr 16).toByte()
            2 -> (argb shr 8).toByte()
            3 -> argb.toByte()
            else -> 0
        }
    }

    @Test
    fun a_narrow_rectangle_after_a_wide_one_carries_no_stale_pixels() = runTest {
        val fb = RemoteFramebuffer(4, 1)
        val shared = RfbScratch()

        decodeRaw(sourceOf(rawPixels(4, 0xFF0000)), fb, VncRect(0, 0, 4, 1), shared)
        decodeRaw(sourceOf(rawPixels(2, 0x00FF00)), fb, VncRect(0, 0, 2, 1), shared)

        assertEquals(
            listOf(0xFF00FF00.toInt(), 0xFF00FF00.toInt(), 0xFFFF0000.toInt(), 0xFFFF0000.toInt()),
            fb.pixels.toList(),
        )
    }
}
