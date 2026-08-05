package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.terminal.TerminalStepMark

/** Where a run is happening: one terminal session, addressed only through what the runner needs. */
class RunbookTarget(
    /** Tab the run belongs to — the UI shows the run there and nowhere else. */
    val sessionId: String,
    /** Sends a line to that terminal (bound to the guarded input path, production guard included). */
    val send: (String) -> Unit,
    /**
     * Declares the step about to be sent: which token the terminal should report, and the fragments
     * of the line that are protocol rather than the operator's command, so their echo is not drawn.
     * `null` ends it — nothing is captured or hidden until the next step.
     */
    val expectStep: (String?, List<String>) -> Unit,
    /**
     * Takes the step's report once its probe has emitted it — exit code plus what the command
     * printed ([TerminalStepMark]). `null` while the step is still running.
     */
    val takeMark: (String) -> TerminalStepMark?,
    /**
     * Counter of output batches from the host. Only the watchdog reads it: a step that has printed
     * nothing for long enough and still hasn't reported is flagged as possibly stuck.
     */
    val outputVersion: () -> Long,
    /** How the host names itself in the run — the pane's title. */
    val label: String = sessionId,
    /** Whether the session is still open; a dropped session ends the run. */
    val isLive: () -> Boolean = { true },
    /**
     * Opens an SFTP channel on the same connection, for [RunbookStep.Transfer] steps. `null` where
     * the transport has none (local shell, telnet, serial) — such a step fails with
     * [RunbookStepFailure.NoSftpChannel] rather than waiting for something that can't happen.
     */
    val openSftp: (suspend () -> SftpClient)? = null,
)

/** Why a step ended without an exit code of its own. */
sealed interface RunbookStepFailure {

    /** The step wanted SFTP and this session has none. */
    data object NoSftpChannel : RunbookStepFailure

    /** The transfer itself failed; [message] is what the SFTP layer reported. */
    data class Transfer(val message: String) : RunbookStepFailure
}

/** Where a step of the current run stands. */
enum class RunbookStepStatus {
    /** Not reached yet. */
    PENDING,

    /** The run is paused here waiting for the user's go-ahead ([RunbookStep.confirm]). */
    AWAITING_CONFIRM,

    /** Sent to the shell (or moving over SFTP); waiting for it to report. */
    RUNNING,
    SUCCEEDED,

    /** Failed — a non-zero exit code, or a transfer that threw ([RunbookStepState.failure]). */
    FAILED,

    /** The user skipped it at the confirmation pause. */
    SKIPPED,

    /** The run was stopped (by the user or by losing the session) while this step was pending on it. */
    STOPPED,
}

/** Where a run stands. */
enum class RunbookPhase { AWAITING_CONFIRM, RUNNING, DONE, FAILED, STOPPED }

/** One step's live state on one host. */
@Stable
class RunbookStepState internal constructor(val index: Int, val step: RunbookStep) {
    var status: RunbookStepStatus by mutableStateOf(RunbookStepStatus.PENDING)
        internal set

    /** Exit code the shell reported, once it has; `null` while the step hasn't finished. */
    var exitCode: Int? by mutableStateOf(null)
        internal set

    /**
     * The step has printed nothing for a long while and still hasn't reported a status — the shape
     * a step takes when its closing probe will never run: an unterminated here-doc or quote leaves
     * the shell at its continuation prompt, `exec` replaces the shell that would have run it, a
     * non-POSIX shell has no `$?` to report.
     *
     * A guess, deliberately: `sleep 3600` and a silent migration look identical from here. So it
     * only marks the step, never ends it — see [RunbookRunner].
     */
    var stalled: Boolean by mutableStateOf(false)
        internal set

    /** Why the step ended without an exit code (transfer steps only); `null` for the ordinary path. */
    var failure: RunbookStepFailure? by mutableStateOf(null)
        internal set

    /** Bytes moved so far by a [RunbookStep.Transfer]; `null` before the transfer reports anything. */
    var transferredBytes: Long? by mutableStateOf(null)
        internal set

    /** Size the transfer is working towards, as the SFTP layer reports it. */
    var totalBytes: Long? by mutableStateOf(null)
        internal set

    internal var startedAtMillis: Long? by mutableStateOf(null)
    internal var finishedAtMillis: Long? by mutableStateOf(null)

    /** How long the step took, once it has finished; `null` while it is still running. */
    val durationMillis: Long?
        get() {
            val started = startedAtMillis ?: return null
            val finished = finishedAtMillis ?: return null
            return finished - started
        }

    /**
     * What the command printed, cut out of the terminal between the step's two marks
     * ([TerminalStepMark]). Held only for as long as the run is on screen and never written
     * anywhere: a command's output can carry as much of a secret as its command line.
     *
     * `null` for a step that hasn't finished and for a transfer, which prints nothing — its progress
     * is [transferredBytes] instead.
     */
    var output: String? by mutableStateOf(null)
        internal set

    /**
     * Whether the step finished with its output lost rather than empty: the rows it printed are not
     * there to quote ([TerminalStepMark]) — the terminal was resized, cleared or reset while it ran,
     * the step ran on the alt screen, or its opening mark never arrived.
     * The panel says so instead of reporting a silent command — the difference is what an operator
     * asking "why did this succeed with no logs?" needs.
     */
    var outputLost: Boolean by mutableStateOf(false)
        internal set
}

/**
 * A run inside one session: its copy of the step list, where it stands, and which tab it belongs to.
 * Held apart from [RunbookRunner] so the screens can read one live object instead of six fields.
 */
@Stable
class RunbookSessionRun internal constructor(internal val target: RunbookTarget, steps: List<RunbookStep>) {

    val sessionId: String get() = target.sessionId

    /** How the session names itself — the pane's title, shown in the bar and in the log. */
    val label: String get() = target.label

    val steps: List<RunbookStepState> = steps.mapIndexed { index, step -> RunbookStepState(index, step) }

    var phase: RunbookPhase by mutableStateOf(RunbookPhase.RUNNING)
        internal set

    /** Step the run is on (sent or awaiting confirmation); -1 before the first one. */
    var currentIndex: Int by mutableStateOf(-1)
        internal set

    /** Whether any step failed, including ones the runbook tolerates. */
    var hadFailures: Boolean by mutableStateOf(false)
        internal set

    /** Steps that already have a verdict — what "3 of 7" counts. */
    val finishedCount: Int
        get() = steps.count {
            it.status == RunbookStepStatus.SUCCEEDED ||
                it.status == RunbookStepStatus.FAILED ||
                it.status == RunbookStepStatus.SKIPPED
        }
}
