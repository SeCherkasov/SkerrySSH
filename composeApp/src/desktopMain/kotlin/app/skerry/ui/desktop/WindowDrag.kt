package app.skerry.ui.desktop

import java.awt.Point

/**
 * Where a titlebar drag should put the window, for the in-app drag used on platforms the window
 * manager can't do it for (see [NativeWindowMove]). Fed absolute pointer positions, it answers with
 * the window origin to apply, or `null` while the window has to stay put.
 *
 * It is handed a gesture that already is a drag — telling a drag from a click (and from the
 * double-click-to-maximize gesture) is the touch-slop gate in `awaitDragStart`, upstream of this
 * class. So the first event after [press] only captures the grab (window origin minus pointer) and
 * moves nothing; every event after it follows the pointer.
 *
 * One case deliberately stays put: a window that stopped floating mid-gesture — the double-click
 * maximizes it while the button is still down, and following the pointer from the pre-maximize
 * origin would then park a screen-sized window at the old top-left, pushing its right/bottom edges
 * off-screen.
 */
class WindowDragGesture {

    private var pressed = false

    /** Window origin minus pointer, captured on the first event of the drag; `null` until then. */
    private var grab: Point? = null

    /** Button pressed; arms the gesture. */
    fun press() {
        pressed = true
        grab = null
    }

    /**
     * Pointer moved to absolute [pointer] with the button still down. [windowOrigin] is the window's
     * current top-left, [floating] whether it is still in the floating placement. Returns the origin
     * to move the window to, or `null` to leave it alone.
     */
    fun drag(pointer: Point, windowOrigin: Point, floating: Boolean): Point? {
        // Maximized mid-gesture: this drag is over, and a later restore must not resume it either
        // (the grab it captured belongs to the floating window that no longer exists).
        if (!floating) {
            release()
            return null
        }
        if (!pressed) return null
        val grab = this.grab
        if (grab == null) {
            this.grab = Point(windowOrigin.x - pointer.x, windowOrigin.y - pointer.y)
            return null
        }
        return Point(pointer.x + grab.x, pointer.y + grab.y)
    }

    /** Button released: [drag] does nothing until the next [press]. */
    fun release() {
        pressed = false
        grab = null
    }
}
