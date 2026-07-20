package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Right-aligned sample placement of [Sparkline] — the part of its drawing that can be wrong. */
class SparklineTest {

    @Test
    fun pins_the_newest_sample_to_the_right_edge() {
        assertEquals(100f, sparklineX(index = 4, count = 5, capacity = 60, width = 100f))
        assertEquals(100f, sparklineX(index = 59, count = 60, capacity = 60, width = 100f))
    }

    @Test
    fun a_full_window_starts_at_the_left_edge() {
        assertEquals(0f, sparklineX(index = 0, count = 60, capacity = 60, width = 100f))
    }

    @Test
    fun a_partial_window_grows_in_from_the_left_instead_of_stretching() {
        // 3 of 60 samples occupy 2 slots at the right, not the whole width.
        val step = 100f / 59
        assertEquals(100f - 2 * step, sparklineX(index = 0, count = 3, capacity = 60, width = 100f), 0.001f)
        assertTrue(sparklineX(index = 0, count = 3, capacity = 60, width = 100f) > 90f)
    }

    @Test
    fun samples_are_evenly_spaced() {
        val a = sparklineX(index = 1, count = 10, capacity = 20, width = 190f)
        val b = sparklineX(index = 2, count = 10, capacity = 20, width = 190f)
        assertEquals(10f, b - a, 0.001f)
    }

    @Test
    fun a_degenerate_capacity_does_not_divide_by_zero() {
        val x = sparklineX(index = 0, count = 2, capacity = 1, width = 100f)
        assertTrue(x.isFinite(), "x must stay finite, was $x")
    }
}
