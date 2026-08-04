package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.ResolvedRunbookStep
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookScript
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.captureSnippetRunEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a run is happening: one terminal session, addressed only through what the runner needs. */
class RunbookTarget(
    /** Tab the run belongs to — the UI shows the panel there and nowhere else. */
    val sessionId: String,
    /** Sends a line to that terminal (bound to the guarded input path, production guard included). */
    val send: (String) -> Unit,
    /** Recent terminal text to look for the step's marker in (a tail, not the whole scrollback). */
    val readOutput: () -> String,
    /** Whether the session is still open; a dropped session aborts the run. */
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

    /** Sent to the shell; waiting for its exit code. */
    RUNNING,
    SUCCEEDED,

    /** Exited non-zero. Ends the run unless the step is [RunbookStep.continueOnError]. */
    FAILED,

    /** The user skipped it at the confirmation pause. */
    SKIPPED,

    /** The run was stopped (by the user or by losing the session) while this step was pending on it. */
    STOPPED,
}

/** Where the run as a whole stands; `null` on the runner means no run at all. */
enum class RunbookPhase { AWAITING_CONFIRM, RUNNING, DONE, FAILED, STOPPED }

/**
 * A run waiting on its start dialog: the runbook, its steps already parsed with this run's draw of
 * machine variables ([script]), and where it will run. [recording] — the target terminal is
 * recording a cast, so the dialog warns that resolved lines (secrets included) will be captured.
 */
@Stable
class RunbookStartRequest internal constructor(
    val runbook: Runbook,
    val script: RunbookScript,
    val target: RunbookTarget,
    val recording: Boolean,
)

/** One step's live state in the progress list. */
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
     * only marks the step, never ends it — see [RunbookRunner.watch].
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
}

/**
 * Drives one runbook through one terminal session: sends a step, waits for its exit code, pauses
 * where the runbook asks for a confirmation, and stops on a failure.
 *
 * Steps go through the ordinary terminal input path rather than an exec channel, so `cd`, exported
 * variables and a cached `sudo` ticket carry from step to step, everything is visible in the
 * session the user is watching, and the production guard applies exactly as it does to typed input.
 * The exit code comes back through a marker printed after the command (see [RunbookMarker]), which
 * the runner polls for — a PTY has no other way to report status.
 *
 * One run at a time, app-wide: a run outlives the panel showing it (switching tabs must not abandon
 * a half-finished procedure), so its lifecycle is owned here and ended explicitly by [stop]/[close].
 * The vault gate calls [close] on lock — the resolved values of this run may include secrets.
 *
 * The resolved command lines are never stored, only sent: they can carry a `${{vault}}` secret, and
 * the terminal the user is already looking at is the honest record of what ran.
 */
