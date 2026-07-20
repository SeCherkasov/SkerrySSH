package app.skerry.ui.vnc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Touch version of [VncSurface]. The finger drives the remote cursor like a laptop trackpad
 * ([VncTrackpad]) instead of teleporting it under the touch point — see that class for why — and
 * the gestures are the ones a remote desktop needs but a mouse-shaped input model has no room for:
 *
 * - drag with one finger — move the cursor (the view pans along when it reaches an edge under zoom)
 * - tap — left click at the cursor
 * - double-tap and hold, then drag — drag with the left button down (moving windows, selecting text)
 * - tap with two fingers — right click
 * - drag with two fingers — scroll wheel at 1:1 zoom, pan the picture when zoomed in
 * - pinch — zoom
 *
 * Nothing here takes Compose focus: the keyboard belongs to the hidden IME field on the screen
 * above, and stealing focus on every touch is exactly what closes the soft keyboard.
 */
@Composable
fun VncTouchSurface(screen: VncScreenState, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val frame = screen.frame
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val pad = remember(screen) { VncTrackpad(screen.desktopSize) }
    // A server-side resize moves the desktop out from under the cursor; keep it inside the new bounds.
    LaunchedEffect(pad, screen.desktopSize) { pad.onDesktopSize(screen.desktopSize) }

    var mod = modifier.fillMaxSize().clipToBounds().background(Color.Black).onSizeChanged {
        canvasSize = it
        screen.onViewportSize(it)
    }
    // A frozen last frame after a drop takes no input, and nothing tracks a cursor on it.
    if (interactive) mod = mod.pointerInput(screen, pad) { vncTouchGestures(screen, pad) { canvasSize } }

    Box(mod) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame // captured so the draw invalidates when it changes
            drawFramebuffer(screen)
        }
        // Own canvas, as on desktop: the cursor moves far more often than the framebuffer changes,
        // and only this layer reads its position, so a move redraws just the sprite.
        if (interactive) Canvas(Modifier.fillMaxSize()) { drawTouchCursor(screen, pad) }
    }

    if (interactive) VncClipboardBridge(screen)
}

/** The remote cursor at the trackpad's position: the server's sprite, or our own arrow if it sent none. */
private fun DrawScope.drawTouchCursor(screen: VncScreenState, pad: VncTrackpad) {
    // View-only sends no pointer events, so nothing follows our cursor — the server paints the real
    // one into the framebuffer instead, and a second one here would only lie about where it is.
    if (screen.viewOnly) return
    val geom = fitGeometry(
        size.width, size.height, screen.desktopSize.width, screen.desktopSize.height,
        screen.userScale, screen.userOffset.x, screen.userOffset.y,
    )
    if (geom.scale <= 0f) return
    val at = pad.canvasPosition(geom)
    val sprite = screen.cursor
    if (sprite == null) {
        drawFallbackCursor(at)
        return
    }
    drawImage(
        image = sprite.bitmap,
        dstOffset = IntOffset(
            (at.x - sprite.hotspotX * geom.scale).roundToInt(),
            (at.y - sprite.hotspotY * geom.scale).roundToInt(),
        ),
        dstSize = IntSize((sprite.width * geom.scale).roundToInt(), (sprite.height * geom.scale).roundToInt()),
        filterQuality = framebufferFilterQuality(geom.scale),
    )
}

/**
 * A plain arrow at the cursor, drawn only while the server has sent no cursor shape. Without it a
 * server that neither supports the Cursor pseudo-encoding nor paints a cursor itself leaves the user
 * dragging an invisible pointer. Outlined in white so it stays visible on a dark desktop.
 */
private fun DrawScope.drawFallbackCursor(at: Offset) {
    val arrow = Path().apply {
        moveTo(at.x, at.y)
        lineTo(at.x, at.y + CURSOR_SIZE_PX)
        lineTo(at.x + CURSOR_SIZE_PX * 0.28f, at.y + CURSOR_SIZE_PX * 0.72f)
        lineTo(at.x + CURSOR_SIZE_PX * 0.62f, at.y + CURSOR_SIZE_PX * 0.68f)
        close()
    }
    drawPath(arrow, Color.White)
    drawPath(arrow, Color.Black, style = Stroke(width = 1.5f))
}

