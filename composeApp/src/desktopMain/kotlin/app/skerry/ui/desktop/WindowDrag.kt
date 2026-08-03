package app.skerry.ui.desktop

import java.awt.Point
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where a titlebar drag should put the window when the app moves it itself. Fed absolute pointer
 * positions, it answers with the window origin to apply, or `null` while the window has to stay put.
 *
 * This is the drag on platforms the window manager can't do it for ([NativeWindowMove]) — and also
 * on those where it can but won't, since a WM that ignores `_NET_WM_MOVERESIZE` hands the gesture
 * back here (see [NativeMoveWatch]).
 *
 * Two cases deliberately stay put: a press that hasn't left [deadZone] yet, so a plain click and the
 * double-click-to-maximize gesture never nudge the window, and a window that stopped floating
 * mid-gesture — the double-click maximizes it while the button is still down, and following the
 * pointer from the pre-maximize origin would then park a screen-sized window at the old top-left,
 * pushing its right/bottom edges off-screen.
 */
class WindowDragGesture(private val deadZone: Int) {

    private var pressedAt: Point? = null

    /**
     * Window origin minus pointer, captured once the drag leaves the dead zone — or straight away
     * when a drag already under way is taken over ([takeOver]); `null` until then.
     */
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

    /**
     * Claims a drag already under way, with [pointer] over the window sitting at [windowOrigin]:
     * the dead zone is behind us (the gesture crossed it before the WM was asked to take the window
     * and didn't), so the very next [drag] follows the pointer from here without moving the window.
     */
    fun takeOver(pointer: Point, windowOrigin: Point) {
        pressedAt = Point(pointer)
        grab = Point(windowOrigin.x - pointer.x, windowOrigin.y - pointer.y)
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

/**
 * Watches whether the window manager actually picked up a [NativeWindowMove] request. Asking it to
 * move the window is fire-and-forget — the client message is delivered (and reports success) even
 * when the WM does nothing with it, which is what GNOME 50's Mutter does to this window, leaving it
 * frozen under the pointer. So the drag gives the WM [settle] to move the window; if it hasn't, the
 * gesture goes back to dragging in-app.
 *
 * Once the window has moved the WM owns the gesture for good: it may well move the window back over
 * the original spot, and that must not read as "the WM ignored us".
 *
 * One request at a time: the window has a single titlebar and a single pointer, so a second
 * [started] before the first is settled would overwrite a cycle still in flight.
 */
class NativeMoveWatch(
    private val settle: Duration,
    private val source: TimeSource = TimeSource.Monotonic,
) {

    private var startedAt: TimeMark? = null
    private var startOrigin: Point? = null
    private var wmTookOver = false
    private var ignoredInARow = 0

    /**
     * False once the WM has ignored [IGNORES_BEFORE_GIVING_UP] drags in a row: paying the settle
     * delay on every drag is what makes dragging unusable, so the native path is dropped for the
     * session and the gesture drags in-app from the first pointer move. It takes more than one
     * refusal because a single slow compositor frame must not cost the smooth drag for good.
     */
    val worthTrying: Boolean get() = ignoredInARow < IGNORES_BEFORE_GIVING_UP

    /** A native move was just requested, with the window at [windowOrigin]. */
    fun started(windowOrigin: Point) {
        startedAt = source.markNow()
        startOrigin = Point(windowOrigin)
        wmTookOver = false
    }

    /** True once the WM has moved the window, which means it owns this gesture. */
    fun wmTookOver(windowOrigin: Point): Boolean {
        val startOrigin = this.startOrigin ?: return false
        if (windowOrigin != startOrigin) {
            wmTookOver = true
            // It answered, so whatever it did before was not a pattern.
            ignoredInARow = 0
        }
        return wmTookOver
    }

    /** True once it's clear the WM will not move the window and the in-app drag should take over. */
    fun givenUp(windowOrigin: Point): Boolean {
        val startedAt = this.startedAt ?: return false
        if (wmTookOver(windowOrigin)) return false
        if (startedAt.elapsedNow() < settle) return false
        // This gesture is settled either way; a second call must not count as a second refusal.
        this.startedAt = null
        ignoredInARow++
        return true
    }

    private companion object {
        const val IGNORES_BEFORE_GIVING_UP = 2
    }
}

/** The window being dragged, as the drag arbitration sees it: an origin and two ways to move it. */
interface DragTarget {

    /** The window's current top-left on screen. */
    val origin: Point

    /** Moves the window itself, frame by frame from the app thread. */
    fun moveTo(target: Point)

    /** Asks the WM to take the window over from [pointer]; false when it refuses outright. */
    fun startNativeMove(pointer: Point): Boolean

    /**
     * Withdraws a native move the WM never acted on, so a late WM can't move a window nobody is
     * dragging any more. False when the request could not be withdrawn.
     */
    fun cancelNativeMove(pointer: Point): Boolean
}

/** Who is moving the window during a titlebar drag. */
enum class DragMode {
    /** Nothing dragged yet: waiting for the pointer to leave the dead zone. */
    Idle,

    /** The WM was asked to take the window; watching whether it does. */
    HandedToWm,

    /** This gesture moves the window itself, frame by frame. */
    InApp,

    /** Over: the WM took the window, and the release belongs to the compositor, not to the app. */
    Ended,
}

/**
 * What the gesture does next, and whether the pointer event that caused it belongs to the drag.
 * A `null` [mode] ends the gesture: the WM owns the window from here, and the next press starts a
 * fresh one.
 */
data class DragStep(val mode: DragMode?, val consume: Boolean)

/**
 * Decides, press by press and move by move, who drags the window: the WM (asked once per gesture,
 * where [native] support exists) or the app itself.
 *
 * [watch] is passed in rather than owned because it carries the verdict on a WM that ignores
 * `_NET_WM_MOVERESIZE`, and that has to outlive this arbiter: an arbiter belongs to one pointer-input
 * node (the titlebar, the vault gate — each drag area has its own, and a node is rebuilt whenever
 * the screen around it changes), while the WM's behaviour belongs to the window.
 */
class WindowDragArbiter(
    private val target: DragTarget,
    deadZone: Int,
    private val watch: NativeMoveWatch,
    private val native: Boolean,
) {

    private val gesture = WindowDragGesture(deadZone)

    /** Where the pointer was last seen, so a gesture cut short can still address the WM. */
    private var lastPointer = Point()

    /** Who is dragging right now; only meaningful between [press] and [release]. */
    var mode: DragMode = DragMode.InApp
        private set

    /** Button pressed at absolute [pointer]. A WM known to ignore us is not asked again. */
    fun press(pointer: Point) {
        gesture.press(pointer)
        lastPointer = Point(pointer)
        mode = if (native && watch.worthTrying) DragMode.Idle else DragMode.InApp
    }

    /**
     * Pointer moved to absolute [pointer] with the button still down; [pastDeadZone] tells whether
     * it has travelled far enough to count as a drag rather than a click, [floating] whether the
     * window is still floating.
     */
    fun moved(pointer: Point, pastDeadZone: Boolean, floating: Boolean): DragStep {
        lastPointer = Point(pointer)
        // A window maximized mid-gesture is nobody's to drag — not the app's (issue #76), and not
        // the WM's either, which would restore it behind WindowState's back. A request the WM has
        // not acted on yet goes back now rather than at the release, so it can't move the maximized
        // window in the meantime.
        if (!floating) {
            if (mode == DragMode.HandedToWm) withdrawRequest()
            mode = DragMode.InApp
            return moveWindow(pointer, floating = false)
        }
        val step = when (mode) {
            DragMode.Idle -> handOffToWm(pointer, pastDeadZone)
            DragMode.HandedToWm -> reclaimIfIgnored(pointer)
            DragMode.InApp, DragMode.Ended -> moveWindow(pointer, floating)
        }
        // A step without a mode ends the gesture, and the arbiter has to know: a later release must
        // not withdraw a move the WM is actually performing.
        mode = step.mode ?: DragMode.Ended
        return step
    }

    /**
     * Button released. A gesture that ends after the settle window elapsed without the WM moving
     * anything counts as a refusal like any other — that is what stops the next drag from paying the
     * settle delay again. Either way the request is withdrawn on the way out, or a WM answering late
     * would move a window nobody is dragging any more.
     */
    fun release() {
        // A WM that took the window usually swallows every event including this release, so the
        // gesture can reach here still nominally waiting: check the window itself before withdrawing
        // anything, or a completed move would be cancelled after the fact.
        if (mode == DragMode.HandedToWm && !watch.wmTookOver(target.origin)) {
            reportGiveUp()
            withdrawRequest()
        }
        mode = DragMode.Ended
        gesture.release()
    }

    /**
     * Hands the window to the WM once the pointer left the dead zone — which is what keeps a plain
     * click (and the double-click-to-maximize) from starting a move. A WM that refuses outright
     * drops the gesture straight into the in-app drag.
     */
    private fun handOffToWm(pointer: Point, pastDeadZone: Boolean): DragStep {
        if (!pastDeadZone) return DragStep(DragMode.Idle, consume = false)
        if (!target.startNativeMove(pointer)) {
            gesture.takeOver(pointer, target.origin)
            return DragStep(DragMode.InApp, consume = false)
        }
        watch.started(target.origin)
        return DragStep(DragMode.HandedToWm, consume = true)
    }

    /**
     * Pointer events still arriving means the compositor never grabbed the pointer; once the window
     * has stayed put for the settle window, this gesture takes it back and drags it itself.
     *
     * If the window did move, the WM owns the drag and the app is out of the loop: whatever event
     * shows up here belongs to the next gesture, so this one ends rather than waiting for a release
     * the compositor swallowed — waiting forever is what would leave the titlebar dead.
     */
    private fun reclaimIfIgnored(pointer: Point): DragStep {
        if (watch.wmTookOver(target.origin)) return DragStep(mode = null, consume = false)
        if (!reportGiveUp()) return DragStep(DragMode.HandedToWm, consume = false)
        withdrawRequest()
        gesture.takeOver(pointer, target.origin)
        return DragStep(DragMode.InApp, consume = true)
    }

    private fun withdrawRequest() {
        if (target.cancelNativeMove(lastPointer)) return
        System.err.println("Skerry: could not withdraw the pending window move from the window manager")
    }

    private fun moveWindow(pointer: Point, floating: Boolean): DragStep {
        val origin = gesture.drag(pointer, target.origin, floating)
            ?: return DragStep(DragMode.InApp, consume = false)
        target.moveTo(origin)
        return DragStep(DragMode.InApp, consume = true)
    }

    /**
     * Asks the watch for its verdict and says so once — a WM that silently drops the request is the
     * bug this whole fallback exists for, and it should leave a trace instead of only a slow drag.
     */
    private fun reportGiveUp(): Boolean {
        if (!watch.givenUp(target.origin)) return false
        System.err.println(
            "Skerry: the window manager ignored _NET_WM_MOVERESIZE; dragging the window in-app from here on",
        )
        return true
    }
}
