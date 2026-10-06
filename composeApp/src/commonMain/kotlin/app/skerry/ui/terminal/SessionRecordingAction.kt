package app.skerry.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import app.skerry.ui.design.CancelButton
import app.skerry.ui.design.GhostButton
import app.skerry.ui.design.ModalScrim
import app.skerry.ui.design.PrimaryButton
import app.skerry.ui.design.Txt
import app.skerry.ui.design.consumeClicks
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_cancel
import app.skerry.ui.generated.resources.term_record_destination
import app.skerry.ui.generated.resources.term_record_local
import app.skerry.ui.generated.resources.term_record_team_and_local
import app.skerry.ui.session.Session
import app.skerry.ui.teams.TeamsCoordinator
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vault.exportFileGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The same controller and destination dialog on desktop and mobile. */
@Composable
fun rememberSessionRecordingAction(
    session: Session?,
    terminal: TerminalScreenState?,
    teams: TeamsCoordinator?,
    onSaved: (String, Long) -> Unit = { _, _ -> },
    onDone: (RecordingOutcome) -> Unit,
): () -> Unit {
    if (session == null || terminal == null) return {}
    val title = session.displayTitle.ifBlank { session.subtitle }
    val saved by rememberUpdatedState(onSaved)
    val done by rememberUpdatedState(onDone)
    val baseScope = rememberCoroutineScope()
    val actionScope = remember(terminal) {
        CoroutineScope(baseScope.coroutineContext + SupervisorJob(baseScope.coroutineContext[Job]))
    }
    DisposableEffect(actionScope) { onDispose { actionScope.cancel() } }
    val controller = remember(terminal, session.hostId, title, teams) {
        val port = object : SessionRecordingPort {
            override val mode get() = terminal.teamRecordingMode
            override val recording get() = terminal.recording
            override fun startLocal() { terminal.startRecording(title) }
            override suspend fun armTeam(): RecordingStart {
                val hostId = session.hostId ?: error("team recording host missing")
                val plan = teams?.recordings?.armOptionalRecording(hostId, title, terminal.cols, terminal.rows)
                    ?: error("optional team recording unavailable")
                val capture = checkNotNull(plan.capture)
                return RecordingStart(
                    commit = { terminal.startRecording(title, capture, plan.onFinished) },
                    cancel = { capture.abort() },
                )
            }
            override suspend fun stop(): RecordedTake? {
                val truncated = terminal.recordingTruncated
                val cast = terminal.stopRecording() ?: return null
                return RecordedTake(cast, terminal.recordingSeconds, truncated,
                    teamDestination = terminal.recordingToTeam)
            }
        }
        SessionRecordingController(port, title, session.hostId, ::exportFileGuarded,
            reportSaved = { host, seconds -> saved(host, seconds) })
    }
    var choosing by remember(controller) { mutableStateOf(false) }
    val perform: (RecordingDestination?) -> Unit = { destination ->
        actionScope.launch {
            when (val result = controller.toggle(destination)) {
                RecordingAction.ChooseDestination -> choosing = true
                is RecordingAction.Finished -> done(result.outcome)
                RecordingAction.Started, RecordingAction.Ignored -> Unit
            }
        }
    }
    if (choosing) RecordingDestinationDialog(
        onChoose = { destination -> choosing = false; perform(destination) },
        onDismiss = { choosing = false },
    )
    return { perform(null) }
}

@Composable
private fun RecordingDestinationDialog(onChoose: (RecordingDestination) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(Res.string.term_record_destination)
    Popup(alignment = Alignment.Center, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        ModalScrim(onDismiss = onDismiss, label = title) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(20.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Skerry.colors.surfaceDeep)
                    .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp)).consumeClicks().padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Txt(title, color = Skerry.colors.text, size = 16.sp)
                GhostButton(stringResource(Res.string.term_record_local),
                    onClick = { onChoose(RecordingDestination.LOCAL) }, modifier = Modifier.fillMaxWidth())
                PrimaryButton(stringResource(Res.string.term_record_team_and_local),
                    onClick = { onChoose(RecordingDestination.TEAM_AND_LOCAL) }, modifier = Modifier.fillMaxWidth())
                CancelButton(stringResource(Res.string.shell_cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
