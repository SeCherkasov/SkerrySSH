package app.skerry.ui.vnc

import androidx.compose.ui.graphics.toPixelMap
import app.skerry.shared.graphics.RemoteRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The desktop pixel bridge, around the F-01 rewrite: the bitmap keeps long-lived pixel storage and
 * only the dirty rectangles are written, so these pin the properties that rewrite must not lose —
 * a partial update leaves the rest of the screen alone, and a clean bitmap costs nothing to read.
 */
class FramebufferImageDesktopTest {

    @Test
    fun `an update that touches one rect must not disturb the rest of the screen`() {
        val image = FramebufferImage(4, 2)
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        image.writeRects(listOf(RemoteRect(0, 0, 4, 2)), IntArray(8) { red }, 4)
        assertEquals(red, image.bitmap.toPixelMap().buffer[0])

        // One pixel at (2,1) turns green; every other pixel keeps its colour.
        val src = IntArray(8) { red }
        src[1 * 4 + 2] = green
        image.writeRects(listOf(RemoteRect(2, 1, 1, 1)), src, 4)

        val pixels = image.bitmap.toPixelMap()
        for (y in 0 until 2) {
            for (x in 0 until 4) {
                val expected = if (x == 2 && y == 1) green else red
                assertEquals(expected, pixels.buffer[y * 4 + x], "pixel ($x,$y)")
            }
        }
    }

    @Test
    fun `a clean bitmap is the same object, a dirty one is not`() {
        val image = FramebufferImage(2, 2)
        image.writeRects(listOf(RemoteRect(0, 0, 2, 2)), IntArray(4), 2)
        val first = image.bitmap
        assertSame(first, image.bitmap, "nothing changed, nothing to rebuild")

        image.writeRects(listOf(RemoteRect(0, 0, 1, 1)), IntArray(4), 2)
        assertNotSame(first, image.bitmap, "a write must invalidate what the draw holds")
    }

    @Test
    fun `resize replaces the surface and starts it empty`() {
        val image = FramebufferImage(2, 2)
        image.writeRects(listOf(RemoteRect(0, 0, 2, 2)), IntArray(4) { 0xFFABCDEF.toInt() }, 2)

        image.resize(3, 1)

        val pixels = image.bitmap.toPixelMap()
        assertEquals(3, image.bitmap.width)
        assertEquals(1, image.bitmap.height)
        assertEquals(0, pixels.buffer[0], "the old picture must not survive into the new size")
    }

    @Test
    fun `a rect past the edge is clipped, not written out of bounds`() {
        val image = FramebufferImage(2, 2)
        // The rect claims 3 columns of a 2-wide source: rows that fit are written, the rest skipped.
        image.writeRects(listOf(RemoteRect(1, 1, 3, 3)), IntArray(4) { 0xFF112233.toInt() }, 2)
        // Nothing to assert beyond "no exception": the bridge clamps rather than trusting the rect.
        image.bitmap
    }
}
