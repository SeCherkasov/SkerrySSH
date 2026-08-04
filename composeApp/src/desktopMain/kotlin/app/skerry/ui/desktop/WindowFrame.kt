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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import java.awt.Point
import java.awt.Rectangle

/**
 * Custom chrome for the undecorated main window: dragging the empty titlebar space (with
 * double-click on it toggling maximize), minimize/maximize through [WindowState], close via [exit].
 * Dragging a maximized window restores it under the pointer and keeps going, the way a native
 * titlebar does — see [titlebarDrag].
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
        // elsewhere fall back to moving it frame by frame from the app thread.
        val useNativeMove = NativeWindowMove.isAvailable()
        val fullscreen = FullscreenToggle({ state.placement }, { state.placement = it })
        WindowChrome(
            isMaximized = { state.placement == WindowPlacement.Maximized },
            onMinimize = { state.isMinimized = true },
            onToggleMaximize = toggleMaximize,
            onClose = exit,
            setFullscreen = fullscreen::apply,
            dragArea = { content ->
                val doubleClick = Modifier.onUnconsumedDoubleClick(toggleMaximize)
                Box(doubleClick.then(Modifier.titlebarDrag(awtWindow, state, useNativeMove))) { content() }
            },
        )
    }
}

// How many pointer events to give the window manager to answer a restore before the drag carries on
// regardless. The wait is counted in events, not milliseconds, because it happens inside the pointer
// handler: a drag delivers events continuously, and counting them keeps a release visible instantly.
private const val RESTORE_EVENTS = 16

/**
 * The one titlebar drag gesture. It branches per press instead of per placement, because a
 * placement-keyed modifier would be torn down (cancelling the gesture) the moment the restore below
 * changes it — mid-drag, before the window is ever placed under the pointer.
 *
 * A maximized window is restored to its floating size and put back under the pointer first
 * ([restoredWindowOrigin]); a floating one is dragged as is. Either way the move itself goes to the
 * window manager where one is reachable ([NativeWindowMove], smooth and compositor-driven), and is
 * otherwise walked frame by frame from the app thread ([followPointer]).
 *
 * Everything happens inside the pointer handler, with no suspension outside it: Compose delivers an
 * event only to a handler parked in `awaitPointerEvent`, so a release during a wait taken elsewhere
 * would go unseen — and the WM move would then start with no button held, leaving the window stuck
 * to the cursor until the next click.
 *
 * Only unconsumed presses arm it, so buttons and tabs inside the drag area keep their own clicks,
 * and a press that never leaves touch-slop is left alone for the double-click handler.
 */
private fun Modifier.titlebarDrag(
    window: java.awt.Window,
    state: WindowState,
    useNativeMove: Boolean,
): Modifier = pointerInput(window, state, useNativeMove) {
    val slop = viewConfiguration.touchSlop
    val isFloating = { state.placement == WindowPlacement.Floating }
    awaitEachGesture {
        var pointer = awaitDragStart(slop) ?: return@awaitEachGesture
        if (state.placement == WindowPlacement.Maximized) {
            val maximized = window.bounds
            state.placement = WindowPlacement.Floating
            // The window manager answers a restore a frame or two later, and the pointer keeps
            // travelling meanwhile: read it again afterwards, or the window lands under where the
            // cursor was and then jumps the travelled distance when the move starts.
            val restored = awaitRestoredSize(window, maximized) ?: return@awaitEachGesture
            pointer = MouseInfo.getPointerInfo()?.location ?: pointer
            window.location = restoredWindowOrigin(maximized, restored, pointer)
        }
        if (useNativeMove && NativeWindowMove.startMove(window, pointer.x, pointer.y)) return@awaitEachGesture
        followPointer(window, pointer, isFloating)
    }
}

/**
 * Waits for a press that turns into a drag and answers with the absolute pointer position it
 * reached, or null when the press ended as a click. Absolute ([MouseInfo]) rather than local,
 * because local positions travel with the window being moved and would feed back into the drag.
 */
private suspend fun AwaitPointerEventScope.awaitDragStart(slop: Float): Point? {
    val down = awaitFirstDown(requireUnconsumed = true)
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: return null
        if (!change.pressed) return null // released without dragging — leave it for click/double-click
        if ((change.position - down.position).getDistance() <= slop) continue
        // Consumed only once there is a position to drag from: an unconsumed press still reaches the
        // double-click detector on the same box, a consumed one would be swallowed for nothing.
        val pointer = MouseInfo.getPointerInfo()?.location ?: return null
        change.consume()
        return pointer
    }
}

/**
 * The window's size once the restore lands. Events are consumed while waiting, so a release is seen
 * at once (null — the drag is over). A window manager that restores to the very size the window had
 * while maximized, or one that never answers, ends the wait after [RESTORE_EVENTS] events: the drag
 * then carries on with the size the window has rather than being dropped on the floor.
 */
private suspend fun AwaitPointerEventScope.awaitRestoredSize(
    window: java.awt.Window,
    maximized: Rectangle,
): Dimension? {
    repeat(RESTORE_EVENTS) {
        if (window.bounds != maximized) return window.size
        val change = awaitPointerEvent().changes.firstOrNull() ?: return null
        if (!change.pressed) return null
        change.consume()
    }
    return window.size
}

/**
 * Moves the window with the pointer until the button is released, for platforms the window manager
 * can't do it for. Gated by [WindowDragGesture], which also stops the drag if the window stops
 * floating mid-gesture (a double-click maximizes it while the button is still down — issue #76).
 * The dead zone is zero here: it was already crossed by [awaitDragStart].
 */
private suspend fun AwaitPointerEventScope.followPointer(
    window: java.awt.Window,
    from: Point,
    isFloating: () -> Boolean,
) {
    val gesture = WindowDragGesture(deadZone = 0)
    gesture.press(from)
    try {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: return
            if (!change.pressed) return
            val pointer = MouseInfo.getPointerInfo()?.location ?: continue
            val target = gesture.drag(pointer, window.location, isFloating()) ?: continue
            change.consume()
            window.setLocation(target.x, target.y)
        }
    } finally {
        gesture.release()
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