@Stable
class RunbookRunner(
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val environment: () -> SnippetRunEnvironment = ::captureSnippetRunEnvironment,
    /**
     * How often the terminal tail is searched for the current step's marker. Small enough that a
     * quick step doesn't feel stalled, large enough that scanning the tail is free in comparison.
     */
    private val pollIntervalMillis: Long = 120L,
) {
    var runbook: Runbook? by mutableStateOf(null)
        private set

    var steps: List<RunbookStepState> by mutableStateOf(emptyList())
        private set

    var phase: RunbookPhase? by mutableStateOf(null)
        private set

    /** Step the run is on (sent or awaiting confirmation); -1 before the first one. */
    var currentIndex: Int by mutableStateOf(-1)
        private set

    /** Session the run belongs to; the panel only shows up in that tab. */
    var sessionId: String? by mutableStateOf(null)
        private set

    /** Whether any step exited non-zero, including ones the runbook tolerates. */
    var hadFailures: Boolean by mutableStateOf(false)
        private set

    /**
     * Run being set up: the start dialog is showing it and collecting the values its `${{…}}`
     * placeholders need. `null` when no dialog is open.
     */
    var pending: RunbookStartRequest? by mutableStateOf(null)
        private set

    /** Whether a run is in flight (sending or waiting for a confirmation). */
    val active: Boolean get() = phase == RunbookPhase.RUNNING || phase == RunbookPhase.AWAITING_CONFIRM

    private var script: RunbookScript? = null
    private var contextValue: (SnippetSegment.Variable) -> String = { "" }
    private var target: RunbookTarget? = null

    /** Policy of the runbook being run; the default only stands in while nothing is running. */
    private var policy: RunbookPolicy = RunbookPolicy()
    private var runId: String = ""
    private var watchJob: Job? = null

    // Bumped by every start/stop/close. A watcher captures it and drops its result if it no longer
    // matches — otherwise a poll that completed just as the user hit Stop would resurrect the run
    // and type the NEXT step into a live terminal. The watcher runs on a multi-threaded scope while
    // Stop comes from the UI thread, so the stamp is read and written under a lock and the whole
    // check-then-act is inside it (same guard as PingController's post-stop measurement, which is
    // locked for exactly this reason).
    private val lock = Any()
    private var generation: Int = 0

    /**
     * Prepares a run of [runbook] in [target] and parks it for the confirmation dialog. The script
     * is built (and its machine variables drawn) here rather than at [confirmStart], so the lines
     * the dialog previews are the ones that will be sent. Returns false — changing nothing — if a
     * run is already in flight or being set up, or the runbook has no steps.
     */
    fun requestStart(runbook: Runbook, target: RunbookTarget, recording: Boolean = false): Boolean {
        if (active || pending != null) return false
        if (runbook.steps.isEmpty()) return false
        pending = RunbookStartRequest(
            runbook = runbook,
            script = RunbookScript.of(runbook, environment()),
            target = target,
            recording = recording,
        )
        return true
    }

    /** Closes the start dialog without running anything. */
    fun dismissStart() {
        pending = null
    }

    /**
     * The start dialog was confirmed: begins the parked run. [contextValue] supplies the
     * clipboard/vault/prompted values it collected and is called per step, so every step gets what
     * the user actually confirmed.
     */
    fun confirmStart(contextValue: (SnippetSegment.Variable) -> String): Boolean {
        val request = pending ?: return false
        pending = null
        return start(request, contextValue)
    }

    /** Starts a prepared [request]. Returns false (changing nothing) if a run is already in flight. */
    fun start(request: RunbookStartRequest, contextValue: (SnippetSegment.Variable) -> String): Boolean {
        if (active) return false
        synchronized(lock) {
            generation++
            watchJob?.cancel()
            watchJob = null
        }
        this.runbook = request.runbook
        this.policy = request.runbook.policy
        this.target = request.target
        this.contextValue = contextValue
        this.script = request.script
        this.runId = newId()
        this.sessionId = request.target.sessionId
        this.hadFailures = false
        this.steps = request.runbook.steps.mapIndexed { index, step -> RunbookStepState(index, step) }
        advance(0)
        return true
    }

    /** The user approved the step the run is paused on. No-op if it isn't paused on one. */
    fun confirmStep() {
        if (phase != RunbookPhase.AWAITING_CONFIRM) return
        dispatchStep(currentIndex)
    }

    /** The user skipped the step the run is paused on; the run continues with the next one. */
    fun skipStep() {
        if (phase != RunbookPhase.AWAITING_CONFIRM) return
        val index = currentIndex
        steps.getOrNull(index)?.status = RunbookStepStatus.SKIPPED
        advance(index + 1)
    }

    /**
     * Ends the run where it stands: the step in flight is left as [RunbookStepStatus.STOPPED] and
     * nothing further is sent. What the shell is already running keeps running — the runner types
     * into a terminal, it doesn't own the remote process.
     */
    fun stop() {
        if (!active) return
        // The step's own state is settled inside the same critical section as the generation bump:
        // markStalled re-reads the generation under this lock, so a poll racing Stop is refused
        // rather than allowed to re-flag a step this call has just finished with.
        synchronized(lock) {
            generation++
            watchJob?.cancel()
            watchJob = null
            steps.getOrNull(currentIndex)?.let {
                if (it.status == RunbookStepStatus.RUNNING || it.status == RunbookStepStatus.AWAITING_CONFIRM) {
                    it.status = RunbookStepStatus.STOPPED
                }
                it.stalled = false // the run is over; nothing is waiting on this step any more
            }
        }
        phase = RunbookPhase.STOPPED
    }

    /** Dismisses the run entirely (stopping it first if needed) and forgets its resolved values. */
    fun close() {
        stop()
        synchronized(lock) {
            generation++
            watchJob?.cancel()
            watchJob = null
        }
        pending = null
        runbook = null
        steps = emptyList()
        phase = null
        currentIndex = -1
        sessionId = null
        hadFailures = false
        script = null
        // Drop the closure holding this run's clipboard/vault/parameter values.
        contextValue = { "" }
        target = null
        runId = ""
    }

    /** Moves to step [index], pausing there if it asks to be confirmed; past the end ends the run. */
    private fun advance(index: Int) {
        val state = steps.getOrNull(index)
        if (state == null) {
            currentIndex = steps.lastIndex
            phase = RunbookPhase.DONE
            return
        }
        currentIndex = index
        if (state.step.confirm) {
            state.status = RunbookStepStatus.AWAITING_CONFIRM
            phase = RunbookPhase.AWAITING_CONFIRM
        } else {
            dispatchStep(index)
        }
    }

    /** Starts step [index] the way its kind is run: typed into the shell, or moved over SFTP. */
    private fun dispatchStep(index: Int) {
        val state = steps.getOrNull(index) ?: return
        val target = target ?: return
        val resolved = script?.resolve(index, contextValue) ?: return
        state.status = RunbookStepStatus.RUNNING
        phase = RunbookPhase.RUNNING
        currentIndex = index
        when (resolved) {
            is ResolvedRunbookStep.Command -> {
                val token = RunbookMarker.token(runId, index)
                target.send(RunbookMarker.probeLine(resolved.line, token) + "\n")
                watch(index, token, target)
            }
            is ResolvedRunbookStep.Transfer -> transfer(index, resolved, target)
        }
    }

    /**
     * Moves a [RunbookStep.Transfer]'s file over the session's own SFTP channel. Unlike a command,
     * there is no shell and no exit code here: the SFTP call either returns or throws, and the
     * throw is what the step reports ([RunbookStepFailure.Transfer]).
     *
     * Cancellation is the same story as [watch]: Stop bumps the generation and cancels the job, and
     * the result is only applied while the stamp still matches — a transfer that completed just as
     * the user hit Stop must not advance the run into the next command.
     */
    private fun transfer(index: Int, step: ResolvedRunbookStep.Transfer, target: RunbookTarget) {
        val generationAtStart = synchronized(lock) { generation }
        val job = scope.launch {
            val outcome = runCatching {
                val open = target.openSftp ?: throw NoSftpChannelException()
                val client = open()
                val progress = SftpProgress { done, total -> publishProgress(index, generationAtStart, done, total) }
                when (step.direction) {
                    RunbookTransferDirection.UPLOAD -> client.upload(step.localPath, step.remotePath, progress)
                    RunbookTransferDirection.DOWNLOAD -> client.download(step.remotePath, step.localPath, progress)
                }
            }
            // A cancelled transfer is Stop's business, not a failure of the step: rethrow so the
            // coroutine ends cancelled and nothing below reports on a run that is already over.
            outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            synchronized(lock) {
                if (generationAtStart != generation) return@launch
                val error = outcome.exceptionOrNull()
                if (error == null) finishStep(index, exitCode = 0) else failStep(index, failureOf(error))
            }
        }
        synchronized(lock) {
            if (generationAtStart == generation) watchJob = job else job.cancel()
        }
    }

    /** Publishes transfer progress under the generation guard the rest of the run uses. */
    private fun publishProgress(index: Int, generationAtStart: Int, done: Long, total: Long) = synchronized(lock) {
        if (generationAtStart != generation) return@synchronized
        val state = steps.getOrNull(index) ?: return@synchronized
        state.transferredBytes = done
        state.totalBytes = total
    }

    /**
     * Waits for step [index]'s marker to show up in the terminal. Polling rather than reacting to
     * output: the run must survive the panel leaving composition, and the marker sits in the buffer
     * once printed, so a poll can't miss it the way a dropped event would.
     *
     * There is deliberately no per-step timeout — a migration or a package build legitimately takes
     * an hour, and killing the procedure on a guess would be worse than waiting. A step that can
     * never report (a shell without `$?`, a command still waiting on stdin) is ended by the user
     * with [stop] — so the run says when a step looks like that one
     * ([RunbookStepState.stalled]) instead of waiting silently forever: no output for
     * [RunbookPolicy.watchdogMinutes] and no marker. Only the terminal's size and hash are kept between polls,
     * never its text — a step's resolved line can carry a `${'$'}{{vault}}` secret and the run stores none.
     */
    private fun watch(index: Int, token: String, target: RunbookTarget) {
        val generationAtStart = synchronized(lock) { generation }
        val job = scope.launch {
            var lastPrint: Pair<Int, Int>? = null
            var quietMillis = 0L
            while (true) {
                delay(pollIntervalMillis)
                if (stale(generationAtStart)) return@launch
                if (!target.isLive()) {
                    stop()
                    return@launch
                }
                val text = target.readOutput()
                val print = text.length to text.hashCode()
                if (print == lastPrint) quietMillis += pollIntervalMillis else quietMillis = 0
                lastPrint = print
                val code = RunbookMarker.exitCodeIn(text, token)
                if (code == null) {
                    val watchdog = policy.watchdogMinutes
                    markStalled(index, generationAtStart, watchdog > 0 && quietMillis >= watchdog * MILLIS_PER_MINUTE)
                    continue
                }
                // Check-then-act under the lock: without it a Stop landing between the check and
                // finishStep would still let the run advance and send the next command.
                synchronized(lock) {
                    if (generationAtStart != generation) return@launch
                    finishStep(index, code)
                }
                return@launch
            }
        }
        synchronized(lock) {
            // A Stop that ran while this coroutine was being launched already bumped the stamp;
            // publishing the job then would leave it uncancelled, so cancel it here instead.
            if (generationAtStart == generation) watchJob = job else job.cancel()
        }
    }

    private fun stale(generationAtStart: Int): Boolean = synchronized(lock) { generationAtStart != generation }

    /**
     * Marks (or unmarks) step [index] as possibly stuck, under the same generation guard as
     * [finishStep]: a poll landing just after Stop must not put a warning on a run that is over.
     */
    private fun markStalled(index: Int, generationAtStart: Int, stalled: Boolean) = synchronized(lock) {
        if (generationAtStart != generation) return@synchronized
        val state = steps.getOrNull(index) ?: return@synchronized
        if (state.stalled != stalled) state.stalled = stalled
    }

    private fun finishStep(index: Int, exitCode: Int) {
        val state = steps.getOrNull(index) ?: return
        state.exitCode = exitCode
        // It reported after all: whatever the warning said, the step was only slow.
        state.stalled = false
        if (exitCode == 0) {
            state.status = RunbookStepStatus.SUCCEEDED
            advance(index + 1)
            return
        }
        failStep(index, failure = null)
    }

    /**
     * Ends step [index] as failed and decides where the run goes from there: on to the next step
     * when the step tolerates its own failure ([RunbookStep.continueOnError]) or the runbook doesn't
     * stop on failures at all ([RunbookPolicy.stopOnFirstFailure]), otherwise the run ends here.
     */
    private fun failStep(index: Int, failure: RunbookStepFailure?) {
        val state = steps.getOrNull(index) ?: return
        state.status = RunbookStepStatus.FAILED
        state.failure = failure
        state.stalled = false
        hadFailures = true
        val carryOn = state.step.continueOnError || !policy.stopOnFirstFailure
        if (carryOn) advance(index + 1) else phase = RunbookPhase.FAILED
    }
}

/** How long a watchdog minute is; the policy is in minutes, the poll loop counts milliseconds. */
private const val MILLIS_PER_MINUTE = 60_000L

/** Raised in place of opening a channel the session doesn't have — reported, never surfaced raw. */
private class NoSftpChannelException : Exception("This session has no SFTP channel")

/** What a failed transfer is reported as; the UI turns this into its own wording. */
private fun failureOf(error: Throwable): RunbookStepFailure = when (error) {
    is NoSftpChannelException -> RunbookStepFailure.NoSftpChannel
    else -> RunbookStepFailure.Transfer(error.message ?: error::class.simpleName.orEmpty())
}
