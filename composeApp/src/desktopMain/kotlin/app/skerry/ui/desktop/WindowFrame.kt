package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.window.WindowDraggableArea
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

/**
 * Custom chrome for the undecorated main window: dragging via [WindowDraggableArea] (with
 * double-click on empty titlebar space toggling maximize), minimize/maximize through
 * [WindowState], close via [exit]. A maximized window is not draggable (only double-click
 * restores it) — moving a maximized AWT window would desync placement from the real bounds.
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
        // On X11 the WM moves the window itself (smooth, compositor-driven — see NativeWindowMove);
        // elsewhere fall back to Compose's frame-by-frame WindowDraggableArea.
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
                    else -> WindowDraggableArea(doubleClick) { content() }
                }
            },
        )
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
