package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalCastPicker
import app.skerry.ui.design.IconBtn
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_play
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

/**
 * Toolbar button that opens a `.cast` file. Unlike the palettes and the recorder beside it, it owns
 * nothing: the picker runs in [CastOpenDriver], which is composed by the window chrome and is
 * therefore reachable from anywhere. ⌘⇧P needs no session, so it has to work over a remote desktop
 * and over an already-open recording — neither of which draws this toolbar at all.
 *
 * While the picker and the parse are in flight the button is inert: a second file dialog on top of
 * the first is the kind of thing that hangs a desktop app. Disabled rather than merely tinted, the
 * same rule the rest of the toolbar follows.
 */
@Composable
fun PlayRecordingButton(state: DesktopDesignState) {
    IconBtn(
        "play_circle",
        onClick = { state.castOpen.raise() },
        tooltip = stringResource(Res.string.shell_tip_play),
        // Through the shared rule, not a second copy of it: the overflow menu row draws itself from
        // the same call, and a menu entry that fires a request the driver then drops is the dead
        // press the buttons themselves stopped making.
        enabled = toolbarActionEnabled(ToolbarAction.Play, active = null, playerBusy = state.castOpening),
    )
}

/**
 * Runs the file picker behind [DesktopDesignState.castOpen] and hands the result to [onOpened]
 * (which shows the player or the "not a recording" notice).
 *
 * Composed once, by the window chrome: the button that raises the request may be parked out of a
 * narrow toolbar, off-screen behind another view, or absent entirely, and the ask must still be
 * answered on the frame it arrives.
 */
@Composable
fun CastOpenDriver(state: DesktopDesignState, onOpened: (CastOpenResult) -> Unit) {
    val scope = rememberCoroutineScope()
    // The picker comes from the composition rather than being called directly, so a test can answer
    // it: a native file dialog cannot be driven from one, and without the seam this whole path —
    // the chord, the button and the overflow row alike — is reachable only by hand.
    val pick = LocalCastPicker.current
    // The flag is set during composition and cleared by the coroutine's `finally`, so a disposal
    // between the two — the vault locking swaps this whole tree out — cancels the job before its body
    // runs and the `finally` never happens. The state outlives the lock, so Play would stay disabled
    // for the rest of the process. This gives the flag and its clear one lifetime.
    DisposableEffect(Unit) { onDispose { state.castOpening = false } }
    OnToolbarRequest(state.castOpen) {
        if (!state.castOpening) {
            state.castOpening = true
            scope.launch {
                // Nothing here throws today (both the read and the parse answer with a result rather
                // than an exception), and the `finally` is what keeps that from becoming a button
                // stuck inert if one day it does.
                try {
                    onOpened(pick())
                } finally {
                    state.castOpening = false
                }
            }
        }
    }
}
