package app.skerry.ui.runbook

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.session.SessionsController
import app.skerry.ui.terminal.TerminalScreenState

/**
 * How many rows off the bottom of the buffer the runner searches for a step's marker.
 *
 * Not the whole scrollback: flattening thousands of rows several times a second would cost more on
 * Android than the feature is worth, and the marker is the last thing a finished step prints. The
 * window is generous enough that a burst of output arriving between two polls can't push it out of
 * view — and if a background job ever does bury it, the run stalls visibly with a Stop button
 * rather than reporting a status it didn't read.
 */
private const val TAIL_ROWS = 400

/**
 * Binds a runbook run to a live terminal: input goes through the same guarded path as typed text
 * (so the production guard still asks about a dangerous step), the marker is looked for in the
 * bottom of the buffer, and the run ends by itself when the session does. [controller] is the same
 * session's connection, which is where a transfer step gets its SFTP channel ([sftpOpener]).
 */
fun runbookTarget(
    sessionId: String,
    terminal: TerminalScreenState,
    controller: ConnectionController?,
): RunbookTarget = RunbookTarget(
    sessionId = sessionId,
    send = terminal::sendUserInputGuarded,
    readOutput = { terminal.tailText(TAIL_ROWS) },
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

/** The last [rows] rows of the terminal buffer as plain text, one row per line. */
internal fun TerminalScreenState.tailText(rows: Int): String {
    val grid = screen
    return grid.subList((grid.size - rows).coerceAtLeast(0), grid.size)
        .joinToString("\n") { row -> buildString { row.forEach { append(it.text) } }.trimEnd() }
}
