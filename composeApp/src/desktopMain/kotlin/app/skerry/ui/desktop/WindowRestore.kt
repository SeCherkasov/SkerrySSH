package app.skerry.ui.desktop

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * Where to put a window restored from maximized in the middle of a titlebar drag. The pointer keeps
 * the share of the titlebar it grabbed — press near the right end of a maximized window and the
 * restored window hangs off to the left of the cursor, the way a native titlebar behaves — instead
 * of the window jumping back to the origin it had before it was maximized, out from under the hand.
 *
 * [maximized] is the area the window is leaving (its own bounds), [restored] the size it returns to.
 * The result stays inside that area, so a grab at either end doesn't push the window off-screen.
 */
fun restoredWindowOrigin(maximized: Rectangle, restored: Dimension, pointer: Point): Point = Point(
    axisOrigin(pointer.x, maximized.x, maximized.width, restored.width),
    axisOrigin(pointer.y, maximized.y, maximized.height, restored.height),
)

private fun axisOrigin(pointer: Int, areaStart: Int, areaSize: Int, windowSize: Int): Int {
    // A zero-sized area (a window with no bounds yet) has no share to keep: pin the pointer to the
    // window's leading edge rather than dividing by it.
    val share = if (areaSize > 0) ((pointer - areaStart).toDouble() / areaSize).coerceIn(0.0, 1.0) else 0.0
    val origin = pointer - (share * windowSize).roundToInt()
    // A window wider than the area it came from can only start at its leading edge.
    val last = (areaStart + areaSize - windowSize).coerceAtLeast(areaStart)
    return origin.coerceIn(areaStart, last)
}
