package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import app.skerry.shared.terminal.castFileName
import app.skerry.shared.terminal.recordingStamp
import app.skerry.ui.design.D
import app.skerry.ui.design.IconBtn
import app.skerry.ui.session.Session
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.vault.exportTextFile
import kotlinx.coroutines.launch

/**
 * Session record toggle: starts an asciinema recording of the live terminal, and on the second
 * click stops it and offers a Save-As for the `.cast` file. Lit red while recording.
 *
 * Nothing is written until the user picks a file: a recording holds whatever the server printed, so
 * it must not land on disk as a side effect of clicking a toolbar button.
 */
@Composable
fun RecordSessionButton(session: Session?, onDone: (RecordingOutcome) -> Unit) {
    val terminal = (session?.controller?.uiState as? ConnectionUiState.Connected)?.terminal
    val scope = rememberCoroutineScope()
    val recording = terminal?.recording == true
    IconBtn(
        name = if (recording) "stop_circle" else "radio_button_checked",
        tint = if (recording) D.sunset else D.dim,
        onClick = onClick@{
            val live = terminal ?: return@onClick
            if (!live.recording) {
                live.startRecording(session.displayTitle.ifBlank { session.subtitle })
                return@onClick
            }
            val truncated = live.recordingTruncated
            val cast = live.stopRecording()
            if (cast == null || live.recordingWasEmpty(cast)) {
                onDone(RecordingOutcome.Empty)
                return@onClick
            }
            scope.launch {
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
        },
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
