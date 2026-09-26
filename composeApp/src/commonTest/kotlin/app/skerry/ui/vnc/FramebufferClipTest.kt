package app.skerry.ui.vnc

import app.skerry.shared.graphics.RemoteRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The clip both pixel bridges write through. Android's `setPixels` throws on a rectangle that
 * reaches past its source array, so this is what keeps a stale region from ending the session.
 */
class FramebufferClipTest {

    @Test
    fun `a region from before a shrink is cut to the rows the source still has`() {
        // Bitmap still 4x4, framebuffer already 2x2.
        assertEquals(RemoteRect(0, 0, 2, 2), clipToBoth(RemoteRect(0, 0, 4, 4), 4, 4, srcWidth = 2, srcSize = 4))
    }

    @Test
    fun `a region from before a grow is cut to the bitmap it is drawn into`() {
        assertEquals(RemoteRect(1, 1, 1, 1), clipToBoth(RemoteRect(1, 1, 8, 8), 2, 2, srcWidth = 8, srcSize = 64))
    }

    @Test
    fun `a region wholly outside either side draws nothing`() {
        assertNull(clipToBoth(RemoteRect(0, 3, 2, 2), 4, 4, srcWidth = 2, srcSize = 4))
        assertNull(clipToBoth(RemoteRect(5, 0, 1, 1), 4, 4, srcWidth = 8, srcSize = 64))
    }

    @Test
    fun `a negative size or an empty source draws nothing`() {
        assertNull(clipToBoth(RemoteRect(2, 0, -1, 1), 4, 4, srcWidth = 4, srcSize = 16))
        assertNull(clipToBoth(RemoteRect(0, 0, 1, 1), 4, 4, srcWidth = 0, srcSize = 0))
    }

    @Test
    fun `a negative origin is cut at the edge`() {
        assertEquals(RemoteRect(0, 0, 1, 2), clipToBoth(RemoteRect(-1, -1, 2, 3), 4, 4, srcWidth = 4, srcSize = 16))
    }
}
