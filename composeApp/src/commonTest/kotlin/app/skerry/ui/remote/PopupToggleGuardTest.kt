package app.skerry.ui.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class PopupToggleGuardTest {

    @Test
    fun the_click_that_dismissed_the_popup_does_not_reopen_it() {
        val time = TestTimeSource()
        val guard = PopupToggleGuard(time)

        // What one press on an open menu's button really is: the popup takes the click as an outside
        // click and dismisses, then the same click lands on the button.
        guard.onDismissed()
        time += 5.milliseconds

        assertFalse(guard.opensOnClick())
    }

    @Test
    fun a_later_click_opens_the_popup_again() {
        val time = TestTimeSource()
        val guard = PopupToggleGuard(time)

        guard.onDismissed()
        time += 500.milliseconds

        assertTrue(guard.opensOnClick())
    }

    @Test
    fun a_click_with_no_dismissal_behind_it_always_opens() {
        val guard = PopupToggleGuard(TestTimeSource())

        assertTrue(guard.opensOnClick())
    }

    @Test
    fun the_guard_only_swallows_one_click() {
        val time = TestTimeSource()
        val guard = PopupToggleGuard(time)

        guard.onDismissed()
        assertFalse(guard.opensOnClick())
        // The next press is the user asking for the menu again, however fast they were.
        assertTrue(guard.opensOnClick())
    }
}
