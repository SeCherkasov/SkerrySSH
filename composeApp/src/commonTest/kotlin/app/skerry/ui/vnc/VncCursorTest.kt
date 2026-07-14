package app.skerry.ui.vnc

import app.skerry.shared.vnc.VncUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VncCursorTest {

    @Test
    fun hides_the_local_pointer_over_a_live_framebuffer() {
        // We draw the remote cursor ourselves there; the OS pointer on top would be a duplicate.
        assertTrue(shouldHideLocalCursor(interactive = true, viewOnly = false, pointerOverImage = true))
    }

    @Test
    fun keeps_the_local_pointer_over_the_letterbox() {
        // Outside the fitted image (black bars, or a tab with no frame yet) there is no remote cursor
        // to stand in for ours — hiding it would leave the area with no pointer at all.
        assertFalse(shouldHideLocalCursor(interactive = true, viewOnly = false, pointerOverImage = false))
    }

    @Test
    fun keeps_the_local_pointer_in_view_only() {
        // View-only sends no pointer events, so the remote cursor doesn't follow the mouse; there the
        // server paints it into the framebuffer instead (see VncScreenState.toggleViewOnly).
        assertFalse(shouldHideLocalCursor(interactive = true, viewOnly = true, pointerOverImage = true))
    }

    @Test
    fun keeps_the_local_pointer_on_a_frozen_frame() {
        // A disconnected tab still renders its last frame, but nothing tracks the mouse there.
        assertFalse(shouldHideLocalCursor(interactive = false, viewOnly = false, pointerOverImage = true))
    }

    // --- sprite placement ---

    @Test
    fun sprite_lands_on_the_framebuffer_pixel_under_the_pointer_minus_the_hotspot() {
        // 10x10 framebuffer drawn 4x in a 40x40 canvas: no letterbox, scale 4, origin 0.
        val geom = fitGeometry(40f, 40f, 10, 10)
        // Pointer mid-pixel (5,3): the sprite snaps to that pixel's top-left, then backs off a
        // hotspot of (2,1) — i.e. fb pixel (3,2) -> canvas (12,8).
        val at = assertNotNull(cursorTopLeft(geom, 22f, 15f, hotspotX = 2, hotspotY = 1))
        assertEquals(12f, at.x)
        assertEquals(8f, at.y)
    }

    @Test
    fun sprite_placement_accounts_for_the_letterbox_offset() {
        // 10x5 framebuffer in a 40x40 canvas: scale 4, centered vertically -> a 10px top bar.
        val geom = fitGeometry(40f, 40f, 10, 5)
        val at = assertNotNull(cursorTopLeft(geom, 2f, 12f, hotspotX = 0, hotspotY = 0))
        assertEquals(0f, at.x)
        assertEquals(10f, at.y) // fb row 0 starts below the bar
    }

    @Test
    fun no_sprite_outside_the_drawn_image() {
        // There is no framebuffer pixel out there to anchor the sprite to. In practice VncView never
        // asks: it feeds the last position INSIDE the image, so moving onto the letterbox leaves the
        // sprite where the remote cursor really is (next to the OS pointer, which comes back there).
        val geom = fitGeometry(40f, 40f, 10, 5)
        assertNull(cursorTopLeft(geom, 2f, 2f, hotspotX = 0, hotspotY = 0))
    }

    // --- sprite construction ---

    @Test
    fun empty_shape_means_no_sprite() {
        // A 0x0 shape is the server saying the cursor is hidden right now.
        assertNull(VncCursorImage.of(VncUpdate.CursorShape(IntArray(0), 0, 0, 0, 0)))
    }

    @Test
    fun shape_becomes_a_sprite_carrying_its_hotspot() {
        val shape = VncUpdate.CursorShape(IntArray(6) { 0xFF00FF00.toInt() }, width = 3, height = 2, hotspotX = 1, hotspotY = 1)
        val sprite = assertNotNull(VncCursorImage.of(shape))
        assertEquals(3, sprite.width)
        assertEquals(2, sprite.height)
        assertEquals(1, sprite.hotspotX)
        assertEquals(1, sprite.hotspotY)
        assertEquals(3, sprite.bitmap.width)
        assertEquals(2, sprite.bitmap.height)
    }
}
