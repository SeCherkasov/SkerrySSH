package app.skerry.ui.runbook

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.terminal.TerminalScreenState

/**
 * Binds a runbook run to a live terminal: input goes through the same guarded path as typed text
 * (so the production guard still asks about a dangerous step), the step's status arrives out of band
 * through the terminal's step-mark channel, and the run ends by itself when the session does.
 * [controller] is the same session's connection, which is where a transfer step gets its SFTP
 * channel ([sftpOpener]).
 */
fun runbookTarget(
    sessionId: String,
    terminal: TerminalScreenState,
    controller: ConnectionController?,
): RunbookTarget = RunbookTarget(
    sessionId = sessionId,
    send = terminal::sendUserInputGuarded,
    expectStep = terminal::expectStepMark,
    takeMark = terminal::takeStepMark,
    outputVersion = { terminal.outputVersion },
    isLive = { terminal.state.value !is TerminalState.Closed },
    openSftp = controller?.let(::sftpOpener),
)

/**
 * The run of the tab the user is looking at, or `null` when the run belongs to another tab. Checked
 * against every pane of the tab, not just the focused one: a run started in the left pane of a split
 * belongs to that tab whichever pane has the caret.
 */
internal fun RunbookRunner.runInActiveTab(sessions: SessionsController?): RunbookSessionRun? =
    sessions?.activeTerminal?.panes?.firstNotNullOfOrNull { runIn(it.id) }

/**
 * How a transfer step opens its channel on [controller]'s connection, or `null` where that
 * connection has none — Mosh, Telnet, serial, the local shell and container exec are stream-only
 * ([ConnectionController.supportsSftp]). The step then fails as
 * [RunbookStepFailure.NoSftpChannel] and says so, instead of surfacing a transport exception.
 */
internal fun sftpOpener(controller: ConnectionController): (suspend () -> SftpClient)? =
    if (controller.supportsSftp) ({ controller.openSftp() }) else null
