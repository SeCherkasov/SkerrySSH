package app.skerry.ui.teams

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.skerry.shared.team.TeamScopeRef
import app.skerry.shared.terminal.Asciicast
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.NoticeDialog
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_team_rec_error
import app.skerry.ui.generated.resources.lib_team_rec_title
import app.skerry.ui.generated.resources.shell_cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Mobile supplies its existing player overlay; desktop keeps its player tab. */
internal val LocalTeamRecordingPlayback = staticCompositionLocalOf<((Asciicast) -> Unit)?> { null }

internal class TeamRecordingPlayback(val open: (scopeId: String, recordingId: String) -> Unit)

@Composable
internal fun rememberTeamRecordingPlayback(tc: TeamsCoordinator, teamId: String): TeamRecordingPlayback {
    val scope = rememberCoroutineScope()
    val mobilePlayer = LocalTeamRecordingPlayback.current
    val sessions = LocalSessions.current
    var failed by remember(teamId) { mutableStateOf(false) }
    var busy by remember(teamId) { mutableStateOf(false) }
    if (failed) Popup(alignment = Alignment.Center, properties = PopupProperties(focusable = true),
        onDismissRequest = { failed = false }) { NoticeDialog(
        title = stringResource(Res.string.lib_team_rec_title),
        message = stringResource(Res.string.lib_team_rec_error),
        buttonLabel = stringResource(Res.string.shell_cancel),
        onDismiss = { failed = false },
    ) }
    return remember(tc, teamId, scope, mobilePlayer, sessions) {
        TeamRecordingPlayback { scopeId, recordingId ->
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val cast = tc.recordings.openRecording(TeamScopeRef(teamId, scopeId), recordingId)
                        var handedOff = false
                        try {
                            if (mobilePlayer != null) mobilePlayer(cast)
                            else checkNotNull(sessions).openPlayer(untrustedLabel(cast.title ?: recordingId), cast)
                            handedOff = true
                        } finally { if (!handedOff) cast.source?.close() }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }
        }
    }
}
