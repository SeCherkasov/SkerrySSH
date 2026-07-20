package app.skerry.ui.vnc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Relative ("trackpad") cursor for touch input: the finger pushes the remote cursor instead of
 * teleporting it under the touch point. A finger is far wider than a pixel, so absolute mapping
 * makes small targets — window buttons, menu items — unhittable; here the user drags the cursor
 * onto the target and taps, and the cursor stays visible under their own finger.
 *
 * The position is kept as a float so a slow drag accumulates sub-pixel motion instead of stalling,
 * and deltas are divided by the draw [scale] so the cursor travels exactly as far on screen as the
 * finger does — under zoom that automatically means finer control over the framebuffer.
 */
@Stable
class VncTrackpad(desktop: IntSize) {
    private var desktop: IntSize = desktop

    /** Cursor position in framebuffer pixels (float; see the class doc). Starts screen-centered. */
    var position by mutableStateOf(Offset(desktop.width / 2f, desktop.height / 2f))
        private set

    /** The framebuffer pixel the cursor is on — what pointer events are sent for. */
    val pixel: IntOffset get() = IntOffset(position.x.toInt(), position.y.toInt())

    /** Move by a canvas-space [delta]; [scale] is the fit-to-window scale times the user zoom. */
    fun moveBy(delta: Offset, scale: Float) {
        if (scale <= 0f) return
        position = clamp(position + delta / scale)
    }

    /** The remote desktop resized: keep the cursor inside the new bounds. */
    fun onDesktopSize(size: IntSize) {
        desktop = size
        position = clamp(position)
    }

    /** Where to draw the cursor on the canvas, given the geometry the framebuffer is drawn with. */
    fun canvasPosition(geom: FitGeometry): Offset =
        Offset(geom.offsetX + pixel.x * geom.scale, geom.offsetY + pixel.y * geom.scale)

    /**
     * How much the pan offset has to change for the cursor at [cursor] (canvas coordinates) to stay
     * [margin] pixels inside a [canvas]-sized viewport. Zero when it already is. Zoomed in, the
     * framebuffer is larger than the viewport and pushing the cursor against an edge has to scroll
     * the view — otherwise the cursor walks off-screen and the zoom is only usable at its center.
     */
    fun panToReveal(cursor: Offset, canvas: IntSize, margin: Float): Offset = Offset(
        panAxis(cursor.x, canvas.width.toFloat(), margin),
        panAxis(cursor.y, canvas.height.toFloat(), margin),
    )

    private fun panAxis(at: Float, extent: Float, margin: Float): Float {
        // A viewport too small for both margins would fight itself; nudge nothing there.
        if (extent < 2 * margin) return 0f
        return when {
            at < margin -> margin - at
            at > extent - margin -> extent - margin - at
            else -> 0f
        }
    }

    private fun clamp(at: Offset): Offset = Offset(
        at.x.coerceIn(0f, (desktop.width - 1).coerceAtLeast(0).toFloat()),
        at.y.coerceIn(0f, (desktop.height - 1).coerceAtLeast(0).toFloat()),
    )
}
