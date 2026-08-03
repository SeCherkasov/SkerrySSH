package app.skerry.ui.desktop

import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class NativeMoveWatchTest {

    private val settle = 60.milliseconds
    private val origin = Point(100, 200)
    private val time = TestTimeSource()

    private fun watch() = NativeMoveWatch(settle, time)

    @Test
    fun watchThatWasNeverStartedNeverGivesUp() {
        val watch = watch()
        time += settle * 10
        assertFalse(watch.givenUp(origin))
    }

    @Test
    fun windowStillAtItsOriginWithinTheSettleWindowKeepsWaiting() {
        val watch = watch()
        watch.started(origin)
        time += settle - 1.milliseconds
        assertFalse(watch.givenUp(origin))
    }

    /** The WM ignored the request: nothing moved, the settle window elapsed — the drag falls back. */
    @Test
    fun windowThatNeverMovedGivesUpOnceTheSettleWindowElapsed() {
        val watch = watch()
        watch.started(origin)
        time += settle
        assertTrue(watch.givenUp(origin))
    }

    /** The WM took the window: it moved, so the in-app drag must never claim the gesture. */
    @Test
    fun windowMovedByTheWindowManagerNeverGivesUp() {
        val watch = watch()
        watch.started(origin)
        time += 10.milliseconds
        assertFalse(watch.givenUp(Point(140, 260)))
        assertTrue(watch.wmTookOver(origin))
        // Long past the settle window, and even back at the original spot: the WM owns this gesture.
        time += settle * 10
        assertFalse(watch.givenUp(origin))
    }

    /**
     * A single slow first frame must not cost the smooth native drag for the whole session, but a WM
     * that keeps ignoring the request should stop being asked — otherwise every drag pays the settle
     * delay, which is what makes dragging unusable.
     */
    @Test
    fun theNativePathIsAbandonedOnlyAfterTheWindowManagerIgnoredTwoDragsInARow() {
        val watch = watch()
        watch.started(origin)
        time += settle
        assertTrue(watch.givenUp(origin))
        assertTrue(watch.worthTrying)

        watch.started(origin)
        time += settle
        assertTrue(watch.givenUp(origin))
        assertFalse(watch.worthTrying)
    }

    @Test
    fun aWindowManagerThatAnsweredResetsTheDoubt() {
        val watch = watch()
        watch.started(origin)
        time += settle
        assertTrue(watch.givenUp(origin))

        // The next drag is honoured: the window moves, so the WM is back in good standing...
        watch.started(origin)
        time += 5.milliseconds
        assertTrue(watch.wmTookOver(Point(300, 400)))

        // ...and a later refusal starts counting from scratch instead of abandoning the native path.
        watch.started(origin)
        time += settle
        assertTrue(watch.givenUp(origin))
        assertTrue(watch.worthTrying)
    }
}
