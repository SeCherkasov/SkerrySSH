package app.skerry.ui.desktop

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The grab arithmetic of an already-armed drag. What makes a press a drag in the first place is the
 * touch-slop gate upstream in `awaitDragStart`, covered by [TitlebarDoubleClickTest] against the
 * production modifier chain — this class never sees a click.
 */
class WindowDragGestureTest {

    private val origin = Point(100, 200)

    private fun gesture() = WindowDragGesture().apply { press() }

    @Test
    fun firstEventCapturesTheGrabAndMovesNothing() {
        val gesture = gesture()
        // The drag is handed over already in progress, so the first event has nothing to measure a
        // delta from: it only records where the window sits relative to the pointer.
        assertNull(gesture.drag(Point(500, 20), origin, floating = true))
        // ...and every event after it moves the window by the pointer delta.
        assertEquals(Point(101, 200), gesture.drag(Point(501, 20), origin, floating = true))
        assertEquals(Point(140, 215), gesture.drag(Point(540, 35), origin, floating = true))
    }

    /** Issue #76: the double-click maximizes the window while the button is still down. */
    @Test
    fun windowMaximizedMidGestureIsNeverMoved() {
        val gesture = gesture()
        // The maximized window sits at (0,0); moves keep arriving until the button goes up, and not
        // one of them may reposition it (the second one is what a leaked drag would answer).
        assertNull(gesture.drag(Point(560, 40), Point(0, 0), floating = false))
        assertNull(gesture.drag(Point(600, 80), Point(0, 0), floating = false))
        assertNull(gesture.drag(Point(900, 400), Point(0, 0), floating = false))
    }

    @Test
    fun maximizingMidGestureEndsTheGestureForGood() {
        val gesture = gesture()
        // A drag is already under way — grab captured, window following the pointer...
        assertNull(gesture.drag(Point(530, 20), origin, floating = true))
        assertEquals(Point(140, 215), gesture.drag(Point(570, 35), origin, floating = true))
        // ...when the window stops floating (double-click, or the WM's own drag-to-top maximize).
        assertNull(gesture.drag(Point(600, 80), origin, floating = false))
        // Placement flips back to floating (restore) without releasing the button: still no move,
        // otherwise the window would jump to the stale grab captured before the maximize.
        assertNull(gesture.drag(Point(650, 130), origin, floating = true))
        assertNull(gesture.drag(Point(700, 220), origin, floating = true))
    }

    @Test
    fun dragAfterAnAbortedGestureWorksAgain() {
        val gesture = gesture()
        gesture.drag(Point(505, 25), origin, floating = false)
        gesture.release()
        gesture.press()
        assertNull(gesture.drag(Point(440, 30), origin, floating = true))
        assertEquals(Point(110, 200), gesture.drag(Point(450, 30), origin, floating = true))
    }

    @Test
    fun dragWithoutPressIsIgnored() {
        val gesture = WindowDragGesture()
        assertNull(gesture.drag(Point(900, 400), origin, floating = true))
        assertNull(gesture.drag(Point(920, 410), origin, floating = true))
    }

    @Test
    fun releasedGestureStopsFollowingThePointer() {
        val gesture = gesture()
        gesture.drag(Point(530, 20), origin, floating = true)
        gesture.release()
        assertNull(gesture.drag(Point(560, 20), origin, floating = true))
    }
}
