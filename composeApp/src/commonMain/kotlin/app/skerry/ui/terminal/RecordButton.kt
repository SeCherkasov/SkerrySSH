package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import app.skerry.shared.terminal.castFileName
import app.skerry.shared.terminal.recordingStamp
import app.skerry.ui.design.D
import app.skerry.ui.design.IconBtn
import app.skerry.ui.session.Session
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.vault.exportTextFile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Session record toggle: starts an asciinema recording of the live terminal, and on the second
 * click stops it and offers a Save-As for the `.cast` file. Lit red while recording.
 *
 * Nothing is written until the user picks a file: a recording holds whatever the server printed, so
 * it must not land on disk as a side effect of clicking a toolbar button.
 *
 * [requests] is the hotkey channel (⌘R / Ctrl+Shift+R): the shell can't toggle recording itself,
 * because start/stop lives on the terminal state this button holds.
 */
@Composable
fun RecordSessionButton(
    session: Session?,
    requests: SharedFlow<Unit>? = null,
    onDone: (RecordingOutcome) -> Unit,
) {
    val terminal = (session?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    val scope = rememberCoroutineScope()
    val recording = terminal?.recording == true
    // Start/stop go through the terminal's command loop (it owns the recorder), so the whole toggle
    // runs in a coroutine rather than inline in the click.
    val toggle = {
            scope.launch {
                val live = terminal ?: return@launch
                if (!live.recording) {
                    live.startRecording(session.displayTitle.ifBlank { session.subtitle })
                    return@launch
                }
                val truncated = live.recordingTruncated
                val cast = live.stopRecording()
                if (cast == null || live.recordingWasEmpty(cast)) {
                    onDone(RecordingOutcome.Empty)
                    return@launch
                }
                val name = castFileName(session.displayTitle.ifBlank { session.subtitle }, recordingStamp())
                val saved = exportTextFile(name, cast)
                onDone(
                    when {
                        !saved -> RecordingOutcome.Cancelled
                        truncated -> RecordingOutcome.SavedTruncated
                        else -> RecordingOutcome.Saved
                    },
                )
            }
            Unit
        }
    // The hotkey does exactly what a click does. rememberUpdatedState so a collector started for an
    // earlier session doesn't keep toggling a terminal that is no longer on screen.
    val currentToggle by rememberUpdatedState(toggle)
    LaunchedEffect(requests) { requests?.collect { currentToggle() } }
    IconBtn(
        name = if (recording) "stop_circle" else "radio_button_checked",
        tint = if (recording) D.sunset else D.dim,
        onClick = toggle,
    )
}

/** Outcome of stopping a recording, so the caller can show the right notice. */
enum class RecordingOutcome {
    Saved,
    SavedTruncated,
    Empty,
    Cancelled;

    /** A cancelled Save-As is the user's own choice — nothing to tell them about it. */
    val worthReporting: Boolean get() = this != Cancelled
}

/** A cast with only a header line means the session printed nothing while recording. */
private fun TerminalScreenState.recordingWasEmpty(cast: String): Boolean = !cast.contains('\n')
