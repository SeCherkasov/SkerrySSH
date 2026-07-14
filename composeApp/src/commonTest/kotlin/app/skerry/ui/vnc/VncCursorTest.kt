package app.skerry.ui.vnc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VncCursorTest {

    @Test
    fun hides_the_local_pointer_over_a_live_framebuffer() {
        // The server draws the remote cursor into the framebuffer; ours on top would be a duplicate.
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
        // View-only sends no pointer events, so the remote cursor doesn't follow the mouse.
        assertFalse(shouldHideLocalCursor(interactive = true, viewOnly = true, pointerOverImage = true))
    }

    @Test
    fun keeps_the_local_pointer_on_a_frozen_frame() {
        // A disconnected tab still renders its last frame, but nothing tracks the mouse there.
        assertFalse(shouldHideLocalCursor(interactive = false, viewOnly = false, pointerOverImage = true))
    }
}
