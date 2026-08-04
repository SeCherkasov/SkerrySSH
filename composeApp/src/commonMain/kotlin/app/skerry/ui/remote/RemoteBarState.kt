package app.skerry.ui.remote

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Visibility of the floating action bar over a remote desktop: it slides up out of the way on its
 * own, and comes back when the pointer reaches the top edge of the picture.
 *
 * Kept out of the composable so the rules can be tested without a composition. The timer itself
 * belongs to the screen ([app.skerry.ui.vnc.VncView]); this only says whether one should be armed.
 */
@Stable
class RemoteBarState {

    /** Whether the bar is on screen. It starts up, so the session explains its own controls. */
    var visible: Boolean by mutableStateOf(true)
        private set

    /** Pinned: the bar stays until the user hides it themselves. */
    var pinned: Boolean by mutableStateOf(false)
        private set

    /**
     * Bumped by every reveal. Part of the auto-hide effect's key: re-setting an already-true
     * [visible] is not a state change, so without this a reveal would leave the running timer to
     * expire on its old schedule and the bar would vanish a moment after being summoned.
     */
    var revealCount: Int by mutableStateOf(0)
        private set

    // The pointer is over the bar, or one of its menus is open — see [setHeld].
    private var heldOpen by mutableStateOf(false)

    /** Whether an auto-hide timer should be running right now. */
    val autoHides: Boolean get() = visible && !pinned && !heldOpen

    /** Show the bar and restart the timer. */
    fun reveal() {
        visible = true
        revealCount++
    }

    /** Hide it now. Works on a pinned bar too: the pin governs the timer, not this button. */
    fun hide() {
        visible = false
        // A pointer cannot be over a bar that is off screen, and a menu cannot outlive its anchor.
        // Without this a bar hidden while held would come back with its timer disarmed and stay up
        // for the rest of the session.
        heldOpen = false
    }

    fun togglePin() {
        pinned = !pinned
        if (pinned) reveal()
    }

    /**
     * Hold the bar open while the pointer is on it or a menu it opened is up: sliding away from
     * under the user's hand would take the menu with it.
     */
    fun setHeld(value: Boolean) {
        heldOpen = value
    }

    /**
     * The pointer moved over the picture at [y] pixels from its top. Within [edge] of the top it
     * summons the bar — the only way back once it has slid away.
     *
     * Only while the bar is away: this runs on every mouse move over the framebuffer, and a reveal
     * of an already-visible bar would cancel and relaunch the auto-hide coroutine dozens of times a
     * second for nothing. A pointer resting on the visible bar holds it open through [setHeld].
     */
    fun onPointerY(y: Float, edge: Float) {
        if (y <= edge && !visible) reveal()
    }
}
