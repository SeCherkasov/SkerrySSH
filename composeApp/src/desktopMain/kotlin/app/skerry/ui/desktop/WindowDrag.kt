package app.skerry.ui.desktop

import java.awt.Point

/**
 * Where a titlebar drag should put the window, for the in-app drag used on platforms the window
 * manager can't do it for (see [NativeWindowMove]). Fed absolute pointer positions, it answers with
 * the window origin to apply, or `null` while the window has to stay put.
 *
 * Two cases deliberately stay put: a press that hasn't left [deadZone] yet, so a plain click and the
 * double-click-to-maximize gesture never nudge the window, and a window that stopped floating
 * mid-gesture — the double-click maximizes it while the button is still down, and following the
 * pointer from the pre-maximize origin would then park a screen-sized window at the old top-left,
 * pushing its right/bottom edges off-screen.
 */
class WindowDragGesture(private val deadZone: Int) {

    private var pressedAt: Point? = null

    /** Window origin minus pointer, captured once the drag leaves the dead zone; `null` until then. */
    private var grab: Point? = null

    /** Button pressed at absolute [pointer]; arms the gesture. */
    fun press(pointer: Point) {
        pressedAt = Point(pointer)
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
        val pressedAt = this.pressedAt ?: return null
        val grab = this.grab
        if (grab == null) {
            if (!leftDeadZone(pressedAt, pointer)) return null
            this.grab = Point(windowOrigin.x - pointer.x, windowOrigin.y - pointer.y)
            return null
        }
        return Point(pointer.x + grab.x, pointer.y + grab.y)
    }

    /** Button released: [drag] does nothing until the next [press]. */
    fun release() {
        pressedAt = null
        grab = null
    }

    private fun leftDeadZone(from: Point, to: Point): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        return dx * dx + dy * dy >= deadZone * deadZone
    }
}
