package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.sftp.SftpClient

/** Where a run is happening: one terminal session, addressed only through what the runner needs. */
class RunbookTarget(
    /** Tab the run belongs to — the UI shows the run there and nowhere else. */
    val sessionId: String,
    /** Sends a line to that terminal (bound to the guarded input path, production guard included). */
    val send: (String) -> Unit,
    /** Recent terminal text to look for the step's marker in (a tail, not the whole scrollback). */
    val readOutput: () -> String,
    /** How the host names itself in the run — the pane's title. */
    val label: String = sessionId,
    /** Whether the session is still open; a dropped session ends this host's part of the run. */
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

/**
 * Where a run — or one host's part of it — stands. [WAITING] only happens on a host: with
 * [app.skerry.shared.runbook.RunbookParallelism.ONE_HOST_AT_A_TIME] the hosts after the current one
 * have not been touched yet.
 */
enum class RunbookPhase { WAITING, AWAITING_CONFIRM, RUNNING, DONE, FAILED, STOPPED }

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
     * a step takes when nothing will ever print its marker: an unterminated here-doc or quote leaves
     * the shell at its continuation prompt, `exec` replaces the shell that would have printed it, a
     * non-POSIX shell never had `$?` to print.
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
     * What the command printed, cut out of the terminal between its echo and its marker
     * ([runbookStepOutput]). Held only for as long as the run is on screen and never written
     * anywhere: a command's output can carry as much of a secret as its command line.
     *
     * `null` for a step that hasn't finished and for a transfer, which prints nothing — its progress
     * is [transferredBytes] instead.
     */
    var output: String? by mutableStateOf(null)
        internal set
}

/**
 * One host's part of a run: its own copy of the step list, its own place in the procedure. Hosts are
 * separate because they genuinely diverge — with
 * [app.skerry.shared.runbook.RunbookParallelism.ALL_HOSTS_AT_ONCE] a fast host is three steps ahead
 * of a slow one, and a failure on one may or may not end the others (see
 * [app.skerry.shared.runbook.RunbookPolicy.stopOnFirstFailure]).
 */
@Stable
class RunbookHostRun internal constructor(internal val target: RunbookTarget, steps: List<RunbookStep>) {

    val sessionId: String get() = target.sessionId

    /** How the host names itself in the run's host list. */
    val label: String get() = target.label

    val steps: List<RunbookStepState> = steps.mapIndexed { index, step -> RunbookStepState(index, step) }

    var phase: RunbookPhase by mutableStateOf(RunbookPhase.WAITING)
        internal set

    /** Step this host is on (sent or awaiting confirmation); -1 before the first one. */
    var currentIndex: Int by mutableStateOf(-1)
        internal set

    /** Whether any step on this host failed, including ones the runbook tolerates. */
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
