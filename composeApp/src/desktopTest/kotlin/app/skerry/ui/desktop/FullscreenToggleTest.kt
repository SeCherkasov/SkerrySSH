package app.skerry.ui.desktop

import androidx.compose.ui.window.WindowPlacement
import kotlin.test.Test
import kotlin.test.assertEquals

class FullscreenToggleTest {

    private class Window(var placement: WindowPlacement = WindowPlacement.Floating) {
        val toggle = FullscreenToggle({ placement }, { placement = it })
    }

    @Test
    fun a_floating_window_goes_fullscreen_and_comes_back_floating() {
        val w = Window(WindowPlacement.Floating)

        w.toggle.apply(true)
        assertEquals(WindowPlacement.Fullscreen, w.placement)

        w.toggle.apply(false)
        assertEquals(WindowPlacement.Floating, w.placement)
    }

    @Test
    fun a_maximized_window_comes_back_maximized() {
        val w = Window(WindowPlacement.Maximized)

        w.toggle.apply(true)
        w.toggle.apply(false)

        assertEquals(WindowPlacement.Maximized, w.placement)
    }

    @Test
    fun asking_for_fullscreen_twice_does_not_forget_where_to_return() {
        val w = Window(WindowPlacement.Maximized)

        w.toggle.apply(true)
        // A recomposition re-running the effect must not record Fullscreen as the placement to
        // restore — the window would then have no way back to its own size.
        w.toggle.apply(true)
        w.toggle.apply(false)

        assertEquals(WindowPlacement.Maximized, w.placement)
    }

    @Test
    fun leaving_a_mode_that_was_never_entered_leaves_the_window_alone() {
        val w = Window(WindowPlacement.Maximized)

        w.toggle.apply(false)

        assertEquals(WindowPlacement.Maximized, w.placement)
    }

    @Test
    fun a_window_the_user_put_fullscreen_themselves_is_restored_to_floating() {
        // Entered from outside (the WM's own fullscreen key), so nothing was recorded: the window
        // still has to end up somewhere it can be moved and closed.
        val w = Window(WindowPlacement.Fullscreen)

        w.toggle.apply(false)

        assertEquals(WindowPlacement.Floating, w.placement)
    }
}
