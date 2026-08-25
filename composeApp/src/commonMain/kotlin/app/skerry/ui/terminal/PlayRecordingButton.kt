package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.skerry.ui.design.IconBtn
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_play
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Toolbar button that opens a `.cast` file and hands the result to [onOpened] (which shows the
 * player or the "not a recording" notice).
 *
 * While the picker and the parse are in flight the button is inert: a second file dialog on top of
 * the first is the kind of thing that hangs a desktop app.
 */
@Composable
fun PlayRecordingButton(requests: SharedFlow<Unit>? = null, onOpened: (CastOpenResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    val open = {
        if (!opening) {
            opening = true
            scope.launch {
                try {
                    onOpened(openCastFile())
                } finally {
                    opening = false
                }
            }
        }
    }
    val currentOpen by rememberUpdatedState(open)
    // Hotkey channel (⌘P / Ctrl+Shift+P) — same action as the click.
    LaunchedEffect(requests) { requests?.collect { currentOpen() } }
    // Disabled rather than merely tinted while a picker is up: the same rule the rest of the
    // toolbar follows, and it draws the faint tint by itself. The guard inside [open] stays — the
    // hotkey reaches this button whether or not it is clickable.
    IconBtn(
        "play_circle",
        onClick = { open() },
        tooltip = stringResource(Res.string.shell_tip_play),
        enabled = !opening,
    )
}
