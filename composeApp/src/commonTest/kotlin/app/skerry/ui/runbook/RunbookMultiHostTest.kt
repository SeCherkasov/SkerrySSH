package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookParallelism
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A run across more than one host: how the procedure is spread ([RunbookParallelism]), what a
 * failure on one host does to the others, and what the run as a whole reports while its hosts are
 * in different places.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookMultiHostTest {

    private val poll = 100L

    private class FakeHost(val id: String) {
        val sent = mutableListOf<String>()
        var buffer: String = ""
        var live: Boolean = true

        fun target() = RunbookTarget(
            sessionId = id,
            label = id,
            send = { line -> sent += line; buffer += line },
            readOutput = { buffer },
            isLive = { live },
        )

        /** The shell on this host finished step [stepIndex] and printed the marker. */
        fun complete(stepIndex: Int, exitCode: Int) {
            buffer += "\n" + RunbookMarker.token(RUN_ID, stepIndex) + ":" + exitCode + "\n"
        }
    }

    private fun environment() = SnippetRunEnvironment(
        moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
        newUuid = { "uuid" },
        randomChars = { n -> "r".repeat(n) },
    )

    private fun runbook(steps: Int, policy: RunbookPolicy) = Runbook(
        id = "rb",
        label = "Deploy",
        steps = (0 until steps).map { RunbookStep.Command(id = "s$it", title = "step $it", command = "cmd$it", confirm = false) },
        policy = policy,
    )

    private fun runnerTest(body: TestScope.(RunbookRunner, FakeHost, FakeHost) -> Unit) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val runner = RunbookRunner(
            scope = scope,
            newId = { RUN_ID },
            environment = ::environment,
            pollIntervalMillis = poll,
            now = { testScheduler.currentTime },
        )
        try {
            body(runner, FakeHost("web-01"), FakeHost("web-02"))
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    private fun RunbookRunner.startNow(runbook: Runbook, vararg hosts: FakeHost): Boolean =
        requestStart(runbook, hosts.map { it.target() }) && confirmStart { "" }

    @Test
    fun one_host_at_a_time_leaves_the_second_host_untouched_until_the_first_is_done() = runnerTest { r, a, b ->
        r.startNow(runbook(2, RunbookPolicy(parallelism = RunbookParallelism.ONE_HOST_AT_A_TIME)), a, b)

        assertEquals(1, a.sent.size)
        assertTrue(b.sent.isEmpty(), "a rolling run must not touch the next host yet")

        a.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        assertEquals(2, a.sent.size)
        assertTrue(b.sent.isEmpty())

        a.complete(1, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.DONE, r.hostFor("web-01")?.phase)
        assertEquals(1, b.sent.size, "the second host starts once the first has finished")
    }

    @Test
    fun all_hosts_at_once_sends_the_first_step_everywhere() = runnerTest { r, a, b ->
        r.startNow(runbook(2, RunbookPolicy(parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)), a, b)

        assertEquals(1, a.sent.size)
        assertEquals(1, b.sent.size)
    }

    @Test
    fun a_host_that_is_ahead_does_not_wait_for_the_slower_one() = runnerTest { r, a, b ->
        r.startNow(runbook(2, RunbookPolicy(parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)), a, b)
        a.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(2, a.sent.size, "a fast host carries on")
        assertEquals(1, b.sent.size, "the slow one is still on its first step")
    }

    @Test
    fun a_failure_on_one_host_ends_the_whole_run_when_the_policy_says_stop() = runnerTest { r, a, b ->
        r.startNow(
            runbook(2, RunbookPolicy(stopOnFirstFailure = true, parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)),
            a, b,
        )
        a.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.FAILED, r.phase)
        assertEquals(RunbookStepStatus.FAILED, r.hostFor("web-01")!!.steps[0].status)
        // The healthy host is stopped where it stands rather than carried into the next step.
        assertEquals(RunbookPhase.STOPPED, r.hostFor("web-02")?.phase)
        b.complete(0, 0)
        testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()
        assertEquals(1, b.sent.size)
    }

    @Test
    fun a_failure_on_one_host_leaves_the_others_running_when_the_policy_does_not_stop() = runnerTest { r, a, b ->
        r.startNow(
            runbook(2, RunbookPolicy(stopOnFirstFailure = false, parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)),
            a, b,
        )
        a.complete(0, 1)
        b.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(2, a.sent.size, "the failed host carries on under this policy")
        assertEquals(2, b.sent.size)
        assertTrue(r.hadFailures)
        assertEquals(RunbookPhase.RUNNING, r.phase)
    }

    @Test
    fun the_run_is_done_only_once_every_host_is() = runnerTest { r, a, b ->
        r.startNow(runbook(1, RunbookPolicy(parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)), a, b)
        a.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.RUNNING, r.phase, "one host home is not the run finished")

        b.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.DONE, r.phase)
        assertFalse(r.active)
    }

    @Test
    fun a_confirmation_covers_every_host_waiting_on_that_step() = runnerTest { r, a, b ->
        val runbook = Runbook(
            id = "rb",
            label = "Deploy",
            steps = listOf(RunbookStep.Command(id = "s0", command = "reboot", confirm = true)),
            policy = RunbookPolicy(parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE),
        )
        r.startNow(runbook, a, b)

        assertEquals(RunbookPhase.AWAITING_CONFIRM, r.phase)
        assertTrue(a.sent.isEmpty() && b.sent.isEmpty())

        r.confirmStep()

        assertEquals(1, a.sent.size)
        assertEquals(1, b.sent.size)
    }

    @Test
    fun losing_one_session_stops_that_host_only() = runnerTest { r, a, b ->
        r.startNow(
            runbook(2, RunbookPolicy(stopOnFirstFailure = false, parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)),
            a, b,
        )
        a.live = false
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.STOPPED, r.hostFor("web-01")?.phase)
        assertEquals(RunbookPhase.RUNNING, r.hostFor("web-02")?.phase)

        b.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        assertEquals(2, b.sent.size)
    }

    @Test
    fun stopping_the_run_stops_every_host() = runnerTest { r, a, b ->
        r.startNow(runbook(2, RunbookPolicy(parallelism = RunbookParallelism.ALL_HOSTS_AT_ONCE)), a, b)
        r.stop()

        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(RunbookPhase.STOPPED, r.hostFor("web-01")?.phase)
        assertEquals(RunbookPhase.STOPPED, r.hostFor("web-02")?.phase)

        a.complete(0, 0)
        b.complete(0, 0)
        testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()
        assertEquals(1, a.sent.size)
        assertEquals(1, b.sent.size)
    }

    @Test
    fun a_step_records_how_long_it_took_and_what_it_printed() = runnerTest { r, a, _ ->
        r.startNow(runbook(1, RunbookPolicy()), a)
        testScheduler.advanceTimeBy(2_400)
        a.buffer += "\nhealthz 200 OK"
        a.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        val step = r.hostFor("web-01")!!.steps[0]
        assertEquals(RunbookStepStatus.SUCCEEDED, step.status)
        assertTrue((step.durationMillis ?: 0) >= 2_400, "took ${step.durationMillis} ms")
        assertEquals("healthz 200 OK", step.output)
    }

    @Test
    fun a_run_with_no_hosts_is_refused() = runnerTest { r, _, _ ->
        assertFalse(r.requestStart(runbook(1, RunbookPolicy()), emptyList()))
    }

    private companion object {
        const val RUN_ID = "run"
    }
}
