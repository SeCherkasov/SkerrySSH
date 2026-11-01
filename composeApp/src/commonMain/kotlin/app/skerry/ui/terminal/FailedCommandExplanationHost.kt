package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiPolicyDecision
import app.skerry.shared.terminal.FailedCommandSnapshot
import app.skerry.shared.terminal.ShellCommandMark
import app.skerry.shared.terminal.failedCommandSnapshot
import app.skerry.ui.app.LocalAi
import app.skerry.ui.app.LocalHosts
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.session.Session

/** Captured on invocation, not on confirmation; never resolves through the subsequently focused pane. */
internal class FailedCommandTarget(
    val session: Session,
    val terminal: TerminalScreenState,
    val snapshot: FailedCommandSnapshot?,
    val hostId: String? = session.hostId,
)

internal fun failedCommandTarget(session: Session, terminal: TerminalScreenState, mark: ShellCommandMark?): FailedCommandTarget =
    FailedCommandTarget(
        session, terminal,
        if (!terminal.altScreen && mark != null && mark in terminal.commandMarks) failedCommandSnapshot(terminal.screen, mark) else null,
    )

internal val TerminalScreenState.lastFailedCommand: ShellCommandMark?
    get() = commandMarks.lastOrNull { (it.exitCode ?: 0) > 0 }

@Composable
internal fun failedCommandExplanationEnabled(session: Session?): Boolean {
    val policy = session?.hostId?.let { LocalHosts.current?.find(it)?.aiPolicy } ?: AiPolicy.Strict
    return session != null && !session.controller.isWatched && LocalAi.current?.enabled == true && AiPolicyDecision.of(policy).aiEnabled
}

@Composable
internal fun FailedCommandExplanationHost(target: FailedCommandTarget, onDismiss: () -> Unit) {
    val ai = LocalAi.current
    val hosts = LocalHosts.current
    val sessions = LocalSessions.current
    val policy = { target.hostId?.let { hosts?.find(it)?.aiPolicy } ?: AiPolicy.Strict }
    val current = {
        sessions?.tabs?.any { tab -> tab.panes.any { it === target.session } } == true &&
            target.session.hostId == target.hostId &&
            (target.session.controller.uiState as? ConnectionUiState.Connected)?.terminal === target.terminal &&
            !target.session.controller.isWatched
    }
    val controller = remember(target, ai) { ai?.failedCommandController(policy, current) }
    DisposableEffect(controller) { onDispose { controller?.cancel() } }
    LaunchedEffect(controller) { controller?.prepare(target.snapshot) }
    LaunchedEffect(controller, current(), policy(), ai?.settings) { controller?.revalidate() }
    if (controller != null) {
        ExplainFailedCommandDialog(controller, target.terminal, onDismiss, canRun = {
            current() && ai?.enabled == true && AiPolicyDecision.of(policy()).aiEnabled
        }, onBeforeRun = {
            sessions?.tabs?.firstOrNull { tab -> tab.panes.any { it === target.session } }?.let { tab ->
                sessions.activate(tab.id)
                sessions.focusPane(tab.id, target.session.id)
            }
        })
    } else {
        LaunchedEffect(Unit) { onDismiss() }
    }
}
