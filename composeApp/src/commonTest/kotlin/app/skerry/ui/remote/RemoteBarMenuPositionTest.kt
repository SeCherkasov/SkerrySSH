package app.skerry.ui.remote

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteBarMenuPositionTest {

    private fun place(anchor: IntRect, window: IntSize, menu: IntSize): IntOffset =
        belowAnchor(gap = 6).calculatePosition(anchor, window, LayoutDirection.Ltr, menu)

    @Test
    fun a_menu_hangs_below_its_button_centered_on_it() {
        val at = place(
            anchor = IntRect(left = 600, top = 20, right = 634, bottom = 54),
            window = IntSize(1280, 800),
            menu = IntSize(220, 300),
        )

        assertEquals(IntOffset(x = 617 - 110, y = 60), at)
    }

    @Test
    fun a_menu_near_the_left_edge_stays_inside_the_window() {
        val at = place(
            anchor = IntRect(left = 4, top = 20, right = 38, bottom = 54),
            window = IntSize(1280, 800),
            menu = IntSize(220, 300),
        )

        assertEquals(0, at.x)
    }

    @Test
    fun a_menu_near_the_right_edge_stays_inside_the_window() {
        val at = place(
            anchor = IntRect(left = 1250, top = 20, right = 1284, bottom = 54),
            window = IntSize(1280, 800),
            menu = IntSize(220, 300),
        )

        assertEquals(1280 - 220, at.x)
    }

    @Test
    fun a_menu_taller_than_the_space_below_is_lifted_rather_than_cut_off() {
        val at = place(
            anchor = IntRect(left = 600, top = 20, right = 634, bottom = 54),
            window = IntSize(1280, 300),
            menu = IntSize(220, 280),
        )

        assertEquals(20, at.y)
    }

    // The phone strip sits against the right screen edge, so its menus open to the LEFT of the
    // button — the harder branch, with a fallback for a window too narrow for either side.
    private fun placeLeft(anchor: IntRect, window: IntSize, menu: IntSize): IntOffset =
        leftOfAnchor(gap = 6).calculatePosition(anchor, window, LayoutDirection.Ltr, menu)

    @Test
    fun the_phone_strips_menu_opens_to_the_left_of_its_button() {
        val at = placeLeft(
            anchor = IntRect(left = 380, top = 100, right = 424, bottom = 134),
            window = IntSize(420, 900),
            menu = IntSize(220, 300),
        )

        assertEquals(380 - 220 - 6, at.x)
        assertEquals(100, at.y)
    }

    @Test
    fun a_window_too_narrow_for_the_left_side_falls_back_to_the_right_of_the_button() {
        val at = placeLeft(
            anchor = IntRect(left = 100, top = 100, right = 144, bottom = 134),
            window = IntSize(260, 900),
            menu = IntSize(220, 300),
        )

        // Not a negative x, which would clip the menu against the window edge.
        assertEquals(260 - 220, at.x)
    }

    @Test
    fun the_phone_strips_menu_is_lifted_when_it_would_run_past_the_bottom() {
        val at = placeLeft(
            anchor = IntRect(left = 380, top = 700, right = 424, bottom = 734),
            window = IntSize(420, 900),
            menu = IntSize(220, 300),
        )

        assertEquals(900 - 300, at.y)
    }
}
