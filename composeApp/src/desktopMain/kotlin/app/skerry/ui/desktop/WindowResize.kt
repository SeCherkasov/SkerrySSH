package app.skerry.ui.desktop

import androidx.compose.ui.unit.DpSize
import java.awt.Dimension
import java.awt.Rectangle

/**
 * [minimumWindowSize] in AWT units, for the window manager's size hint and for the resize strips
 * that clamp against it. One conversion for both: a floor the WM enforces and a different floor in
 * the drag math would slide the fixed side of a left/top drag by their difference.
 */
fun minimumWindowDimension(screen: DpSize): Dimension = minimumWindowSize(screen).let {
    Dimension(it.width.value.toInt(), it.height.value.toInt())
}

/**
 * Which window border a resize drag grabs. [dx]/[dy] mark the moving side per axis:
 * -1 — the left/top side moves, 1 — the right/bottom side, 0 — the axis is untouched.
 */
enum class ResizeEdge(val dx: Int, val dy: Int) {
    Left(-1, 0), Right(1, 0), Top(0, -1), Bottom(0, 1),
    TopLeft(-1, -1), TopRight(1, -1), BottomLeft(-1, 1), BottomRight(1, 1),
}

/**
 * New window bounds after dragging [edge] by ([deltaX], [deltaY]) px from [start].
 * When the left/top side moves, the opposite side stays fixed (x/y compensate the width/height
 * change) — including when the size clamps at [minWidth]/[minHeight].
 */
fun resizedWindowBounds(
    start: Rectangle,
    edge: ResizeEdge,
    deltaX: Int,
    deltaY: Int,
    minWidth: Int,
    minHeight: Int,
): Rectangle {
    var width = start.width
    var x = start.x
    if (edge.dx != 0) {
        width = (start.width + edge.dx * deltaX).coerceAtLeast(minWidth)
        if (edge.dx < 0) x = start.x + start.width - width
    }
    var height = start.height
    var y = start.y
    if (edge.dy != 0) {
        height = (start.height + edge.dy * deltaY).coerceAtLeast(minHeight)
        if (edge.dy < 0) y = start.y + start.height - height
    }
    return Rectangle(x, y, width, height)
}
