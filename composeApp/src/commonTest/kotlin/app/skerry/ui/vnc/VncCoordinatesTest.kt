package app.skerry.ui.vnc

import androidx.compose.ui.unit.IntOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VncCoordinatesTest {

    @Test
    fun fit_centers_a_smaller_aspect_and_letterboxes() {
        // 100x100 framebuffer in a 200x100 canvas: scale 1, centered horizontally (50px bars).
        val geom = fitGeometry(200f, 100f, 100, 100)
        assertEquals(1f, geom.scale)
        assertEquals(50f, geom.offsetX)
        assertEquals(0f, geom.offsetY)
    }

    @Test
    fun pointer_maps_back_to_framebuffer_pixels() {
        val geom = fitGeometry(200f, 100f, 100, 100)
        // Canvas (50,0) is the top-left of the image → fb (0,0).
        assertEquals(IntOffset(0, 0), geom.toFramebuffer(50f, 0f))
        // Canvas (149,50) → fb (99, 50); the very edge at 150 is exclusive (outside).
        assertEquals(IntOffset(99, 50), geom.toFramebuffer(149f, 50f))
        assertNull(geom.toFramebuffer(150f, 50f))
    }

    @Test
    fun points_in_the_letterbox_are_outside() {
        val geom = fitGeometry(200f, 100f, 100, 100)
        assertNull(geom.toFramebuffer(10f, 50f))  // left bar
        assertNull(geom.toFramebuffer(190f, 50f)) // right bar
    }

    @Test
    fun scaling_up_halves_the_framebuffer_coordinate() {
        // 50x50 fb in a 100x100 canvas → scale 2, no letterbox.
        val geom = fitGeometry(100f, 100f, 50, 50)
        assertEquals(2f, geom.scale)
        assertEquals(IntOffset(25, 25), geom.toFramebuffer(50f, 50f))
    }

    @Test
    fun zero_size_is_safe() {
        val geom = fitGeometry(0f, 0f, 0, 0)
        assertEquals(0f, geom.scale)
        assertNull(geom.toFramebuffer(0f, 0f))
    }
}
