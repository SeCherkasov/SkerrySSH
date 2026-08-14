package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookRunOutcome
import app.skerry.shared.runbook.RunbookRunRecord
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.terminal.TerminalStepMark
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a finished run hands to the history. Reported once, when the run ends whichever way — the
 * log is what the run screen's "previous runs" card reads back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookRunSummaryTest {

    private val poll = 100L

    private class FakeHost(val id: String) {
        var live = true
        private var mark: TerminalStepMark? = null

        fun target() = RunbookTarget(
            sessionId = id,
            label = id,
            send = {},
            expectStep = { _, _ -> },
            takeMark = { token ->
                val parked = mark
                mark = null
                parked?.takeIf { it.token == token }
            },
            outputVersion = { 0L },
            isLive = { live },
        )

        fun complete(stepIndex: Int, exitCode: Int) {
            mark = TerminalStepMark(RunbookMarker.token(RUN_ID, stepIndex), exitCode, "")
        }
    }

    private fun environment() = SnippetRunEnvironment(
        moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
        newUuid = { "uuid" },
        randomChars = { n, _ -> "r".repeat(n) },
    )

    private fun runbook(steps: Int, policy: RunbookPolicy = RunbookPolicy(), continueOnError: Boolean = false) = Runbook(
        id = "rb",
        label = "Deploy",
        steps = (0 until steps).map {
            RunbookStep.Command(id = "s$it", command = "cmd$it", confirm = false, continueOnError = continueOnError)
        },
        policy = policy,
    )

    private fun runnerTest(body: TestScope.(RunbookRunner, FakeHost, MutableList<RunbookRunRecord>) -> Unit) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val logged = mutableListOf<RunbookRunRecord>()
        val runner = RunbookRunner(
            scope = scope,
            newId = { RUN_ID },
            environment = ::environment,
            pollIntervalMillis = poll,
            now = { testScheduler.currentTime },
            onFinished = { logged += it },
        )
        try {
            body(runner, FakeHost("web-01"), logged)
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    private fun RunbookRunner.startNow(runbook: Runbook, host: FakeHost): Boolean =
        requestStart(runbook, host.target()) && confirmStart { "" }

    @Test
    fun a_clean_run_is_logged_as_done() = runnerTest { r, host, logged ->
        r.startNow(runbook(2), host)
        host.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(5_000)
        host.complete(1, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        val record = logged.single()
        assertEquals("rb", record.runbookId)
        assertEquals(RunbookRunOutcome.DONE, record.outcome)
        assertEquals(2 to 2, record.host.stepsDone to record.host.stepsTotal)
        assertEquals("web-01", record.host.label)
        assertTrue(record.durationMillis >= 5_000, "took ${record.durationMillis} ms")
    }

    @Test
    fun a_failed_run_remembers_the_step_it_died_on() = runnerTest { r, host, logged ->
        r.startNow(runbook(3), host)
        host.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        host.complete(1, 7)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        val record = logged.single()
        assertEquals(RunbookRunOutcome.FAILED, record.outcome)
        // 1-based, as the run screen numbers steps.
        assertEquals(2, record.host.failedStep)
    }

    @Test
    fun a_tolerated_failure_still_colours_the_log() = runnerTest { r, host, logged ->
        r.startNow(runbook(1, continueOnError = true), host)
        host.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookRunOutcome.DONE_WITH_FAILURES, logged.single().outcome)
    }

    @Test
    fun a_stopped_run_is_logged_as_stopped_exactly_once() = runnerTest { r, host, logged ->
        r.startNow(runbook(2), host)
        r.stop()
        r.stop()
        testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()

        assertEquals(1, logged.size)
        assertEquals(RunbookRunOutcome.STOPPED, logged.single().outcome)
    }

    @Test
    fun closing_a_finished_run_does_not_log_it_again() = runnerTest { r, host, logged ->
        r.startNow(runbook(1), host)
        host.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        r.close()

        assertEquals(1, logged.size)
    }

    @Test
    fun a_run_that_never_started_logs_nothing() = runnerTest { r, _, logged ->
        r.close()

        assertTrue(logged.isEmpty())
    }

    private companion object {
        const val RUN_ID = "run"
    }
}
