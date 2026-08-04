package app.skerry.ui.runbook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.runbook.ResolvedRunbookStep
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookParallelism
import app.skerry.shared.runbook.RunbookHostOutcome
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A run waiting on its start dialog: the runbook, its steps already parsed with this run's draw of
 * machine variables ([script]), and the hosts it will run on. [recording] — at least one target
 * terminal is recording a cast, so the dialog warns that resolved lines (secrets included) will be
 * captured.
 */
@Stable
class RunbookStartRequest internal constructor(
    val runbook: Runbook,
    val script: RunbookScript,
    val targets: List<RunbookTarget>,
    val recording: Boolean,
)

/**
 * Drives one runbook across one or more terminal sessions: sends a step, waits for its exit code,
 * pauses where the runbook asks for a confirmation, and stops on a failure the policy doesn't
 * tolerate.
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

    /** Hosts of the current run, in the order they were picked; empty when nothing is running. */
    var hosts: List<RunbookHostRun> by mutableStateOf(emptyList())
        private set

    /** Where the run as a whole stands; `null` means no run at all. */
    var phase: RunbookPhase? by mutableStateOf(null)
        private set

    /**
     * Run being set up: the start dialog is showing it and collecting the values its `${{…}}`
     * placeholders need. `null` when no dialog is open.
     */
    var pending: RunbookStartRequest? by mutableStateOf(null)
        private set

    /** Whether a run is in flight (sending or waiting for a confirmation). */
    val active: Boolean get() = phase == RunbookPhase.RUNNING || phase == RunbookPhase.AWAITING_CONFIRM

    /** Whether any step on any host failed, including ones the runbook tolerates. */
    val hadFailures: Boolean get() = hosts.any { it.hadFailures }

    /** Steps finished across every host — what the run's own "12 of 14" counts. */
    val finishedCount: Int get() = hosts.sumOf { it.finishedCount }

    /** Steps the run has to get through in total. */
    val totalCount: Int get() = hosts.sumOf { it.steps.size }

    /** This run's part on [sessionId]'s tab, or `null` if that session isn't in the run. */
    fun hostFor(sessionId: String): RunbookHostRun? = hosts.firstOrNull { it.sessionId == sessionId }

    private var script: RunbookScript? = null
    private var contextValue: (SnippetSegment.Variable) -> String = { "" }
    private var policy: RunbookPolicy = RunbookPolicy()
    private var runId: String = ""
    private var startedAt: Long = 0L

    /** Whether the run in hand has already been handed to [onFinished] — it is reported once. */
    private var reported: Boolean = false

    /** Watcher per host, keyed by session id — one step per host is ever in flight. */
    private val jobs = mutableMapOf<String, Job>()

    // Bumped by every start/stop/close. A watcher captures it and drops its result if it no longer
    // matches — otherwise a poll that completed just as the user hit Stop would resurrect the run
    // and type the NEXT step into a live terminal. The watcher runs on a multi-threaded scope while
    // Stop comes from the UI thread, so the stamp is read and written under a lock and the whole
    // check-then-act is inside it (same guard as PingController's post-stop measurement).
    private val lock = Any()
    private var generation: Int = 0

    /**
     * Prepares a run of [runbook] on [targets] and parks it for the confirmation dialog. The script
     * is built (and its machine variables drawn) here rather than at [confirmStart], so the lines
     * the dialog previews are the ones that will be sent. Returns false — changing nothing — if a
     * run is already in flight or being set up, the runbook has no steps, or there is nowhere to run.
     */
    fun requestStart(runbook: Runbook, targets: List<RunbookTarget>, recording: Boolean = false): Boolean {
        if (active || pending != null) return false
        if (runbook.steps.isEmpty() || targets.isEmpty()) return false
        pending = RunbookStartRequest(
            runbook = runbook,
            script = RunbookScript.of(runbook, environment()),
            targets = targets,
            recording = recording,
        )
        return true
    }

    /** Single-host [requestStart] — what the terminal palette and the mobile sheet reach for. */
    fun requestStart(runbook: Runbook, target: RunbookTarget, recording: Boolean = false): Boolean =
        requestStart(runbook, listOf(target), recording)

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

    /**
     * The start dialog was confirmed on a different set of hosts than the run was requested with —
     * the dialog is where the user picks them, and a catalog host only becomes a target once its
     * session is up. [targets] replaces the requested one; an empty list starts nothing.
     */
    fun confirmStart(targets: List<RunbookTarget>, contextValue: (SnippetSegment.Variable) -> String): Boolean {
        val request = pending ?: return false
        if (targets.isEmpty()) return false
        pending = null
        return start(
            RunbookStartRequest(request.runbook, request.script, targets, request.recording),
            contextValue,
        )
    }

    /** Starts a prepared [request]. Returns false (changing nothing) if a run is already in flight. */
    fun start(request: RunbookStartRequest, contextValue: (SnippetSegment.Variable) -> String): Boolean {
        if (active) return false
        synchronized(lock) {
            generation++
            cancelJobs()
        }
        this.runbook = request.runbook
        this.policy = request.runbook.policy
        this.contextValue = contextValue
        this.script = request.script
        this.runId = newId()
        this.startedAt = now()
        this.reported = false
        this.hosts = request.targets.map { RunbookHostRun(it, request.runbook.steps) }
        phase = RunbookPhase.RUNNING
        when (policy.parallelism) {
            RunbookParallelism.ALL_HOSTS_AT_ONCE -> hosts.forEach { advance(it, 0) }
            RunbookParallelism.ONE_HOST_AT_A_TIME -> hosts.firstOrNull()?.let { advance(it, 0) }
        }
        refreshPhase()
        return true
    }

    /**
     * The user approved the step the run is paused on. With more than one host it covers every host
     * waiting on its own copy of that step: the pause belongs to the step, not to a machine.
     */
    fun confirmStep() {
        hosts.filter { it.phase == RunbookPhase.AWAITING_CONFIRM }.forEach { dispatchStep(it, it.currentIndex) }
        refreshPhase()
    }

    /** The user skipped the step the run is paused on; every host waiting on it moves to the next. */
    fun skipStep() {
        hosts.filter { it.phase == RunbookPhase.AWAITING_CONFIRM }.forEach { host ->
            val index = host.currentIndex
            host.steps.getOrNull(index)?.status = RunbookStepStatus.SKIPPED
            advance(host, index + 1)
        }
        refreshPhase()
    }

    /**
     * Ends the run where it stands: the step in flight on each host is left as
     * [RunbookStepStatus.STOPPED] and nothing further is sent. What the shells are already running
     * keeps running — the runner types into terminals, it doesn't own the remote processes.
     */
    fun stop() {
        if (!active) return
        // Every host's state is settled inside the same critical section as the generation bump:
        // markStalled re-reads the generation under this lock, so a poll racing Stop is refused
        // rather than allowed to re-flag a step this call has just finished with.
        synchronized(lock) {
            generation++
            cancelJobs()
            hosts.forEach { host -> if (host.phase != RunbookPhase.DONE) stopHost(host) }
        }
        phase = RunbookPhase.STOPPED
        report()
    }

    /** Dismisses the run entirely (stopping it first if needed) and forgets its resolved values. */
    fun close() {
        stop()
        synchronized(lock) {
            generation++
            cancelJobs()
        }
        pending = null
        runbook = null
        // Drops the captured step output with it: a command's output can carry a secret.
        hosts = emptyList()
        phase = null
        script = null
        // Drop the closure holding this run's clipboard/vault/parameter values.
        contextValue = { "" }
        runId = ""
    }

    /** Moves [host] to step [index], pausing there if it asks to be confirmed; past the end it is done. */
    private fun advance(host: RunbookHostRun, index: Int) {
        val state = host.steps.getOrNull(index)
        if (state == null) {
            host.currentIndex = host.steps.lastIndex
            finishHost(host, RunbookPhase.DONE)
            return
        }
        host.currentIndex = index
        if (state.step.confirm) {
            state.status = RunbookStepStatus.AWAITING_CONFIRM
            host.phase = RunbookPhase.AWAITING_CONFIRM
            refreshPhase()
        } else {
            dispatchStep(host, index)
        }
    }

    /** Starts step [index] on [host] the way its kind runs: typed into the shell, or moved over SFTP. */
    private fun dispatchStep(host: RunbookHostRun, index: Int) {
        val state = host.steps.getOrNull(index) ?: return
        val resolved = script?.resolve(index, contextValue) ?: return
        state.status = RunbookStepStatus.RUNNING
        state.startedAtMillis = now()
        host.phase = RunbookPhase.RUNNING
        host.currentIndex = index
        phase = RunbookPhase.RUNNING
        when (resolved) {
            is ResolvedRunbookStep.Command -> {
                val token = RunbookMarker.token(runId, index)
                host.target.send(RunbookMarker.probeLine(resolved.line, token) + "\n")
                watch(host, index, token)
            }
            is ResolvedRunbookStep.Transfer -> transfer(host, index, resolved)
        }
    }

    /**
     * Waits for step [index]'s marker to show up in [host]'s terminal. Polling rather than reacting
     * to output: the run must survive the screen leaving composition, and the marker sits in the
     * buffer once printed, so a poll can't miss it the way a dropped event would.
     *
     * There is deliberately no per-step timeout — a migration or a package build legitimately takes
     * an hour, and killing the procedure on a guess would be worse than waiting. A step that can
     * never report (a shell without `$?`, a command still waiting on stdin) is ended by the user
     * with [stop] — so the run says when a step looks like that one ([RunbookStepState.stalled])
     * instead of waiting silently forever: no output for [RunbookPolicy.watchdogMinutes] and no
     * marker. Only the terminal's size and hash are kept between polls, never its text; the step's
     * own output is read once, when its marker finally arrives.
     */
    private fun watch(host: RunbookHostRun, index: Int, token: String) {
        val generationAtStart = synchronized(lock) { generation }
        val target = host.target
        val job = scope.launch {
            var lastPrint: Pair<Int, Int>? = null
            var quietMillis = 0L
            while (true) {
                delay(pollIntervalMillis)
                if (stale(generationAtStart)) return@launch
                if (!target.isLive()) {
                    loseHost(host, generationAtStart)
                    return@launch
                }
                val text = target.readOutput()
                val print = text.length to text.hashCode()
                if (print == lastPrint) quietMillis += pollIntervalMillis else quietMillis = 0
                lastPrint = print
                val code = RunbookMarker.exitCodeIn(text, token)
                if (code == null) {
                    val watchdog = policy.watchdogMinutes
                    markStalled(host, index, generationAtStart, watchdog > 0 && quietMillis >= watchdog * MILLIS_PER_MINUTE)
                    continue
                }
                val output = runbookStepOutput(text, token)
                // Check-then-act under the lock: without it a Stop landing between the check and
                // finishStep would still let the run advance and send the next command.
                synchronized(lock) {
                    if (generationAtStart != generation) return@launch
                    host.steps.getOrNull(index)?.output = output
                    finishStep(host, index, code)
                }
                return@launch
            }
        }
        publishJob(host, job, generationAtStart)
    }

    /**
     * Moves a transfer step's file over [host]'s own SFTP channel. Unlike a command, there is no
     * shell and no exit code here: the SFTP call either returns or throws, and the throw is what the
     * step reports ([RunbookStepFailure.Transfer]).
     *
     * Cancellation is the same story as [watch]: Stop bumps the generation and cancels the job, and
     * the result is only applied while the stamp still matches — a transfer that completed just as
     * the user hit Stop must not advance the run into the next command.
     */
    private fun transfer(host: RunbookHostRun, index: Int, step: ResolvedRunbookStep.Transfer) {
        val generationAtStart = synchronized(lock) { generation }
        val target = host.target
        val job = scope.launch {
            val outcome = runCatching {
                val open = target.openSftp ?: throw NoSftpChannelException()
                val client = open()
                val progress = SftpProgress { done, total -> publishProgress(host, index, generationAtStart, done, total) }
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
                if (error == null) finishStep(host, index, exitCode = 0) else failStep(host, index, failureOf(error))
            }
        }
        publishJob(host, job, generationAtStart)
    }

    /**
     * Publishes [job] as [host]'s watcher, unless a Stop landed while it was being launched — that
     * already bumped the stamp, and publishing then would leave the job uncancelled.
     */
    private fun publishJob(host: RunbookHostRun, job: Job, generationAtStart: Int) = synchronized(lock) {
        if (generationAtStart == generation) jobs[host.sessionId] = job else job.cancel()
    }

    /** Publishes transfer progress under the generation guard the rest of the run uses. */
    private fun publishProgress(host: RunbookHostRun, index: Int, generationAtStart: Int, done: Long, total: Long) =
        synchronized(lock) {
            if (generationAtStart != generation) return@synchronized
            val state = host.steps.getOrNull(index) ?: return@synchronized
            state.transferredBytes = done
            state.totalBytes = total
        }

    private fun stale(generationAtStart: Int): Boolean = synchronized(lock) { generationAtStart != generation }

    /**
     * Marks (or unmarks) step [index] on [host] as possibly stuck, under the same generation guard
     * as [finishStep]: a poll landing just after Stop must not put a warning on a run that is over.
     */
    private fun markStalled(host: RunbookHostRun, index: Int, generationAtStart: Int, stalled: Boolean) =
        synchronized(lock) {
            if (generationAtStart != generation) return@synchronized
            val state = host.steps.getOrNull(index) ?: return@synchronized
            if (state.stalled != stalled) state.stalled = stalled
        }

    /** The session behind [host] went away mid-step: that host is done for, the rest may not be. */
    private fun loseHost(host: RunbookHostRun, generationAtStart: Int) = synchronized(lock) {
        if (generationAtStart != generation) return@synchronized
        stopHost(host)
        if (policy.stopOnFirstFailure) stopOthers(host)
        refreshPhase()
    }

    private fun finishStep(host: RunbookHostRun, index: Int, exitCode: Int) {
        val state = host.steps.getOrNull(index) ?: return
        state.exitCode = exitCode
        state.finishedAtMillis = now()
        // It reported after all: whatever the warning said, the step was only slow.
        state.stalled = false
        if (exitCode == 0) {
            state.status = RunbookStepStatus.SUCCEEDED
            advance(host, index + 1)
            refreshPhase()
            return
        }
        failStep(host, index, failure = null)
    }

    /**
     * Ends step [index] on [host] as failed and decides where things go from there: on to the next
     * step when the step tolerates its own failure ([RunbookStep.continueOnError]) or the runbook
     * doesn't stop on failures at all ([RunbookPolicy.stopOnFirstFailure]); otherwise this host
     * ends here, and with a stop-on-failure policy so does every other host — the point of a rolling
     * deploy is not to carry a broken release onto the rest of the fleet.
     */
    private fun failStep(host: RunbookHostRun, index: Int, failure: RunbookStepFailure?) {
        val state = host.steps.getOrNull(index) ?: return
        state.status = RunbookStepStatus.FAILED
        state.failure = failure
        state.finishedAtMillis = now()
        state.stalled = false
        host.hadFailures = true
        val carryOn = state.step.continueOnError || !policy.stopOnFirstFailure
        if (carryOn) {
            advance(host, index + 1)
        } else {
            finishHost(host, RunbookPhase.FAILED)
            stopOthers(host)
        }
        refreshPhase()
    }

    /** [host] has reached the end of its part of the run, one way or another. */
    private fun finishHost(host: RunbookHostRun, phase: RunbookPhase) {
        host.phase = phase
        jobs.remove(host.sessionId)?.cancel()
        // Rolling runs hand over here: the next host only starts once this one is home.
        if (phase == RunbookPhase.DONE && policy.parallelism == RunbookParallelism.ONE_HOST_AT_A_TIME) {
            hosts.firstOrNull { it.phase == RunbookPhase.WAITING }?.let { advance(it, 0) }
        }
        refreshPhase()
    }

    /** Ends [host] where it stands, leaving the step it was on marked as stopped. */
    private fun stopHost(host: RunbookHostRun) {
        jobs.remove(host.sessionId)?.cancel()
        host.steps.getOrNull(host.currentIndex)?.let {
            if (it.status == RunbookStepStatus.RUNNING || it.status == RunbookStepStatus.AWAITING_CONFIRM) {
                it.status = RunbookStepStatus.STOPPED
                it.finishedAtMillis = now()
            }
            it.stalled = false // the run is over for this host; nothing is waiting on the step
        }
        host.phase = RunbookPhase.STOPPED
    }

    /** Stops every host except [except] — a failure under a stop-on-failure policy takes them all. */
    private fun stopOthers(except: RunbookHostRun) {
        hosts.forEach { other ->
            if (other !== except && other.phase != RunbookPhase.DONE && other.phase != RunbookPhase.FAILED) {
                stopHost(other)
            }
        }
    }

    private fun cancelJobs() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * The run's phase, read off its hosts: it is running while any host still has work, waiting on
     * the user the moment one of them pauses for a confirmation, and only finished once every host
     * is — failed if any host failed, stopped if the rest were stopped.
     */
    private fun refreshPhase() {
        if (hosts.isEmpty()) return
        val phases = hosts.map { it.phase }
        phase = when {
            phases.any { it == RunbookPhase.AWAITING_CONFIRM } -> RunbookPhase.AWAITING_CONFIRM
            phases.any { it == RunbookPhase.RUNNING || it == RunbookPhase.WAITING } -> RunbookPhase.RUNNING
            phases.any { it == RunbookPhase.FAILED } -> RunbookPhase.FAILED
            phases.any { it == RunbookPhase.STOPPED } -> RunbookPhase.STOPPED
            else -> RunbookPhase.DONE
        }
        report()
    }

    /**
     * Hands a finished run to [onFinished], once. Called from both endings a run has: the last host
     * reporting in ([refreshPhase]) and the user stopping it ([stop]).
     */
    private fun report() {
        val phase = phase ?: return
        if (reported || phase == RunbookPhase.RUNNING || phase == RunbookPhase.AWAITING_CONFIRM) return
        val runbook = runbook ?: return
        reported = true
        onFinished(
            RunbookRunRecord(
                id = runId,
                runbookId = runbook.id,
                startedAt = startedAt,
                durationMillis = now() - startedAt,
                outcome = when {
                    phase == RunbookPhase.FAILED -> RunbookRunOutcome.FAILED
                    phase == RunbookPhase.STOPPED -> RunbookRunOutcome.STOPPED
                    hadFailures -> RunbookRunOutcome.DONE_WITH_FAILURES
                    else -> RunbookRunOutcome.DONE
                },
                hosts = hosts.map { host ->
                    RunbookHostOutcome(
                        label = host.label,
                        stepsDone = host.finishedCount,
                        stepsTotal = host.steps.size,
                        // 1-based, the way the run screen numbers steps.
                        failedStep = host.steps.firstOrNull { it.status == RunbookStepStatus.FAILED }?.let { it.index + 1 },
                    )
                },
            ),
        )
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
