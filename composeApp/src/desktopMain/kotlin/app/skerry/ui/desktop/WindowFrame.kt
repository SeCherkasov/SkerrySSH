package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Point
import kotlin.time.Duration.Companion.milliseconds

/**
 * Custom chrome for the undecorated main window: dragging the empty titlebar space (with
 * double-click on it toggling maximize), minimize/maximize through [WindowState], close via [exit].
 * A maximized window is not draggable (only double-click restores it) — moving a maximized AWT
 * window would desync placement from the real bounds.
 */
@Composable
fun WindowScope.rememberSkerryWindowChrome(state: WindowState, exit: () -> Unit): WindowChrome {
    val awtWindow = window
    return remember(state, exit) {
        val toggleMaximize = {
            state.placement =
                if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating
                else WindowPlacement.Maximized
        }
        val isFloating = { state.placement == WindowPlacement.Floating }
        // On X11 the WM is asked to move the window itself (smooth, compositor-driven — see
        // NativeWindowMove); where it doesn't answer, the same gesture drags the window from the app
        // thread instead.
        val useNativeMove = NativeWindowMove.isAvailable()
        // One per window, not per drag area: whether this WM answers at all is a property of the
        // session, and a pointer-input node is rebuilt whenever the screen around it changes.
        val nativeMoveWatch = NativeMoveWatch(NATIVE_MOVE_SETTLE)
        WindowChrome(
            isMaximized = { state.placement == WindowPlacement.Maximized },
            onMinimize = { state.isMinimized = true },
            onToggleMaximize = toggleMaximize,
            onClose = exit,
            dragArea = { content ->
                val doubleClick = Modifier.onUnconsumedDoubleClick(toggleMaximize)
                when {
                    state.placement == WindowPlacement.Maximized -> Box(doubleClick) { content() }
                    else -> Box(
                        doubleClick.then(Modifier.windowDrag(awtWindow, isFloating, useNativeMove, nativeMoveWatch)),
                    ) { content() }
                }
            },
        )
    }
}

// How long a requested native move has to actually move the window before the drag stops waiting
// for the WM. Long enough for a compositor round-trip, short enough to pass for a slow first frame —
// and paid only while the WM still looks like it might answer (see NativeMoveWatch.worthTrying).
private val NATIVE_MOVE_SETTLE = 60.milliseconds

/** The real window behind the drag arbitration in [WindowDragArbiter]. */
private class AwtDragTarget(private val window: java.awt.Window) : DragTarget {

    override val origin: Point get() = window.location

    override fun moveTo(target: Point) = window.setLocation(target.x, target.y)

    override fun startNativeMove(pointer: Point) = NativeWindowMove.startMove(window, pointer.x, pointer.y)

    override fun cancelNativeMove(pointer: Point) = NativeWindowMove.cancelMove(window, pointer.x, pointer.y)
}

/**
 * Drags the undecorated window by its titlebar. Where [native] is available the WM is asked to take
 * the window over ([NativeWindowMove]) so the move is compositor-driven and smooth; that request is
 * fire-and-forget, and a WM that ignores it would otherwise leave the window frozen under the
 * pointer, so [NativeMoveWatch] gives it [NATIVE_MOVE_SETTLE] and then hands the gesture back to the
 * in-app drag ([DragMode]). A WM that does take the window swallows the rest of the events, so the
 * gesture ends on the first event that arrives after the window moved — otherwise it would sit here
 * waiting for a release that never comes and the next press would land inside the stale gesture.
 *
 * Driven by absolute pointer positions ([MouseInfo]) because local ones shift together with the
 * window and would feed back. [isFloating] is read per event, so a double-click that maximizes the
 * window mid-gesture stops the drag instead of dragging the now screen-sized window back to the
 * floating origin (issue #76). Only unconsumed presses arm it, so buttons/tabs that consume their
 * own press are excluded.
 */
