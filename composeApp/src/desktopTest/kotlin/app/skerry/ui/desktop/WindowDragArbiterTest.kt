package app.skerry.ui.desktop

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Drives the drag arbitration without a real window: the WM's answer is just a flag here. */
private class FakeDragTarget(
    start: Point = Point(100, 200),
    private val wmAccepts: Boolean = true,
) : DragTarget {

    override var origin: Point = start
        private set

    var nativeMoveRequests: Int = 0
        private set

    var nativeMovesCancelled: Int = 0
        private set

    override fun moveTo(target: Point) {
        origin = Point(target)
    }

    override fun startNativeMove(pointer: Point): Boolean {
        nativeMoveRequests++
        return wmAccepts
    }

    override fun cancelNativeMove(pointer: Point): Boolean {
        nativeMovesCancelled++
        cancelledAt = Point(pointer)
        return true
    }

    /** Where the last withdrawal was addressed from; `null` until one happens. */
    var cancelledAt: Point? = null
        private set

    /** The WM moving the window itself, behind the app's back. */
    fun movedByWindowManager(to: Point) {
        origin = Point(to)
    }
}

class WindowDragArbiterTest {

    private val settle = 60.milliseconds
    private val time = TestTimeSource()

    private val watch = NativeMoveWatch(settle, time)

    private fun arbiter(target: DragTarget, native: Boolean = true) =
        WindowDragArbiter(target, deadZone = 18, watch = watch, native = native)

