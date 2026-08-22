package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.terminal.TerminalStepMark
import app.skerry.ui.sftp.FakeSftpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * The rig every runbook-run test stands on: a terminal faked as a byte sink plus the two things the
 * runner reads from a real one (the step report its probe emitted, parked until taken as
 * `TerminalScreenState` parks it, and a counter of host output), the runbook builders, and the
 * shared teardown. Split out of `RunbookRunnerTest` so a second suite can use it.
 */

internal const val RUN_ID = "run"

internal const val POLL = 100L

/** The runbooks under test carry a one-minute watchdog; time is virtual, so the wait is free. */
internal const val STALL_AFTER = 60_000L

/**
 * The runner's own silence floor (`SILENT_STEP_MS`, private to the production file), past which a
 * step stops counting as work in flight. Duplicated rather than exposed: the point of the constant
 * is that a runbook cannot move it, so a test that read it from the policy would prove nothing.
 */
internal const val SILENT_FLOOR = 120_000L

@OptIn(ExperimentalCoroutinesApi::class)
internal class FakeTerminal {
    val sent = mutableListOf<String>()
    var live: Boolean = true
    var polls: Int = 0

    /** Bytes the host has produced — the echo of what was typed counts, a step report does too. */
    var outputVersion: Long = 0L

    /** The report parked by the terminal, waiting for the runner to take it. */
    private var mark: TerminalStepMark? = null

    /** Every token the runner declared, in order; `null` means it stopped expecting one. */
    val expected = mutableListOf<String?>()

    /** Fragments of the last declared step that the terminal was told to hide from the echo. */
    var hiddenEcho: List<String> = emptyList()

    /** What the terminal was expecting at the moment each line was sent. */
    val declaredWhenSent = mutableListOf<String?>()

    /** SFTP side of the same session; `null` stands for a connection without one. */
    var sftp: FakeSftpClient? = null

    fun target(sessionId: String = "tab-1") = RunbookTarget(
        sessionId = sessionId,
        // The PTY echoes the typed line; what the terminal was told to expect is captured with it.
        send = { line, _ -> sent += line; declaredWhenSent += expected.lastOrNull(); outputVersion++ },
        expectStep = { token, hidden -> expected += token; hiddenEcho = hidden },
        // Consuming, and a report of another step is dropped — as TerminalScreenState does it.
        takeMark = { token ->
            polls++
            val parked = mark
            mark = null
            parked?.takeIf { it.token == token }
        },
        outputVersion = { outputVersion },
        isLive = { live },
        openSftp = sftp?.let { client -> suspend { client as SftpClient } },
    )

    /** The host wrote something to the terminal — the only thing the watchdog looks at. */
    fun printed() {
        outputVersion++
    }

    /** The shell finished the step and its closing probe emitted the mark. */
    fun complete(stepIndex: Int, exitCode: Int, output: String? = "", runId: String = RUN_ID) {
        mark = TerminalStepMark(RunbookMarker.token(runId, stepIndex), exitCode, output)
        outputVersion++
    }
}

internal fun environment() = SnippetRunEnvironment(
    moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
    newUuid = { "uuid" },
    randomChars = { n, _ -> "r".repeat(n) },
)

internal fun runbook(vararg steps: RunbookStep, policy: RunbookPolicy = RunbookPolicy(watchdogMinutes = 1)) =
    Runbook(id = "rb", label = "Deploy", steps = steps.toList(), policy = policy)

internal fun step(id: String, command: String, confirm: Boolean = false, continueOnError: Boolean = false) =
    RunbookStep.Command(id = id, title = id, command = command, confirm = confirm, continueOnError = continueOnError)

internal fun interactive(id: String, command: String, confirm: Boolean = false) =
    RunbookStep.Command(id = id, title = id, command = command, confirm = confirm, interactive = true)

internal fun transfer(
    id: String,
    localPath: String = "/tmp/release.tgz",
    remotePath: String = "/srv/incoming/release.tgz",
    direction: RunbookTransferDirection = RunbookTransferDirection.UPLOAD,
    confirm: Boolean = false,
    continueOnError: Boolean = false,
) = RunbookStep.Transfer(
    id = id,
    title = id,
    localPath = localPath,
    remotePath = remotePath,
    direction = direction,
    confirm = confirm,
    continueOnError = continueOnError,
)

/**
 * Shared setup and — the point of the helper — shared teardown: a watcher still polling on the
 * test scheduler makes a *failing* assertion hang runTest's cleanup instead of reporting it, so
 * the runner is closed and the scope cancelled even when the body throws.
 */
internal fun runnerTest(body: TestScope.(RunbookRunner, FakeTerminal) -> Unit) = runTest {
    val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
    val term = FakeTerminal()
    val runner = RunbookRunner(
        scope = scope,
        newId = { RUN_ID },
        environment = ::environment,
        pollIntervalMillis = POLL,
    )
    try {
        body(runner, term)
    } finally {
        runner.close()
        scope.cancel()
    }
}

/** The run in hand — every run here has exactly one session. */
internal val RunbookRunner.only: RunbookSessionRun get() = run!!

/** Prepare + confirm in one call: the dialog step has its own coverage in the UI layer. */
internal fun RunbookRunner.startNow(
    runbook: Runbook,
    target: RunbookTarget,
    contextValue: (SnippetSegment.Variable) -> String,
): Boolean = requestStart(runbook, target) && confirmStart(contextValue = contextValue)
