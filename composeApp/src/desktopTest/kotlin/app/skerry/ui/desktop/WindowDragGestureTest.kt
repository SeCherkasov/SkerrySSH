package app.skerry.ui.desktop

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowDragGestureTest {

    private val deadZone = 18
    private val origin = Point(100, 200)

    private fun gesture(pressAt: Point = Point(500, 20)) =
        WindowDragGesture(deadZone).apply { press(pressAt) }

    @Test
    fun pointerInsideDeadZoneDoesNotMoveTheWindow() {
        val gesture = gesture(Point(500, 20))
        assertNull(gesture.drag(Point(502, 21), origin, floating = true))
        assertNull(gesture.drag(Point(500, 20), origin, floating = true))
    }

    @Test
    fun dragPastDeadZoneFollowsThePointer() {
        val gesture = gesture(Point(500, 20))
        // The crossing event itself only arms the drag (the window stays where it is)...
        assertNull(gesture.drag(Point(530, 20), origin, floating = true))
        // ...and from there the origin follows the pointer delta measured from the crossing point.
        assertEquals(Point(140, 215), gesture.drag(Point(570, 35), origin, floating = true))
    }

    /**
     * A drag handed over after a restore (WindowFrame.followPointer) starts with no dead zone: it was
     * already crossed before the window was restored, so the very first event must arm the gesture.
     */
    @Test
    fun zeroDeadZoneArmsOnTheFirstEvent() {
        val gesture = WindowDragGesture(deadZone = 0).apply { press(Point(500, 20)) }
        // Not even a pixel of movement: the first event still only captures the grab...
        assertNull(gesture.drag(Point(500, 20), origin, floating = true))
        // ...and every event after it moves the window.
        assertEquals(Point(101, 200), gesture.drag(Point(501, 20), origin, floating = true))
    }

    /** Issue #76: the double-click maximizes the window while the button is still down. */
    @Test
    fun windowMaximizedMidGestureIsNeverMoved() {
        val gesture = gesture(Point(500, 20))
        // The maximized window sits at (0,0); moves keep arriving until the button goes up, and not
        // one of them may reposition it (the second one is what a leaked drag would answer).
        assertNull(gesture.drag(Point(560, 40), Point(0, 0), floating = false))
        assertNull(gesture.drag(Point(600, 80), Point(0, 0), floating = false))
        assertNull(gesture.drag(Point(900, 400), Point(0, 0), floating = false))
    }

    @Test
    fun maximizingMidGestureEndsTheGestureForGood() {
        val gesture = gesture(Point(500, 20))
        // A drag is already under way — past the dead zone, window following the pointer...
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
        val gesture = gesture(Point(500, 20))
        gesture.drag(Point(505, 25), origin, floating = false)
        gesture.release()
        gesture.press(Point(400, 30))
        assertNull(gesture.drag(Point(440, 30), origin, floating = true))
        assertEquals(Point(110, 200), gesture.drag(Point(450, 30), origin, floating = true))
    }

    @Test
    fun dragWithoutPressIsIgnored() {
        val gesture = WindowDragGesture(deadZone)
        assertNull(gesture.drag(Point(900, 400), origin, floating = true))
    }

    @Test
    fun releasedGestureStopsFollowingThePointer() {
        val gesture = gesture(Point(500, 20))
        gesture.drag(Point(530, 20), origin, floating = true)
        gesture.release()
        assertNull(gesture.drag(Point(560, 20), origin, floating = true))
    }
}
