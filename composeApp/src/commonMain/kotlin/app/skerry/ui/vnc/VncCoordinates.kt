package app.skerry.ui.vnc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fit-to-window geometry for drawing and pointer mapping: how the [fbWidth]×[fbHeight] framebuffer
 * is placed inside a canvas — a uniform [scale] (aspect preserved) plus a centering
 * [offsetX]/[offsetY]. [userScale]/[userOffset] apply an additional zoom+pan on top of the fit (1f/0
 * = plain fit). Kept pure so both the draw path and the pointer path use identical math and it's
 * unit-testable.
 */
data class FitGeometry(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val fbWidth: Int,
    val fbHeight: Int,
) {
    /** Destination size of the drawn image. */
    val dstWidth: Int get() = (fbWidth * scale).roundToInt()
    val dstHeight: Int get() = (fbHeight * scale).roundToInt()

    /**
     * Map a canvas point to framebuffer pixel coordinates, or null if it falls outside the image.
     * Coordinates are clamped into [0, fb-1] when inside the drawn rect.
     */
    fun toFramebuffer(px: Float, py: Float): IntOffset? {
        if (scale <= 0f) return null
        val fx = ((px - offsetX) / scale)
        val fy = ((py - offsetY) / scale)
        if (fx < 0f || fy < 0f || fx >= fbWidth || fy >= fbHeight) return null
        return IntOffset(fx.toInt().coerceIn(0, fbWidth - 1), fy.toInt().coerceIn(0, fbHeight - 1))
    }
}

/**
 * Filter for drawing the framebuffer at [scale]: 1:1 and integer zooms keep nearest-neighbor (hard
 * pixel edges), any fractional scale is resampled bilinearly — nearest there duplicates some pixel
 * rows and drops others, which shreds remote text and icons. Medium (bilinear + mipmaps), not High
 * (bicubic): the resample covers the whole canvas on every framebuffer update, and a fullscreen
 * bicubic pass is ~4× the taps for no visible gain at these scales.
 */
fun framebufferFilterQuality(scale: Float): FilterQuality {
    if (scale <= 0f) return FilterQuality.None
    val nearest = scale.roundToInt()
    return if (nearest >= 1 && abs(scale - nearest) < 0.001f) FilterQuality.None else FilterQuality.Medium
}

/**
 * Cap a user pan so the zoomed framebuffer keeps covering the viewport: at most half the overflow in
 * each direction, and no pan at all while the picture still fits ([userScale] 1, or an axis that
 * letterboxes). Without this a pinch-pan can push the desktop off-screen entirely, leaving black —
 * on touch, where every zoom gesture also pans, that happens constantly.
 *
 * Returns [userOffset] unchanged when the viewport hasn't been measured yet.
 */
fun clampPan(userOffset: Offset, canvas: IntSize, fbWidth: Int, fbHeight: Int, userScale: Float): Offset {
    if (canvas.width <= 0 || canvas.height <= 0 || fbWidth <= 0 || fbHeight <= 0) return userOffset
    val fit = minOf(canvas.width.toFloat() / fbWidth, canvas.height.toFloat() / fbHeight)
    val scale = fit * userScale
    return Offset(
        panLimit(userOffset.x, canvas.width.toFloat(), fbWidth * scale),
        panLimit(userOffset.y, canvas.height.toFloat(), fbHeight * scale),
    )
}

private fun panLimit(offset: Float, extent: Float, drawn: Float): Float {
    val slack = (drawn - extent) / 2f
    return if (slack <= 0f) 0f else offset.coerceIn(-slack, slack)
}

/**
 * Compute fit-to-window geometry for a [fbWidth]×[fbHeight] framebuffer in a [canvasWidth]×
 * [canvasHeight] canvas. [userScale] (>=1 typically) zooms in and [userOffsetX]/[userOffsetY] pan;
 * the defaults reproduce a plain centered fit.
 */
fun fitGeometry(
    canvasWidth: Float,
    canvasHeight: Float,
    fbWidth: Int,
    fbHeight: Int,
    userScale: Float = 1f,
    userOffsetX: Float = 0f,
    userOffsetY: Float = 0f,
): FitGeometry {
    if (fbWidth <= 0 || fbHeight <= 0 || canvasWidth <= 0f || canvasHeight <= 0f) {
        return FitGeometry(0f, 0f, 0f, fbWidth, fbHeight)
    }
    val fit = minOf(canvasWidth / fbWidth, canvasHeight / fbHeight)
    val scale = fit * userScale
    val dstW = fbWidth * scale
    val dstH = fbHeight * scale
    val offsetX = (canvasWidth - dstW) / 2f + userOffsetX
    val offsetY = (canvasHeight - dstH) / 2f + userOffsetY
    return FitGeometry(scale, offsetX, offsetY, fbWidth, fbHeight)
}