private fun Modifier.windowDrag(
    window: java.awt.Window,
    isFloating: () -> Boolean,
    native: Boolean,
    watch: NativeMoveWatch,
): Modifier = pointerInput(window, native) {
    val slop = viewConfiguration.touchSlop
    val arbiter = WindowDragArbiter(AwtDragTarget(window), slop.toInt(), watch, native)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        arbiter.press(MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture)
        try {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                if (!change.pressed) break // released — left for click/double-click
                val pointer = MouseInfo.getPointerInfo()?.location ?: continue
                val pastDeadZone = (change.position - down.position).getDistance() > slop
                val step = arbiter.moved(pointer, pastDeadZone, isFloating())
                if (step.consume) change.consume()
                if (step.mode == null) break // the WM has the window; the next press starts fresh
            }
        } finally {
            arbiter.release()
        }
    }
}

/**
 * Double-click detector that never consumes events: buttons/tabs inside the drag area consume
 * their presses first (requireUnconsumed filters those out), and the window-drag handler on the
 * same box keeps seeing the unconsumed downs it needs.
 */
private fun Modifier.onUnconsumedDoubleClick(onDoubleClick: () -> Unit): Modifier = pointerInput(onDoubleClick) {
    var lastTime = Long.MIN_VALUE
    var lastPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown()
        val slop = viewConfiguration.touchSlop * 4
        if (down.uptimeMillis - lastTime <= viewConfiguration.doubleTapTimeoutMillis &&
            (down.position - lastPos).getDistance() <= slop
        ) {
            lastTime = Long.MIN_VALUE
            onDoubleClick()
        } else {
            lastTime = down.uptimeMillis
            lastPos = down.position
        }
    }
}

/**
 * Content wrapper for the undecorated window: draws [content] and, while the window is floating
 * (not maximized), overlays invisible resize strips along the borders — an undecorated AWT window
 * has no native resize edges, so edge drags call [resizedWindowBounds] over `window.setBounds`.
 */
@Composable
fun WindowScope.SkerryWindowFrame(state: WindowState, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (state.placement == WindowPlacement.Floating) ResizeBorders()
    }
}

// Grab thickness of the resize strips: edges and the corner squares.
private val EDGE = 5.dp
private val CORNER = 14.dp

@Composable
private fun WindowScope.ResizeBorders() {
    Box(Modifier.fillMaxSize()) {
        // Edges (inset by CORNER so corners keep their diagonal cursor).
        ResizeStrip(ResizeEdge.Top, Cursor.N_RESIZE_CURSOR, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EDGE).padding(horizontal = CORNER))
        ResizeStrip(ResizeEdge.Bottom, Cursor.S_RESIZE_CURSOR, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EDGE).padding(horizontal = CORNER))
        ResizeStrip(ResizeEdge.Left, Cursor.W_RESIZE_CURSOR, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE).padding(vertical = CORNER))
        ResizeStrip(ResizeEdge.Right, Cursor.E_RESIZE_CURSOR, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE).padding(vertical = CORNER))
        // Corners on top of the edges.
        ResizeStrip(ResizeEdge.TopLeft, Cursor.NW_RESIZE_CURSOR, Modifier.align(Alignment.TopStart).size(CORNER))
        ResizeStrip(ResizeEdge.TopRight, Cursor.NE_RESIZE_CURSOR, Modifier.align(Alignment.TopEnd).size(CORNER))
        ResizeStrip(ResizeEdge.BottomLeft, Cursor.SW_RESIZE_CURSOR, Modifier.align(Alignment.BottomStart).size(CORNER))
        ResizeStrip(ResizeEdge.BottomRight, Cursor.SE_RESIZE_CURSOR, Modifier.align(Alignment.BottomEnd).size(CORNER))
    }
}

@Composable
private fun WindowScope.ResizeStrip(edge: ResizeEdge, cursor: Int, modifier: Modifier) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            .pointerInput(edge) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    val startBounds = window.bounds
                    // The drag is tracked in absolute screen coordinates (MouseInfo): local pointer
                    // positions shift together with the window being resized and would feed back.
                    val startMouse = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.all { !it.pressed }) break
                        event.changes.forEach { it.consume() }
                        val mouse = MouseInfo.getPointerInfo()?.location ?: continue
                        window.bounds = resizedWindowBounds(
                            startBounds, edge,
                            mouse.x - startMouse.x, mouse.y - startMouse.y,
                            MIN_WINDOW.width.value.toInt(), MIN_WINDOW.height.value.toInt(),
                        )
                    }
                }
            },
    )
}
