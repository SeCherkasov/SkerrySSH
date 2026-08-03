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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.Dimension
import java.awt.MouseInfo

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
        // On X11 the WM moves the window itself (smooth, compositor-driven — see NativeWindowMove);
        // elsewhere fall back to moving it frame by frame from the app thread.
        val useNativeMove = NativeWindowMove.isAvailable()
        WindowChrome(
            isMaximized = { state.placement == WindowPlacement.Maximized },
            onMinimize = { state.isMinimized = true },
            onToggleMaximize = toggleMaximize,
            onClose = exit,
            dragArea = { content ->
                val doubleClick = Modifier.onUnconsumedDoubleClick(toggleMaximize)
                when {
                    state.placement == WindowPlacement.Maximized -> Box(doubleClick) { content() }
                    useNativeMove -> Box(doubleClick.then(Modifier.nativeWindowDrag(awtWindow))) { content() }
                    else -> Box(doubleClick.then(Modifier.inAppWindowDrag(awtWindow, isFloating))) { content() }
                }
            },
        )
    }
}

/**
 * Moves the window frame by frame from the app thread, for platforms without [NativeWindowMove].
 * Gated by [WindowDragGesture] (dead zone, floating only), and driven by absolute pointer positions
 * ([MouseInfo]) because local ones shift together with the window and would feed back. [isFloating]
 * is read per event, so a double-click that maximizes the window mid-gesture stops the drag instead
 * of dragging the now screen-sized window back to the floating origin (issue #76). Only unconsumed
 * presses arm it, so buttons/tabs that consume their own press are excluded.
 */
private fun Modifier.inAppWindowDrag(window: java.awt.Window, isFloating: () -> Boolean): Modifier =
    pointerInput(window) {
        val gesture = WindowDragGesture(viewConfiguration.touchSlop.toInt())
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = true)
            gesture.press(MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    val pointer = MouseInfo.getPointerInfo()?.location ?: continue
                    val target = gesture.drag(pointer, window.location, isFloating()) ?: continue
                    change.consume()
                    window.setLocation(target.x, target.y)
                }
            } finally {
                gesture.release()
            }
        }
    }

/**
 * Starts a native WM drag ([NativeWindowMove]) once the pointer moves past touch-slop from the
 * initial press — the slop threshold keeps clicks on titlebar buttons and the double-click-to-
 * maximize gesture working, since a plain click never crosses it. Only unconsumed presses arm the
 * drag, so buttons/tabs that consume their own press are excluded. Once handed off, the compositor
 * owns the pointer and this gesture simply ends.
 */
private fun Modifier.nativeWindowDrag(window: java.awt.Window): Modifier = pointerInput(window) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            if (!change.pressed) break // released without dragging — leave it for click/double-click
            if ((change.position - down.position).getDistance() > slop) {
                val mouse = MouseInfo.getPointerInfo()?.location
                if (mouse != null && NativeWindowMove.startMove(window, mouse.x, mouse.y)) {
                    change.consume()
                }
                break
            }
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
 *
 * Also declares how far the window may shrink, derived from [screen]: the strips clamp their own
 * drags, but a window-manager resize (keyboard resize, tiling, super+drag) never asks them, and
 * without the hint an undecorated window can be squeezed down to a few pixels. The floor follows
 * the display the app started on — moving the window to a smaller monitor does not recompute it.
 */
@Composable
fun WindowScope.SkerryWindowFrame(state: WindowState, screen: DpSize, content: @Composable () -> Unit) {
    val minimum = remember(screen) { minimumWindowDimension(screen) }
    // The window is not displayable yet, so AWT stores the value on it and the peer applies it when
    // realized — either way the hint is in place before the window is shown.
    DisposableEffect(minimum) {
        window.minimumSize = minimum
        onDispose { }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if (state.placement == WindowPlacement.Floating) ResizeBorders(minimum)
    }
}

// Grab thickness of the resize strips: edges and the corner squares.
private val EDGE = 5.dp
private val CORNER = 14.dp

@Composable
private fun WindowScope.ResizeBorders(minimum: Dimension) {
    Box(Modifier.fillMaxSize()) {
        // Edges (inset by CORNER so corners keep their diagonal cursor).
        ResizeStrip(ResizeEdge.Top, Cursor.N_RESIZE_CURSOR, minimum, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(EDGE).padding(horizontal = CORNER))
        ResizeStrip(ResizeEdge.Bottom, Cursor.S_RESIZE_CURSOR, minimum, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(EDGE).padding(horizontal = CORNER))
        ResizeStrip(ResizeEdge.Left, Cursor.W_RESIZE_CURSOR, minimum, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(EDGE).padding(vertical = CORNER))
        ResizeStrip(ResizeEdge.Right, Cursor.E_RESIZE_CURSOR, minimum, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(EDGE).padding(vertical = CORNER))
        // Corners on top of the edges.
        ResizeStrip(ResizeEdge.TopLeft, Cursor.NW_RESIZE_CURSOR, minimum, Modifier.align(Alignment.TopStart).size(CORNER))
        ResizeStrip(ResizeEdge.TopRight, Cursor.NE_RESIZE_CURSOR, minimum, Modifier.align(Alignment.TopEnd).size(CORNER))
        ResizeStrip(ResizeEdge.BottomLeft, Cursor.SW_RESIZE_CURSOR, minimum, Modifier.align(Alignment.BottomStart).size(CORNER))
        ResizeStrip(ResizeEdge.BottomRight, Cursor.SE_RESIZE_CURSOR, minimum, Modifier.align(Alignment.BottomEnd).size(CORNER))
    }
}

@Composable
private fun WindowScope.ResizeStrip(edge: ResizeEdge, cursor: Int, minimum: Dimension, modifier: Modifier) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            // Keyed on the floor too: a strip that kept the old one would compute the fixed side of
            // a left/top drag from a width that AWT then clamps to the new floor, sliding that side.
            .pointerInput(edge, minimum) {
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
                            minimum.width, minimum.height,
                        )
                    }
                }
            },
    )
}
