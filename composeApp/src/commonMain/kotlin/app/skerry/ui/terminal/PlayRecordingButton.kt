package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.skerry.ui.design.D
import app.skerry.ui.design.IconBtn
import kotlinx.coroutines.launch

/**
 * Toolbar button that opens a `.cast` file and hands the result to [onOpened] (which shows the
 * player or the "not a recording" notice).
 *
 * While the picker and the parse are in flight the button is inert: a second file dialog on top of
 * the first is the kind of thing that hangs a desktop app.
 */
@Composable
fun PlayRecordingButton(onOpened: (CastOpenResult) -> Unit) {
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    IconBtn(
        "play_circle",
        tint = if (opening) D.faint else D.dim,
        onClick = {
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
        },
    )
}
