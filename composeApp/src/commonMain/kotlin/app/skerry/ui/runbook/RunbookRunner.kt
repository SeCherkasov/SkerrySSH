package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.ResolvedRunbookStep
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookHostOutcome
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookRunOutcome
import app.skerry.shared.runbook.RunbookRunRecord
import app.skerry.shared.runbook.RunbookScript
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.shared.sftp.SftpProgress
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.snippet.captureSnippetRunEnvironment
import app.skerry.shared.terminal.epochMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A run waiting on its start dialog: the runbook, its steps already parsed with this run's draw of
 * machine variables ([script]), and the session it will run in. [recording] — the target terminal is
 * recording a cast, so the dialog warns that resolved lines (secrets included) will be captured.
 */
@Stable
class RunbookStartRequest internal constructor(
    val runbook: Runbook,
    val script: RunbookScript,
    val target: RunbookTarget,
    val recording: Boolean,
)

/**
 * Drives one runbook through one terminal session: sends a step, waits for its exit code, pauses
 * where the runbook asks for a confirmation, and stops on a failure the policy doesn't tolerate.
 *
 * One session, deliberately. A procedure fanned out across a fleet has to guess what to do when one
 * host lags, one drops its connection and one stops to ask for a password — and every guess is
 * wrong somewhere. Rolling the same runbook onto the next host is a second run, started once the
 * operator has seen the first one land.
 *
 * Command steps go through the ordinary terminal input path rather than an exec channel, so `cd`,
 * exported variables and a cached `sudo` ticket carry from step to step, everything is visible in
 * the session the user is watching, and the production guard applies exactly as it does to typed
 * input. The exit code comes back through a marker printed after the command (see [RunbookMarker]),
 * which the runner polls for — a PTY has no other way to report status. Transfer steps have no
 * shell involved at all: they move a file over the session's SFTP channel and report what the
 * transfer threw, if anything.
 *
 * One run at a time, app-wide: a run outlives the screen showing it (switching tabs must not
 * abandon a half-finished procedure), so its lifecycle is owned here and ended explicitly by
 * [stop]/[close]. The vault gate calls [close] on lock — the resolved values of this run may include
 * secrets.
 *
 * The resolved command lines are never stored, only sent: they can carry a `${'$'}{{vault}}` secret, and
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
    /** Wall clock behind step durations; injected so tests can run on virtual time. */
    private val now: () -> Long = ::epochMillis,
    /**
     * Called once when a run ends, whichever way it ends — what the history log is written from.
     * The record carries no command lines and no output: see [RunbookRunRecord].
     */
    private val onFinished: (RunbookRunRecord) -> Unit = {},
) {
    var runbook: Runbook? by mutableStateOf(null)
        private set

    /** The run in hand: its steps, where it stands, which session it belongs to. */
    var run: RunbookSessionRun? by mutableStateOf(null)
        private set

    /** Where the run stands; `null` means no run at all. */
    var phase: RunbookPhase? by mutableStateOf(null)
        private set

    /**
     * Run being set up: the start dialog is showing it and collecting the values its `${'$'}{{…}}`
     * placeholders need. `null` when no dialog is open.
     */
    var pending: RunbookStartRequest? by mutableStateOf(null)
        private set

    /** Whether a run is in flight (sending or waiting for a confirmation). */
    val active: Boolean get() = phase == RunbookPhase.RUNNING || phase == RunbookPhase.AWAITING_CONFIRM

    /** Whether any step failed, including ones the runbook tolerates. */
    val hadFailures: Boolean get() = run?.hadFailures == true

    /** The run of [sessionId]'s tab, or `null` when the run belongs to another session. */
    fun runIn(sessionId: String): RunbookSessionRun? = run?.takeIf { it.sessionId == sessionId }

    private var script: RunbookScript? = null
    private var contextValue: (SnippetSegment.Variable) -> String = { "" }
    private var policy: RunbookPolicy = RunbookPolicy()
    private var runId: String = ""
    private var startedAt: Long = 0L

    /** Whether the run in hand has already been handed to [onFinished] — it is reported once. */
    private var reported: Boolean = false

    /** The finished run's record, waiting to be handed over outside the lock ([flushReport]). */
    private var pendingReport: RunbookRunRecord? = null

    private var watchJob: Job? = null

    // Bumped by every start/stop/close. A watcher captures it and drops its result if it no longer
    // matches — otherwise a poll that completed just as the user hit Stop would resurrect the run
    // and type the NEXT step into a live terminal. The watcher runs on a multi-threaded scope while
    // Stop comes from the UI thread, so the stamp is read and written under a lock and the whole
    // check-then-act is inside it (same guard as PingController's post-stop measurement).
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
        this.contextValue = contextValue
        this.script = request.script
        this.runId = newId()
        this.startedAt = now()
        this.reported = false
        this.run = RunbookSessionRun(request.target, request.runbook.steps)
        phase = RunbookPhase.RUNNING
        advance(0)
        flushReport()
        return true
    }

    /** The user approved the step the run is paused on. No-op if it isn't paused on one. */
    fun confirmStep() {
        val run = run ?: return
        if (run.phase != RunbookPhase.AWAITING_CONFIRM) return
        dispatchStep(run.currentIndex)
        flushReport()
    }

    /** The user skipped the step the run is paused on; the run continues with the next one. */
    fun skipStep() {
        val run = run ?: return
        if (run.phase != RunbookPhase.AWAITING_CONFIRM) return
        val index = run.currentIndex
        run.steps.getOrNull(index)?.status = RunbookStepStatus.SKIPPED
        advance(index + 1)
        flushReport()
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
            run?.let { stopRun(it) }
        }
        phase = RunbookPhase.STOPPED
        report()
        flushReport()
    }

    /** Dismisses the run entirely (stopping it first if needed) and forgets its resolved values. */
    fun close() {
        stop()
        // A record parked by a run that ended without anyone flushing it (a phase settled while the
        // screen was gone) is written now — after this the run it describes no longer exists.
        flushReport()
        synchronized(lock) {
            generation++
            watchJob?.cancel()
            watchJob = null
        }
        pending = null
        runbook = null
        // Drops the captured step output with it: a command's output can carry a secret.
        run = null
        phase = null
        script = null
        // Drop the closure holding this run's clipboard/vault/parameter values.
        contextValue = { "" }
        runId = ""
    }

    /** Moves to step [index], pausing there if it asks to be confirmed; past the end ends the run. */
    private fun advance(index: Int) {
        val run = run ?: return
        val state = run.steps.getOrNull(index)
        if (state == null) {
            run.currentIndex = run.steps.lastIndex
            finish(RunbookPhase.DONE)
            return
        }
        run.currentIndex = index
        if (state.step.confirm) {
            state.status = RunbookStepStatus.AWAITING_CONFIRM
            run.phase = RunbookPhase.AWAITING_CONFIRM
            phase = RunbookPhase.AWAITING_CONFIRM
        } else {
            dispatchStep(index)
        }
    }

    /** Starts step [index] the way its kind runs: typed into the shell, or moved over SFTP. */
    private fun dispatchStep(index: Int) {
        val run = run ?: return
        val state = run.steps.getOrNull(index) ?: return
        val resolved = script?.resolve(index, contextValue) ?: return
        state.status = RunbookStepStatus.RUNNING
        state.startedAtMillis = now()
        run.phase = RunbookPhase.RUNNING
        run.currentIndex = index
        phase = RunbookPhase.RUNNING
        when (resolved) {
            is ResolvedRunbookStep.Command -> {
                val token = RunbookMarker.token(runId, index)
                run.target.send(RunbookMarker.probeLine(resolved.line, token) + "\n")
                watch(run, index, token)
            }
            is ResolvedRunbookStep.Transfer -> transfer(run, index, resolved)
        }
    }

    /**
     * Waits for step [index]'s marker to show up in the terminal. Polling rather than reacting to
     * output: the run must survive the screen leaving composition, and the marker sits in the buffer
     * once printed, so a poll can't miss it the way a dropped event would.
     *
     * There is deliberately no per-step timeout — a migration or a package build legitimately takes
     * an hour, and killing the procedure on a guess would be worse than waiting. A step that can
     * never report (a shell without `${'$'}?`, a command still waiting on stdin) is ended by the user
     * with [stop] — so the run says when a step looks like that one ([RunbookStepState.stalled])
     * instead of waiting silently forever: no output for [RunbookPolicy.watchdogMinutes] and no
     * marker. Only the terminal's size and hash are kept between polls, never its text; the step's
     * own output is read once, when its marker finally arrives.
     */
    private fun watch(run: RunbookSessionRun, index: Int, token: String) {
        val generationAtStart = synchronized(lock) { generation }
        val target = run.target
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
                    markStalled(run, index, generationAtStart, watchdog > 0 && quietMillis >= watchdog * MILLIS_PER_MINUTE)
                    continue
                }
                val output = runbookStepOutput(text, token)
                // Check-then-act under the lock: without it a Stop landing between the check and
                // finishStep would still let the run advance and send the next command.
                synchronized(lock) {
                    if (generationAtStart != generation) return@launch
                    run.steps.getOrNull(index)?.output = output
                    finishStep(index, code)
                }
                // Outside the lock: writing the run log encrypts and rewrites the vault, and Stop
                // comes from the UI thread through the same lock.
                flushReport()
                return@launch
            }
        }
        publishJob(job, generationAtStart)
    }

    /**
     * Moves a transfer step's file over the session's own SFTP channel. Unlike a command, there is
     * no shell and no exit code here: the SFTP call either returns or throws, and the throw is what
     * the step reports ([RunbookStepFailure.Transfer]).
     *
     * Cancellation is the same story as [watch]: Stop bumps the generation and cancels the job, and
     * the result is only applied while the stamp still matches — a transfer that completed just as
     * the user hit Stop must not advance the run into the next command.
     */
    private fun transfer(run: RunbookSessionRun, index: Int, step: ResolvedRunbookStep.Transfer) {
        val generationAtStart = synchronized(lock) { generation }
        val target = run.target
        val job = scope.launch {
            val outcome = runCatching {
                val open = target.openSftp ?: throw NoSftpChannelException()
                val client = open()
                val progress = SftpProgress { done, total -> publishProgress(run, index, generationAtStart, done, total) }
                // The channel belongs to whoever opened it (ConnectionController.openSftp). A run
                // with several transfer steps would otherwise pile them up on the connection until
                // the server refuses to open any more — to some unrelated feature, much later.
                try {
                    when (step.direction) {
                        RunbookTransferDirection.UPLOAD -> client.upload(step.localPath, step.remotePath, progress)
                        RunbookTransferDirection.DOWNLOAD -> client.download(step.remotePath, step.localPath, progress)
                    }
                } finally {
                    withContext(NonCancellable) { runCatching { client.close() } }
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
            flushReport()
        }
        publishJob(job, generationAtStart)
    }

    /**
     * Publishes [job] as the run's watcher, unless a Stop landed while it was being launched — that
     * already bumped the stamp, and publishing then would leave the job uncancelled.
     */
    private fun publishJob(job: Job, generationAtStart: Int) = synchronized(lock) {
        if (generationAtStart == generation) watchJob = job else job.cancel()
    }

    /** Publishes transfer progress under the generation guard the rest of the run uses. */
    private fun publishProgress(run: RunbookSessionRun, index: Int, generationAtStart: Int, done: Long, total: Long) =
        synchronized(lock) {
            if (generationAtStart != generation) return@synchronized
            val state = run.steps.getOrNull(index) ?: return@synchronized
            state.transferredBytes = done
            state.totalBytes = total
        }

    private fun stale(generationAtStart: Int): Boolean = synchronized(lock) { generationAtStart != generation }

    /**
     * Marks (or unmarks) step [index] as possibly stuck, under the same generation guard as
     * [finishStep]: a poll landing just after Stop must not put a warning on a run that is over.
     */
    private fun markStalled(run: RunbookSessionRun, index: Int, generationAtStart: Int, stalled: Boolean) =
        synchronized(lock) {
            if (generationAtStart != generation) return@synchronized
            val state = run.steps.getOrNull(index) ?: return@synchronized
            if (state.stalled != stalled) state.stalled = stalled
        }

    private fun finishStep(index: Int, exitCode: Int) {
        val state = run?.steps?.getOrNull(index) ?: return
        state.exitCode = exitCode
        state.finishedAtMillis = now()
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
        val run = run ?: return
        val state = run.steps.getOrNull(index) ?: return
        state.status = RunbookStepStatus.FAILED
        state.failure = failure
        state.finishedAtMillis = now()
        state.stalled = false
        run.hadFailures = true
        val carryOn = state.step.continueOnError || !policy.stopOnFirstFailure
        if (carryOn) advance(index + 1) else finish(RunbookPhase.FAILED)
    }

    /** The run has reached its end, one way or another. */
    private fun finish(phase: RunbookPhase) {
        run?.phase = phase
        this.phase = phase
        watchJob?.cancel()
        watchJob = null
        report()
    }

    /** Ends the run where it stands, leaving the step it was on marked as stopped. */
    private fun stopRun(run: RunbookSessionRun) {
        run.steps.getOrNull(run.currentIndex)?.let {
            if (it.status == RunbookStepStatus.RUNNING || it.status == RunbookStepStatus.AWAITING_CONFIRM) {
                it.status = RunbookStepStatus.STOPPED
                it.finishedAtMillis = now()
            }
            it.stalled = false // the run is over; nothing is waiting on this step any more
        }
        run.phase = RunbookPhase.STOPPED
    }

    /**
     * Builds the record of a finished run, once, and parks it for [flushReport]. Called from both
     * endings a run has: the last step reporting in and the user stopping it.
     *
     * Parked rather than handed over on the spot because one of those callers is inside the
     * generation lock: [onFinished] writes the vault (encrypt + atomic file write), and doing that
     * under the lock would block a Stop coming from the UI thread for the length of the write.
     */
    private fun report() {
        val phase = phase ?: return
        if (reported || phase == RunbookPhase.RUNNING || phase == RunbookPhase.AWAITING_CONFIRM) return
        val runbook = runbook ?: return
        val run = run ?: return
        reported = true
        pendingReport = run.runRecord(
            id = runId,
            runbookId = runbook.id,
            startedAt = startedAt,
            durationMillis = now() - startedAt,
            phase = phase,
        )
    }

    /** Hands the parked record over, outside the lock. Does nothing when there is none. */
    private fun flushReport() {
        val record = pendingReport ?: return
        pendingReport = null
        onFinished(record)
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
