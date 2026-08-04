package app.skerry.ui.runbook

import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.connection.ConnectionController
import app.skerry.ui.connection.ConnectionUiState
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
 * Run targets for [paneIds], in the order given — which, with
 * [app.skerry.shared.runbook.RunbookParallelism.ONE_HOST_AT_A_TIME], is the rollout order.
 *
 * A pane that has gone away or dropped its connection between the pick and the start is left out
 * rather than turned into a target that could never send anything; the caller sees a shorter list
 * and can say so.
 */
fun runbookTargets(sessions: SessionsController, paneIds: List<String>): List<RunbookTarget> {
    val panes = sessions.tabs.flatMap { it.panes }.associateBy { it.id }
    return paneIds.mapNotNull { id ->
        val pane = panes[id] ?: return@mapNotNull null
        val terminal = (pane.controller.uiState as? ConnectionUiState.Connected)?.terminal ?: return@mapNotNull null
        runbookTarget(pane.id, terminal, pane.controller).withLabel(pane.displayTitle)
    }
}

/** Panes a run can be pointed at: connected terminals, remote desktops and players excluded. */
fun connectedRunbookPanes(sessions: SessionsController?): List<RunbookLaunchTarget.Session> =
    sessions?.tabs.orEmpty().flatMap { tab -> tab.panes }
        .filter { pane -> !pane.isVnc && !pane.isPlayer && pane.controller.uiState is ConnectionUiState.Connected }
        .map { pane -> RunbookLaunchTarget.Session(pane.id, pane.displayTitle) }

/** A connected pane of [hostId], or `null` while that host has none — what a launch waits on. */
fun connectedPaneOf(sessions: SessionsController?, hostId: String): String? =
    sessions?.tabs.orEmpty().flatMap { tab -> tab.panes }
        .firstOrNull { pane ->
            pane.hostId == hostId && !pane.isVnc && !pane.isPlayer &&
                pane.controller.uiState is ConnectionUiState.Connected
        }?.id

/** The same target under the name the run should call it by (the pane's title). */
private fun RunbookTarget.withLabel(label: String): RunbookTarget = RunbookTarget(
    sessionId = sessionId,
    send = send,
    readOutput = readOutput,
    label = label,
    isLive = isLive,
    openSftp = openSftp,
)

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
