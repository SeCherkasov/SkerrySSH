package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.team.RecordingMode
import app.skerry.ui.design.StatusAnnouncer
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.lib_team_rec_active
import app.skerry.ui.generated.resources.lib_team_rec_failed
import app.skerry.ui.generated.resources.lib_team_rec_upload_pending
import app.skerry.ui.generated.resources.lib_team_rec_upload_failed
import app.skerry.ui.design.IconBtn
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_record
import app.skerry.ui.generated.resources.shell_tip_record_stop
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.session.Session
import app.skerry.ui.app.LocalTeams
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.theme.Skerry

/** Local export or explicit optional team capture, using the same flow as mobile. */
@Composable
fun RecordSessionButton(
    session: Session?,
    request: ToolbarRequest? = null,
    onSaved: (String, Long) -> Unit = { _, _ -> },
    onDone: (RecordingOutcome) -> Unit,
) {
    val terminal = when (val state = session?.controller?.uiState) {
        is ConnectionUiState.Connected -> state.terminal
        is ConnectionUiState.Disconnected -> state.terminal
        else -> null
    }
    val teams = LocalTeams.current
    val uploadPending = teams?.recordings?.recordingUploadsPending?.collectAsState()?.value == true
    val uploadFailed = teams?.recordings?.recordingUploadError?.collectAsState()?.value == true
    val uploadLabel = if (uploadFailed) stringResource(Res.string.lib_team_rec_upload_failed)
        else if (uploadPending) stringResource(Res.string.lib_team_rec_upload_pending) else ""
    val required = terminal?.teamRecordingMode == RecordingMode.REQUIRED
    val recording = terminal?.recording == true
    val recordingLabel = when {
        terminal?.teamRecordingFailed == true -> stringResource(Res.string.lib_team_rec_failed)
        recording -> stringResource(Res.string.lib_team_rec_active)
        else -> ""
    }
    StatusAnnouncer(recordingLabel)
    StatusAnnouncer(uploadLabel)
    val toggle = rememberSessionRecordingAction(session, terminal, teams, onSaved, onDone)
    OnToolbarRequest(request) { toggle() }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconBtn(
            name = if (recording && !required) "stop_circle" else "radio_button_checked",
            tint = if (recording) Skerry.colors.sunset else Skerry.colors.dim,
            onClick = toggle,
            enabled = !required && toolbarActionEnabled(ToolbarAction.Record, session),
            tooltip = if (required) recordingLabel else stringResource(
                if (recording) Res.string.shell_tip_record_stop else Res.string.shell_tip_record),
        )
        if (recordingLabel.isNotEmpty()) Txt(recordingLabel, color = Skerry.colors.sunset, size = 11.sp)
        if (uploadLabel.isNotEmpty()) Txt(uploadLabel, color = Skerry.colors.amber, size = 11.sp)
    }
}

/** Outcome of stopping a recording, so the caller can show the right notice. */
enum class RecordingOutcome {
    Saved,
    SavedTruncated,
    Empty,
    Failed,
    Cancelled;

    val worthReporting: Boolean get() = this != Cancelled
}
