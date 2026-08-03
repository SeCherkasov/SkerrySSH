package app.skerry.ui.desktop

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a window restored from maximized mid-drag lands: the pointer keeps its relative place along
 * the titlebar, so the shrinking window stays under the cursor instead of jumping to its old origin.
 */
class WindowRestoreTest {

    private val maximized = Rectangle(0, 0, 1920, 1080)
    private val restored = Dimension(800, 600)

    private fun origin(x: Int, y: Int) = restoredWindowOrigin(maximized, restored, Point(x, y))

    @Test
    fun pointer_in_the_middle_centers_the_window_under_it() {
        // Half the maximized width -> half the restored width to the left of the pointer. The same
        // share applies vertically, so a press 10px down a 1080px window lands 6px down a 600px one.
        assertEquals(Point(560, 4), origin(960, 10))
    }

    @Test
    fun pointer_keeps_its_share_of_the_titlebar() {
        // A quarter along the maximized titlebar stays a quarter along the restored one.
        assertEquals(Point(280, 4), origin(480, 10))
    }

    @Test
    fun window_never_starts_left_of_the_area() {
        assertEquals(0, origin(0, 5).x)
    }

    @Test
    fun window_never_starts_above_the_area() {
        val area = Rectangle(0, 27, 1920, 1053)
        assertEquals(27, restoredWindowOrigin(area, restored, Point(960, 27)).y)
    }

    @Test
    fun window_never_hangs_off_the_bottom_edge() {
        assertEquals(1080 - 600, origin(960, 1080).y)
    }

    @Test
    fun window_never_hangs_off_the_right_edge() {
        val point = origin(1920, 10)
        assertEquals(1920 - 800, point.x)
    }

    @Test
    fun a_degenerate_area_does_not_divide_by_zero() {
        val point = restoredWindowOrigin(Rectangle(0, 0, 0, 0), restored, Point(40, 12))
        assertTrue(point.x <= 40 && point.y <= 12)
    }

    @Test
    fun a_restored_window_wider_than_the_area_starts_at_its_left_edge() {
        val point = restoredWindowOrigin(maximized, Dimension(2400, 600), Point(960, 10))
        assertEquals(0, point.x)
    }
}