    /** A press that never leaves the dead zone is a click, not a drag: nobody moves the window. */
    @Test
    fun insideTheDeadZoneNothingHappens() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        val step = arbiter.moved(Point(505, 25), pastDeadZone = false, floating = true)
        assertEquals(DragStep(DragMode.Idle, consume = false), step)
        assertEquals(0, target.nativeMoveRequests)
        assertEquals(Point(100, 200), target.origin)
    }

    @Test
    fun pastTheDeadZoneTheWindowManagerIsAskedFirst() {
        val target = FakeDragTarget(wmAccepts = true)
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        val step = arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        assertEquals(DragStep(DragMode.HandedToWm, consume = true), step)
        assertEquals(1, target.nativeMoveRequests)
    }

    @Test
    fun aWindowManagerThatRefusesOutrightLeavesTheDragToTheApp() {
        val target = FakeDragTarget(wmAccepts = false)
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        assertEquals(DragMode.InApp, arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true).mode)
        // Taken over at that very pointer, so the next move drags from there without a jump.
        arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true)
        assertEquals(Point(110, 210), target.origin)
    }

    @Test
    fun whileTheWindowManagerMayStillAnswerTheAppKeepsItsHandsOff() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        time += settle - 1.milliseconds
        val step = arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true)
        assertEquals(DragStep(DragMode.HandedToWm, consume = false), step)
        assertEquals(Point(100, 200), target.origin)
    }

    /** The bug this exists for: the WM reports success, does nothing, and the window stays put. */
    @Test
    fun aWindowManagerThatNeverMovedTheWindowLosesTheGesture() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        time += settle
        assertEquals(DragMode.InApp, arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true).mode)
        // The stale request is withdrawn so a late WM can't fight the in-app drag...
        assertEquals(1, target.nativeMovesCancelled)
        // ...the take-over itself is jump-free...
        assertEquals(Point(100, 200), target.origin)
        // ...and from there the window follows the pointer.
        arbiter.moved(Point(580, 90), pastDeadZone = true, floating = true)
        assertEquals(Point(110, 220), target.origin)
    }

    /**
     * The WM took the window, so it also owns the release the app never sees: the gesture ends here
     * instead of waiting for it, which is what would otherwise leave the titlebar dead.
     */
    @Test
    fun onceTheWindowManagerMovedTheWindowTheGestureEnds() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        target.movedByWindowManager(Point(300, 400))
        time += settle * 10
        assertNull(arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true).mode)
        assertEquals(0, target.nativeMovesCancelled)
    }

    /**
     * The release after such a gesture belongs to the compositor: withdrawing the request here would
     * cancel a move the WM is in the middle of performing.
     */
    @Test
    fun releasingAfterTheWindowManagerTookOverWithdrawsNothing() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        target.movedByWindowManager(Point(300, 400))
        arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true)
        arbiter.release()
        assertEquals(0, target.nativeMovesCancelled)
        assertTrue(watch.worthTrying)
    }

    /**
     * Issue #76 again, one step earlier: the window is maximized before the pointer crosses the
     * dead zone, so the WM must not be handed a window it would restore behind WindowState's back.
     */
    @Test
    fun aWindowMaximizedBeforeTheDragStartedIsNeverHandedToTheWindowManager() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        val step = arbiter.moved(Point(560, 60), pastDeadZone = true, floating = false)
        assertEquals(DragStep(DragMode.InApp, consume = false), step)
        assertEquals(0, target.nativeMoveRequests)
        assertEquals(Point(100, 200), target.origin)
    }

    /**
     * The window is maximized while the WM still owes an answer: the request goes back immediately,
     * or the WM could pick it up and drag the maximized window.
     */
    @Test
    fun maximizingWhileTheWindowManagerStillOwesAnAnswerWithdrawsTheRequest() {
        val target = FakeDragTarget(wmAccepts = true)
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        assertEquals(1, target.nativeMoveRequests)
        arbiter.moved(Point(600, 100), pastDeadZone = true, floating = false)
        assertEquals(1, target.nativeMovesCancelled)
        assertEquals(Point(100, 200), target.origin)
    }

    /** Issue #76: the double-click maximizes the window while the button is still down. */
    @Test
    fun aWindowMaximizedMidGestureIsNeverMoved() {
        val target = FakeDragTarget(wmAccepts = false)
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        val step = arbiter.moved(Point(600, 100), pastDeadZone = true, floating = false)
        assertEquals(DragStep(DragMode.InApp, consume = false), step)
        assertEquals(Point(100, 200), target.origin)
    }

    @Test
    fun withoutNativeSupportTheWindowManagerIsNeverAsked() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target, native = false)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        assertEquals(0, target.nativeMoveRequests)
        assertEquals(DragMode.InApp, arbiter.mode)
    }

    /** The settle delay is paid while the WM might still answer — not on every drag forever. */
    @Test
    fun aWindowManagerThatKeepsIgnoringUsIsEventuallyDroppedForTheSession() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        repeat(2) {
            arbiter.press(Point(500, 20))
            arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
            time += settle
            arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true)
            arbiter.release()
        }
        val asked = target.nativeMoveRequests

        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        assertEquals(DragMode.InApp, arbiter.mode)
        assertEquals(asked, target.nativeMoveRequests)
    }

    /**
     * The drag areas are rebuilt whenever the screen around them changes, so a verdict that only
     * lived as long as one arbiter meant every rebuild paid the settle delay all over again — which
     * is exactly what made the fallback feel unusable.
     */
    @Test
    fun theVerdictSurvivesArbitersBeingRebuilt() {
        val target = FakeDragTarget()
        repeat(2) {
            val arbiter = arbiter(target)
            arbiter.press(Point(500, 20))
            arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
            time += settle
            arbiter.moved(Point(570, 70), pastDeadZone = true, floating = true)
            arbiter.release()
        }
        val asked = target.nativeMoveRequests

        val rebuilt = arbiter(target)
        rebuilt.press(Point(500, 20))
        rebuilt.moved(Point(560, 60), pastDeadZone = true, floating = true)
        assertEquals(DragMode.InApp, rebuilt.mode)
        assertEquals(asked, target.nativeMoveRequests)
    }

    /**
     * The button comes up while the WM still owes an answer: the request has to be withdrawn, or a
     * WM answering afterwards would move a window nobody is dragging any more.
     */
    @Test
    fun releasingWhileWaitingWithdrawsTheRequest() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        arbiter.release()
        assertEquals(1, target.nativeMovesCancelled)
        assertEquals(Point(560, 60), target.cancelledAt)
    }

    /** Same, but the settle window had not elapsed yet — the request is just as outstanding. */
    @Test
    fun releasingBeforeTheSettleWindowElapsedAlsoWithdrawsTheRequest() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        time += settle - 1.milliseconds
        arbiter.release()
        assertEquals(1, target.nativeMovesCancelled)
    }

    /**
     * The compositor usually swallows every event of a move it owns, release included, so the app
     * can reach the release still nominally waiting. Withdrawing there would cancel a move the WM
     * already performed — the window position is what settles it.
     */
    @Test
    fun releasingAfterAWindowManagerMoveWithNoEventsInBetweenWithdrawsNothing() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        target.movedByWindowManager(Point(300, 400))
        time += settle * 10
        arbiter.release()
        assertEquals(0, target.nativeMovesCancelled)
        assertTrue(watch.worthTrying)
    }

    /** A gesture dropped after the settle window elapsed counts as a refusal like any other. */
    @Test
    fun releasingAfterTheSettleWindowElapsedCountsAsARefusal() {
        val target = FakeDragTarget()
        val arbiter = arbiter(target)
        repeat(2) {
            arbiter.press(Point(500, 20))
            arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
            time += settle
            arbiter.release()
        }
        val asked = target.nativeMoveRequests

        arbiter.press(Point(500, 20))
        arbiter.moved(Point(560, 60), pastDeadZone = true, floating = true)
        assertEquals(DragMode.InApp, arbiter.mode)
        assertEquals(asked, target.nativeMoveRequests)
    }
}
