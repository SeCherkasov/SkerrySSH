package app.skerry.ui.remote

import androidx.compose.runtime.Stable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps a popup's own button from reopening what the same press just closed.
 *
 * A press on the button of an open menu is two events: the popup sees a click outside itself and
 * dismisses, and then the click reaches the button, which toggles the menu back open. The user sees
 * a menu that will not close. The dismissal is what marks the guard, and the click that follows it
 * within [WINDOW] is swallowed — only that one, so a user pressing the button again straight away
 * still gets the menu.
 */
@Stable
class PopupToggleGuard(private val source: TimeSource = TimeSource.Monotonic) {
    private var dismissedAt: TimeMark? = null

    /** Called from the popup's own dismiss handler. */
    fun onDismissed() {
        dismissedAt = source.markNow()
    }

    /** Whether a click on the trigger should act, or belongs to the dismissal that just happened. */
    fun opensOnClick(): Boolean {
        val justDismissed = dismissedAt?.let { it.elapsedNow() < WINDOW } == true
        dismissedAt = null
        return !justDismissed
    }

    private companion object {
        // Long enough for the dismissal and the click to be the same press, short enough that a
        // deliberate second press is never mistaken for one.
        val WINDOW = 200.milliseconds
    }
}
