package app.skerry.ui.vnc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class VncTrackpadTest {

    @Test
    fun starts_in_the_middle_of_the_desktop() {
        val pad = VncTrackpad(IntSize(800, 600))
        assertEquals(IntOffset(400, 300), pad.pixel)
    }

    @Test
    fun a_finger_delta_moves_the_cursor_the_same_distance_on_screen() {
        // Half scale: 10 canvas px cover 20 framebuffer px, so the cursor keeps up with the finger.
        val pad = VncTrackpad(IntSize(800, 600))
        pad.moveBy(Offset(10f, 10f), scale = 0.5f)
        assertEquals(IntOffset(420, 320), pad.pixel)
    }

    @Test
    fun sub_pixel_movement_accumulates_instead_of_stalling() {
        val pad = VncTrackpad(IntSize(800, 600))
        // 0.4 fb px per step at scale 1 — four steps must add up to a whole pixel.
        repeat(4) { pad.moveBy(Offset(0.4f, 0f), scale = 1f) }
        assertEquals(401, pad.pixel.x)
    }

    @Test
    fun the_cursor_stops_at_the_desktop_edges() {
        val pad = VncTrackpad(IntSize(800, 600))
        pad.moveBy(Offset(-5000f, -5000f), scale = 1f)
        assertEquals(IntOffset(0, 0), pad.pixel)
        pad.moveBy(Offset(5000f, 5000f), scale = 1f)
        assertEquals(IntOffset(799, 599), pad.pixel)
    }

    @Test
    fun a_server_resize_pulls_the_cursor_back_inside() {
        val pad = VncTrackpad(IntSize(800, 600))
        pad.moveBy(Offset(5000f, 5000f), scale = 1f)
        pad.onDesktopSize(IntSize(400, 300))
        assertEquals(IntOffset(399, 299), pad.pixel)
    }

    @Test
    fun a_degenerate_scale_is_ignored() {
        val pad = VncTrackpad(IntSize(800, 600))
        pad.moveBy(Offset(10f, 10f), scale = 0f)
        assertEquals(IntOffset(400, 300), pad.pixel)
    }

    @Test
    fun an_empty_desktop_keeps_the_cursor_at_the_origin() {
        val pad = VncTrackpad(IntSize.Zero)
        pad.moveBy(Offset(10f, 10f), scale = 1f)
        assertEquals(IntOffset(0, 0), pad.pixel)
    }

    @Test
    fun a_cursor_inside_the_viewport_needs_no_panning() {
        val pad = VncTrackpad(IntSize(800, 600))
        assertEquals(Offset.Zero, pad.panToReveal(Offset(500f, 500f), IntSize(1000, 1000), margin = 40f))
    }

    @Test
    fun a_cursor_past_the_edge_pans_the_view_back_over_it() {
        val pad = VncTrackpad(IntSize(800, 600))
        // 10px from the left edge with a 40px margin → the view shifts right by 30.
        assertEquals(Offset(30f, 0f), pad.panToReveal(Offset(10f, 500f), IntSize(1000, 1000), margin = 40f))
        // 20px past the bottom edge → shift up by 60 (the 40px margin plus the overshoot).
        assertEquals(Offset(0f, -60f), pad.panToReveal(Offset(500f, 1020f), IntSize(1000, 1000), margin = 40f))
    }

    @Test
    fun a_viewport_thinner_than_its_margins_is_left_alone() {
        val pad = VncTrackpad(IntSize(800, 600))
        assertEquals(Offset.Zero, pad.panToReveal(Offset(5f, 5f), IntSize(50, 50), margin = 40f))
    }

    @Test
    fun canvas_position_places_the_cursor_where_the_framebuffer_pixel_is_drawn() {
        // 400x300 desktop drawn at scale 2 with a 10px letterbox: fb (0,0) sits at canvas (10,10).
        val pad = VncTrackpad(IntSize(400, 300))
        pad.moveBy(Offset(-5000f, -5000f), scale = 1f)
        val geom = FitGeometry(scale = 2f, offsetX = 10f, offsetY = 10f, fbWidth = 400, fbHeight = 300)
        assertEquals(Offset(10f, 10f), pad.canvasPosition(geom))
    }
}