/**
 * The gesture loop described on [VncTouchSurface]. Split out of the composable so the state machine
 * reads as one piece; [canvasSize] is a lambda because the loop outlives any single layout pass.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.vncTouchGestures(
    screen: VncScreenState,
    pad: VncTrackpad,
    canvasSize: () -> IntSize,
) {
    val slop = viewConfiguration.touchSlop
    var lastTapAt: TimeSource.Monotonic.ValueTimeMark? = null
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // A second tap that lands quickly enough starts a drag: press now, release when the finger
        // lifts. This is how a touch screen moves a window or selects text.
        var dragging = lastTapAt?.elapsedNow()?.inWholeMilliseconds?.let { it < DOUBLE_TAP_WINDOW_MS } == true
        if (dragging) screen.onPointer(pad.pixel.x, pad.pixel.y, VncButton.LEFT)
        lastTapAt = null

        var travelled = 0f          // how far the gesture moved, to tell a tap from a drag
        var multiTouch = false      // a second finger joined at any point
        var lastCentroid = Offset.Unspecified
        var lastSpread = 0f
        var scrollCarry = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            when {
                pressed.size >= 2 -> {
                    multiTouch = true
                    // A second finger turns the gesture into scroll/zoom, so a drag started by the
                    // preceding tap ends here — scrolling with the left button held would select
                    // half the remote screen on the way.
                    if (dragging) {
                        screen.onPointer(pad.pixel.x, pad.pixel.y, 0)
                        dragging = false
                    }
                    val centroid = centroidOf(pressed)
                    val spread = spreadOf(pressed, centroid)
                    if (lastCentroid.isSpecified && lastSpread > 0f) {
                        val zoom = if (spread > 0f) spread / lastSpread else 1f
                        val panBy = centroid - lastCentroid
                        travelled += panBy.getDistance() + abs(spread - lastSpread)
                        if (abs(zoom - 1f) > ZOOM_DEADZONE) {
                            screen.setZoom(screen.userScale * zoom, screen.userOffset + panBy)
                        } else if (screen.userScale > 1f) {
                            // Zoomed in, two fingers pan the picture — the same gesture as a photo
                            // viewer, and the only way to reach the parts of the desktop off-screen.
                            screen.setZoom(screen.userScale, screen.userOffset + panBy)
                        } else {
                            scrollCarry = sendWheel(screen, pad, scrollCarry + panBy.y)
                        }
                    }
                    lastCentroid = centroid
                    lastSpread = spread
                }
                pressed.size == 1 && !multiTouch -> {
                    val delta = pressed.first().positionChange()
                    travelled += delta.getDistance()
                    pad.moveBy(delta, currentScale(screen, canvasSize()))
                    keepCursorVisible(screen, pad, canvasSize())
                    // Buttons follow the cursor: dragging keeps the left button down, otherwise this
                    // is a plain move (some remote UIs only reveal hover states this way).
                    screen.onPointer(pad.pixel.x, pad.pixel.y, if (dragging) VncButton.LEFT else 0)
                }
                else -> {
                    // Down to one finger after a two-finger gesture: don't let the leftover finger
                    // drag the cursor, just wait for it to lift.
                    lastCentroid = Offset.Unspecified
                    lastSpread = 0f
                }
            }
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }

        val tapped = travelled < slop
        when {
            dragging -> screen.onPointer(pad.pixel.x, pad.pixel.y, 0)   // release the drag
            multiTouch && tapped -> click(screen, pad, VncButton.RIGHT)
            !multiTouch && tapped -> {
                click(screen, pad, VncButton.LEFT)
                lastTapAt = TimeSource.Monotonic.markNow()
            }
        }
    }
}

/** Press and release [button] where the cursor is. */
private fun click(screen: VncScreenState, pad: VncTrackpad, button: Int) {
    screen.onPointer(pad.pixel.x, pad.pixel.y, button)
    screen.onPointer(pad.pixel.x, pad.pixel.y, 0)
}

/**
 * Turn accumulated vertical finger travel into wheel clicks (RFB has no continuous scrolling —
 * a wheel step is a button press+release), returning the remainder to carry into the next event.
 */
private fun sendWheel(screen: VncScreenState, pad: VncTrackpad, carry: Float): Float {
    var left = carry
    while (abs(left) >= WHEEL_STEP_PX) {
        // Finger down = content down = wheel up, matching the direction of a touch scroll.
        val bit = if (left > 0f) VncButton.WHEEL_UP else VncButton.WHEEL_DOWN
        click(screen, pad, bit)
        left -= if (left > 0f) WHEEL_STEP_PX else -WHEEL_STEP_PX
    }
    return left
}

/** Pan the view when the cursor is pushed against a viewport edge (only possible when zoomed in). */
private fun keepCursorVisible(screen: VncScreenState, pad: VncTrackpad, canvas: IntSize) {
    if (screen.userScale <= 1f || canvas.width <= 0 || canvas.height <= 0) return
    val geom = fitGeometry(
        canvas.width.toFloat(), canvas.height.toFloat(),
        screen.desktopSize.width, screen.desktopSize.height,
        screen.userScale, screen.userOffset.x, screen.userOffset.y,
    )
    val nudge = pad.panToReveal(pad.canvasPosition(geom), canvas, EDGE_MARGIN_PX)
    if (nudge != Offset.Zero) screen.setZoom(screen.userScale, screen.userOffset + nudge)
}

/** The scale the framebuffer is currently drawn at — what canvas deltas divide by to become pixels. */
private fun currentScale(screen: VncScreenState, canvas: IntSize): Float {
    if (canvas.width <= 0 || canvas.height <= 0) return 0f
    return fitGeometry(
        canvas.width.toFloat(), canvas.height.toFloat(),
        screen.desktopSize.width, screen.desktopSize.height,
        screen.userScale, screen.userOffset.x, screen.userOffset.y,
    ).scale
}

private fun centroidOf(changes: List<PointerInputChange>): Offset {
    var sum = Offset.Zero
    changes.forEach { sum += it.position }
    return sum / changes.size.toFloat()
}

/** Mean distance from the [centroid] — the pinch measure, defined for any number of fingers. */
private fun spreadOf(changes: List<PointerInputChange>, centroid: Offset): Float {
    var sum = 0f
    changes.forEach { sum += (it.position - centroid).getDistance() }
    return sum / changes.size
}

// Gesture tuning. The double-tap window matches the platform's usual threshold; the wheel step is
// the finger travel one wheel click is worth; the edge margin keeps the cursor off the very border
// when the view pans under it.
private const val DOUBLE_TAP_WINDOW_MS = 300L
private const val ZOOM_DEADZONE = 0.02f
private const val WHEEL_STEP_PX = 60f
private const val EDGE_MARGIN_PX = 64f
private const val CURSOR_SIZE_PX = 22f
