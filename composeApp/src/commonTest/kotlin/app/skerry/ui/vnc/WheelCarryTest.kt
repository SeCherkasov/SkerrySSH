package app.skerry.ui.vnc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wheel handling for the remote desktop (F-14): magnitude counts, fractions accumulate, and a
 * scroll never releases the buttons a drag is holding (F-38).
 */
class WheelCarryTest {

    @Test
    fun fractional_deltas_accumulate_into_whole_notches() {
        val carry = WheelCarry()
        assertEquals(0, carry.add(0.4f))
        assertEquals(0, carry.add(0.4f))
        assertEquals(1, carry.add(0.4f), "0.4+0.4+0.4 crosses one notch")
        assertEquals(0, carry.add(0.1f), "the remainder is kept, not dropped")
    }

    @Test
    fun a_three_line_step_scrolls_three_notches_in_one_event() {
        assertEquals(-3, WheelCarry().add(-3f))
    }

    @Test
    fun direction_changes_reset_nothing_they_should_not() {
        val carry = WheelCarry()
        carry.add(0.6f)
        assertEquals(0, carry.add(-0.4f), "0.6 - 0.4 = 0.2, still under a notch")
        assertEquals(-1, carry.add(-1.2f))
    }

    @Test
    fun wheel_masks_preserve_the_buttons_a_drag_is_holding() {
        // RFB's mask is absolute: sending 0 after the wheel bit released a held drag (F-38).
        assertEquals(
            listOf(
                VncButton.LEFT or VncButton.WHEEL_UP, VncButton.LEFT,
                VncButton.LEFT or VncButton.WHEEL_UP, VncButton.LEFT,
            ),
            wheelMasks(buttons = VncButton.LEFT, steps = -2, negative = VncButton.WHEEL_UP, positive = VncButton.WHEEL_DOWN),
        )
        assertEquals(
            listOf(VncButton.WHEEL_DOWN, 0),
            wheelMasks(buttons = 0, steps = 1, negative = VncButton.WHEEL_UP, positive = VncButton.WHEEL_DOWN),
        )
    }

    /**
     * A fling on a high-resolution trackpad can accumulate a large delta in one event, and every
     * notch is two writes the actor sends unpaced — the burst would sit in front of the click that
     * follows it. Bounded per event, with the rest carried into the next sample.
     */
    @Test
    fun one_event_emits_at_most_a_bounded_burst() {
        val carry = WheelCarry()
        assertEquals(8, carry.add(100f), "one event emitted an unbounded burst")
        assertEquals(8, carry.add(0f), "what was over the bound was dropped instead of carried")
    }

    @Test
    fun the_bound_holds_in_both_directions() {
        val carry = WheelCarry()
        assertEquals(-8, carry.add(-100f))
    }
}
