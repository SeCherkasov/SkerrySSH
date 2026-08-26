package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * An ask waiting for one of the toolbar buttons that own their own state — the snippet and runbook
 * palettes, the recorder, the share panel.
 *
 * Held as state rather than sent as a one-shot signal, for the same reason the assistant's focus
 * request is a flag ([app.skerry.ui.app.DesktopDesignState.assistantFocusPending]): the keyboard
 * chord and the overflow menu both fire while the button may not be composed at all — the terminal
 * toolbar is drawn by the terminal view, and the tab may be showing the file panel, the monitor, a
 * runbook run or a recording. An event emitted with no collector is gone, and the chord is spent
 * with nothing on screen to show for it.
 *
 * The button takes the ask the moment it composes and reads it ([OnToolbarRequest]), so a flag
 * cannot fire twice on a later recomposition either.
 */
@Stable
class ToolbarRequest {
    /** Whether an ask is waiting for its button. */
    var pending: Boolean by mutableStateOf(false)
        private set

    /** Ask. Asking twice before the button reads it is still one ask. */
    fun raise() { pending = true }

    /** Take the pending ask, if there is one, and clear it. */
    fun take(): Boolean {
        val was = pending
        pending = false
        return was
    }
}

/**
 * Runs [action] once per ask raised on [request], as soon as the button that reads it is composed.
 *
 * [action] runs whether or not the button can act on it — the ask is taken either way, so one the
 * button has nothing to do with cannot linger and surprise the user on a later tab switch.
 */
@Composable
fun OnToolbarRequest(request: ToolbarRequest?, action: () -> Unit) {
    val current by rememberUpdatedState(action)
    val pending = request?.pending == true
    LaunchedEffect(request, pending) {
        if (pending && request?.take() == true) current()
    }
}
