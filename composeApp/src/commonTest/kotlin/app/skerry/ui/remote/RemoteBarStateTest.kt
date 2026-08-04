package app.skerry.ui.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteBarStateTest {

    @Test
    fun starts_visible_and_arms_auto_hide() {
        val bar = RemoteBarState()

        assertTrue(bar.visible)
        assertFalse(bar.pinned)
        assertTrue(bar.autoHides)
    }

    @Test
    fun hiding_stops_the_auto_hide_timer_from_re_arming() {
        val bar = RemoteBarState()

        bar.hide()

        assertFalse(bar.visible)
        assertFalse(bar.autoHides)
    }

    @Test
    fun a_reveal_restarts_the_timer_even_when_the_bar_is_already_up() {
        val bar = RemoteBarState()
        val before = bar.revealCount

        bar.reveal()

        assertTrue(bar.visible)
        assertEquals(before + 1, bar.revealCount)
    }

    @Test
    fun the_pointer_only_reveals_the_bar_at_the_top_edge() {
        val bar = RemoteBarState()
        bar.hide()

        bar.onPointerY(y = 40f, edge = 12f)
        assertFalse(bar.visible)

        bar.onPointerY(y = 4f, edge = 12f)
        assertTrue(bar.visible)
    }

    @Test
    fun the_pointer_at_the_edge_does_nothing_while_the_bar_is_already_up() {
        val bar = RemoteBarState()
        val before = bar.revealCount

        bar.onPointerY(y = 2f, edge = 12f)

        // This runs for every mouse move over the framebuffer: a reveal here would cancel and
        // relaunch the auto-hide coroutine dozens of times a second for a bar already on screen.
        assertEquals(before, bar.revealCount)
    }

    @Test
    fun hiding_a_held_bar_lets_the_next_reveal_arm_the_timer_again() {
        val bar = RemoteBarState()
        bar.setHeld(true)

        bar.hide()
        bar.reveal()

        // The pointer cannot be over a bar that left the screen, and a menu cannot outlive its
        // anchor — a hold carried across the hide would leave the bar up for good.
        assertTrue(bar.autoHides)
    }

    @Test
    fun a_held_bar_never_slides_away_under_the_pointer() {
        val bar = RemoteBarState()

        bar.setHeld(true)
        assertFalse(bar.autoHides)

        bar.setHeld(false)
        assertTrue(bar.autoHides)
    }

    @Test
    fun pinning_shows_the_bar_and_disarms_auto_hide() {
        val bar = RemoteBarState()
        bar.hide()

        bar.togglePin()

        assertTrue(bar.pinned)
        assertTrue(bar.visible)
        assertFalse(bar.autoHides)

        bar.togglePin()

        assertFalse(bar.pinned)
        assertTrue(bar.autoHides)
    }

    @Test
    fun the_hide_button_works_on_a_pinned_bar_too() {
        val bar = RemoteBarState()
        bar.togglePin()

        bar.hide()

        assertFalse(bar.visible)
        // Pin survives the manual hide: it is a preference about the timer, not about this moment.
        assertTrue(bar.pinned)
    }
}
