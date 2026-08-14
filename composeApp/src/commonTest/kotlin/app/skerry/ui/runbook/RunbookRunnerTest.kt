package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookMarker
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.shared.sftp.SftpClient
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.ui.sftp.FakeSftpClient
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.snippet.SnippetSegment
import app.skerry.shared.terminal.TerminalStepMark
import app.skerry.shared.terminal.UNREADABLE_STATUS
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Runbook run state machine. The terminal is faked as a byte sink plus the two things the runner
 * reads from a real one: the step report its probe emitted (parked until taken, as
 * `TerminalScreenState` parks it) and a counter of host output. Time is virtual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookRunnerTest {

    private val poll = 100L

    /** The runbooks under test carry a one-minute watchdog; time is virtual, so the wait is free. */
    private val stallAfter = 60_000L

    private class FakeTerminal {
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
            send = { line -> sent += line; declaredWhenSent += expected.lastOrNull(); outputVersion++ },
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

    private fun environment() = SnippetRunEnvironment(
        moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
        newUuid = { "uuid" },
        randomChars = { n, _ -> "r".repeat(n) },
    )

    private fun runbook(vararg steps: RunbookStep, policy: RunbookPolicy = RunbookPolicy(watchdogMinutes = 1)) =
        Runbook(id = "rb", label = "Deploy", steps = steps.toList(), policy = policy)

    private fun step(id: String, command: String, confirm: Boolean = false, continueOnError: Boolean = false) =
        RunbookStep.Command(id = id, title = id, command = command, confirm = confirm, continueOnError = continueOnError)

    private fun interactive(id: String, command: String, confirm: Boolean = false) =
        RunbookStep.Command(id = id, title = id, command = command, confirm = confirm, interactive = true)

    private fun transfer(
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
    private fun runnerTest(body: TestScope.(RunbookRunner, FakeTerminal) -> Unit) = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val term = FakeTerminal()
        val runner = RunbookRunner(
            scope = scope,
            newId = { RUN_ID },
            environment = ::environment,
            pollIntervalMillis = poll,
        )
        try {
            body(runner, term)
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    /** The run in hand — every run here has exactly one session. */
    private val RunbookRunner.only: RunbookSessionRun get() = run!!

    /** Prepare + confirm in one call: the dialog step has its own coverage in the UI layer. */
    private fun RunbookRunner.startNow(
        runbook: Runbook,
        target: RunbookTarget,
        contextValue: (SnippetSegment.Variable) -> String,
    ): Boolean = requestStart(runbook, target) && confirmStart(contextValue)

    @Test
    fun a_step_that_goes_quiet_without_reporting_is_flagged() = runnerTest { r, term ->
        // An unterminated here-doc (or quote, or a shell that replaced itself with `exec`) leaves
        // nothing that will ever print the marker. The run cannot know that, but it can see that the
        // step has printed nothing for a long time and has not reported a status.
        r.startNow(runbook(step("s1", "cat <<EOF")), term.target()) { "" }
        assertFalse(r.only.steps[0].stalled)

        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()

        assertTrue(r.only.steps[0].stalled)
        // Not killed: `sleep 3600` and a silent migration look exactly the same from here, so the
        // decision stays the user's.
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
        assertEquals(RunbookPhase.RUNNING, r.phase)
    }

    @Test
    fun a_step_that_keeps_printing_is_never_flagged() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "apt upgrade")), term.target()) { "" }

        repeat(6) {
            testScheduler.advanceTimeBy(stallAfter / 2); testScheduler.runCurrent()
            term.printed()
        }
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertFalse(r.only.steps[0].stalled, "a long step that talks is not a stuck one")
    }

    @Test
    fun output_after_a_quiet_spell_clears_the_flag() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "./migrate.sh")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.only.steps[0].stalled)

        term.printed()
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertFalse(r.only.steps[0].stalled)
    }

    @Test
    fun a_step_that_reports_after_a_quiet_spell_does_not_stay_flagged() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "sleep 300")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.only.steps[0].stalled)

        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
        assertFalse(r.only.steps[0].stalled)
    }

    @Test
    fun stopping_a_flagged_step_clears_the_flag_and_a_late_poll_cannot_bring_it_back() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "cat <<EOF")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.only.steps[0].stalled)

        r.stop()
        // A poll that passed its staleness check just before Stop must not re-flag a run that is
        // over — the same race the generation guard exists for, now on this flag too.
        testScheduler.advanceTimeBy(stallAfter * 2); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)
        assertFalse(r.only.steps[0].stalled)
    }

    @Test
    fun a_new_run_after_a_stalled_one_starts_unflagged() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "cat <<EOF")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.only.steps[0].stalled)
        r.stop()

        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }

        assertFalse(r.only.steps[0].stalled)
    }

    @Test
    fun sends_the_command_with_the_exit_code_probe() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "systemctl restart nginx")), term.target()) { "" }

        val expected = RunbookMarker.probeLine("systemctl restart nginx", RunbookMarker.token(RUN_ID, 0)) + "\n"
        assertEquals(listOf(expected), term.sent)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
    }

    @Test
    fun the_echoed_line_alone_never_finishes_a_step() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll * 20); testScheduler.runCurrent()

        // Only the first step was ever sent: the echo of its own line is output, not a report.
        assertEquals(1, term.sent.size)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
    }

    @Test
    fun the_step_is_declared_to_the_terminal_before_it_is_typed() = runnerTest { r, term ->
        // The other order loses steps: the echo starts coming back the instant the line is sent, and
        // a terminal that hasn't been told what to expect neither reports it nor hides its probes.
        val token = RunbookMarker.token(RUN_ID, 0)
        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }

        assertEquals(listOf<String?>(token), term.declaredWhenSent)
        assertEquals(RunbookMarker.echoFragments("uptime", token), term.hiddenEcho)
    }

    @Test
    fun stopping_tells_the_terminal_to_forget_the_step() = runnerTest { r, term ->
        // Otherwise the abandoned step's output — a resolved secret possibly among it — sits in the
        // terminal until something else overwrites it, and a late report could still be picked up.
        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
        r.stop()

        assertEquals(listOf(RunbookMarker.token(RUN_ID, 0), null), term.expected.toList())
    }

    @Test
    fun the_step_keeps_what_the_terminal_cut_out_for_it() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "curl -fsS localhost/healthz")), term.target()) { "" }
        term.complete(0, 0, output = "healthz 200 OK")
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals("healthz 200 OK", r.only.steps[0].output)
        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
    }

    @Test
    fun a_step_whose_capture_was_lost_is_not_reported_as_silent() = runnerTest { r, term ->
        // The terminal was cleared or resized while the step ran, so the rows it printed are gone.
        // "Nothing printed" would be a claim about the command; this is a fact about the terminal.
        r.startNow(runbook(step("s1", "make build")), term.target()) { "" }
        term.complete(0, 0, output = null)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertNull(r.only.steps[0].output)
        assertTrue(r.only.steps[0].outputLost)
        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
    }

    @Test
    fun a_mark_whose_status_could_not_be_read_fails_the_step() = runnerTest { r, term ->
        // The host sent something that is not a status. The step ends — a dropped mark would leave
        // the run waiting for a probe that has already run — and it ends as a failure, never as a
        // success the operator would trust.
        r.startNow(runbook(step("s1", "deploy")), term.target()) { "" }
        term.complete(0, UNREADABLE_STATUS)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        assertEquals(UNREADABLE_STATUS, r.only.steps[0].exitCode)
    }

    @Test
    fun a_step_that_printed_nothing_is_not_flagged_as_lost() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "true")), term.target()) { "" }
        term.complete(0, 0, output = "")
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertFalse(r.only.steps[0].outputLost)
    }

    @Test
    fun a_report_of_another_step_does_not_finish_this_one() = runnerTest { r, term ->
        // A step the user stopped can still report much later — into the run that is going on now.
        // Its token is another step's, and a status is not something to attribute by proximity.
        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
        term.complete(stepIndex = 4, exitCode = 0, runId = "older-run")
        testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
        assertNull(r.only.steps[0].exitCode)
    }

    @Test
    fun walks_the_steps_as_each_exit_code_arrives() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
        assertEquals(0, r.only.steps[0].exitCode)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[1].status)

        term.complete(1, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[1].status)
        assertEquals(RunbookPhase.DONE, r.phase)
        assertFalse(r.active)
        assertFalse(r.hadFailures)
    }

    @Test
    fun a_confirm_step_waits_until_the_user_says_go() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "reboot", confirm = true)), term.target()) { "" }
        testScheduler.advanceTimeBy(poll * 10); testScheduler.runCurrent()

        assertEquals(RunbookPhase.AWAITING_CONFIRM, r.phase)
        assertEquals(RunbookStepStatus.AWAITING_CONFIRM, r.only.steps[0].status)
        assertTrue(term.sent.isEmpty(), "nothing may reach the shell before the go-ahead")

        r.confirmStep()
        assertEquals(1, term.sent.size)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
    }

    @Test
    fun a_failing_step_stops_the_run_and_leaves_the_rest_untouched() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "migrate"), step("s2", "restart")), term.target()) { "" }
        term.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        assertEquals(1, r.only.steps[0].exitCode)
        assertEquals(RunbookStepStatus.PENDING, r.only.steps[1].status)
        assertEquals(RunbookPhase.FAILED, r.phase)
        assertEquals(1, term.sent.size, "the next command must not run after a failure")
    }

    @Test
    fun continue_on_error_records_the_failure_and_keeps_going() = runnerTest { r, term ->
        r.startNow(
            runbook(step("s1", "grep warn log", continueOnError = true), step("s2", "restart")),
            term.target(),
        ) { "" }
        term.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[1].status)
        assertEquals(RunbookPhase.RUNNING, r.phase)

        term.complete(1, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        // A tolerated failure still colours the run: it finished, but not cleanly.
        assertEquals(RunbookPhase.DONE, r.phase)
        assertTrue(r.hadFailures)
    }

    @Test
    fun skipping_a_step_moves_on_without_sending_it() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "reboot", confirm = true), step("s2", "uptime")), term.target()) { "" }
        r.skipStep()

        assertEquals(RunbookStepStatus.SKIPPED, r.only.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[1].status)
        assertEquals(listOf(RunbookMarker.probeLine("uptime", RunbookMarker.token(RUN_ID, 1)) + "\n"), term.sent)
    }

    @Test
    fun stopping_ends_the_watch_and_sends_nothing_more() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        r.stop()

        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)

        // Even if the step's marker turns up afterwards, the run is over.
        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll * 20); testScheduler.runCurrent()
        assertEquals(1, term.sent.size)
        assertEquals(RunbookPhase.STOPPED, r.phase)
    }

    @Test
    fun a_stopped_run_stops_reading_the_terminal() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll * 3); testScheduler.runCurrent()
        r.stop()
        val after = term.polls
        testScheduler.advanceTimeBy(poll * 20); testScheduler.runCurrent()

        assertEquals(after, term.polls, "a stopped run must not keep polling the terminal")
    }

    @Test
    fun a_stop_landing_during_the_poll_does_not_send_the_next_step() = runTest {
        // Single-threaded stand-in for the cross-thread race (same trick as PingControllerTest): the
        // poll is where Stop lands, so the watcher holds a finished step's exit code that is no
        // longer allowed to advance the run.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val term = FakeTerminal()
        var runner: RunbookRunner? = null
        val target = RunbookTarget(
            sessionId = "tab-1",
            send = { line -> term.sent += line },
            expectStep = { _, _ -> },
            takeMark = { token -> runner!!.stop(); TerminalStepMark(token, 0, "") },
            outputVersion = { 0L },
            isLive = { true },
        )
        val r = RunbookRunner(scope, newId = { RUN_ID }, environment = ::environment, pollIntervalMillis = poll)
        runner = r
        try {
            r.requestStart(runbook(step("s1", "uptime"), step("s2", "df -h")), target)
            r.confirmStart { "" }
            testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()

            assertEquals(RunbookPhase.STOPPED, r.phase)
            assertEquals(1, term.sent.size, "the next step must not be typed after Stop")
        } finally {
            r.close()
            scope.cancel()
        }
    }

    @Test
    fun losing_the_session_aborts_the_run() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        term.live = false
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(1, term.sent.size)
    }

    @Test
    fun a_second_run_is_refused_while_one_is_in_flight() = runnerTest { r, term ->
        assertTrue(r.startNow(runbook(step("s1", "uptime")), term.target()) { "" })
        assertFalse(r.startNow(runbook(step("s2", "df -h")), term.target()) { "" })
        assertEquals(1, term.sent.size)
    }

    @Test
    fun a_finished_run_can_be_replaced_by_a_new_one() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertTrue(r.startNow(runbook(step("s2", "df -h")), term.target()) { "" })
        assertEquals(RunbookPhase.RUNNING, r.phase)
        assertEquals(2, term.sent.size)
    }

    @Test
    fun an_empty_runbook_is_refused() = runnerTest { r, term ->
        assertFalse(r.startNow(runbook(), term.target()) { "" })
        assertNull(r.phase)
    }

    @Test
    fun variables_are_resolved_into_the_sent_line() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "deploy \${{service}}")), term.target()) { "billing" }

        assertTrue(term.sent.single().contains("; deploy billing;"), term.sent.single())
    }

    @Test
    fun closing_a_finished_run_clears_it() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }
        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        assertEquals(RunbookPhase.DONE, r.phase)

        r.close()
        assertNull(r.phase)
        assertNull(r.runbook)
        assertNull(r.run)
    }

    @Test
    fun a_watchdog_turned_off_never_flags_a_quiet_step() = runnerTest { r, term ->
        r.startNow(
            runbook(step("s1", "cat <<EOF"), policy = RunbookPolicy(watchdogMinutes = 0)),
            term.target(),
        ) { "" }

        testScheduler.advanceTimeBy(stallAfter * 10); testScheduler.runCurrent()

        assertFalse(r.only.steps[0].stalled)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
    }

    @Test
    fun a_run_that_does_not_stop_on_failure_carries_on_to_the_next_step() = runnerTest { r, term ->
        r.startNow(
            runbook(step("s1", "migrate"), step("s2", "restart"), policy = RunbookPolicy(stopOnFirstFailure = false)),
            term.target(),
        ) { "" }
        term.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[1].status)
        assertTrue(r.hadFailures)
    }

    @Test
    fun a_transfer_step_uploads_over_the_session_sftp_channel() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        sftp.uploadSize = 2048
        term.sftp = sftp

        r.startNow(runbook(transfer("s1")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals("/tmp/release.tgz" to "/srv/incoming/release.tgz", sftp.lastUpload)
        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
        assertEquals(0, r.only.steps[0].exitCode)
        assertEquals(RunbookPhase.DONE, r.phase)
        assertTrue(term.sent.isEmpty(), "a transfer must not type anything into the shell")
    }

    @Test
    fun a_transfer_step_reports_its_progress() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        sftp.uploadSize = 4096
        term.sftp = sftp

        r.startNow(runbook(transfer("s1")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(4096L, r.only.steps[0].transferredBytes)
        assertEquals(4096L, r.only.steps[0].totalBytes)
    }

    @Test
    fun a_download_step_pulls_the_file_the_other_way() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/var/log/app")
        sftp.seedFile("/var/log/app/last.log", size = 512)
        term.sftp = sftp

        r.startNow(
            runbook(
                transfer(
                    "s1",
                    localPath = "/tmp/last.log",
                    remotePath = "/var/log/app/last.log",
                    direction = RunbookTransferDirection.DOWNLOAD,
                ),
            ),
            term.target(),
        ) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals("/var/log/app/last.log" to "/tmp/last.log", sftp.lastDownload)
        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
    }

    @Test
    fun a_failing_transfer_stops_the_run_and_says_why() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        sftp.uploadError = "Permission denied"
        term.sftp = sftp

        r.startNow(runbook(transfer("s1"), step("s2", "systemctl restart app")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        val failure = assertIs<RunbookStepFailure.Transfer>(r.only.steps[0].failure)
        assertTrue(failure.message.contains("Permission denied"), failure.message)
        assertEquals(RunbookPhase.FAILED, r.phase)
        assertTrue(term.sent.isEmpty(), "the next step must not run after a failed transfer")
    }

    @Test
    fun a_transfer_closes_the_channel_it_opened() = runnerTest { r, term ->
        // Each transfer opens its own SFTP channel on the session's connection and owns closing it.
        // Leaked channels only surface much later, as an unrelated feature failing to open one.
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        term.sftp = sftp

        r.startNow(runbook(transfer("s1"), transfer("s2")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookPhase.DONE, r.phase)
        assertEquals(2, sftp.closeCount, "every transfer closes the channel it opened")
    }

    @Test
    fun a_failing_transfer_closes_its_channel_too() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        sftp.uploadError = "Permission denied"
        term.sftp = sftp

        r.startNow(runbook(transfer("s1")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        assertEquals(1, sftp.closeCount, "a throw must not leak the channel")
    }

    @Test
    fun a_second_start_is_refused_while_the_dialog_of_the_first_is_still_open() = runnerTest { r, term ->
        // Two clicks on Run before the dialog draws: the second must not replace the parked request,
        // or the values the user is already looking at (and this run's uuid/date draw) are discarded.
        assertTrue(r.requestStart(runbook(step("s1", "uptime")), term.target()))
        val parked = r.pending

        assertFalse(r.requestStart(runbook(step("s2", "df -h")), term.target()))

        assertSame(parked, r.pending)
    }

    @Test
    fun stopping_on_a_confirmation_pause_settles_the_step_it_was_waiting_on() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "systemctl stop app", confirm = true)), term.target()) { "" }
        assertEquals(RunbookStepStatus.AWAITING_CONFIRM, r.only.steps[0].status)

        r.stop()

        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status, "a paused step must not stay pending forever")
        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertTrue(term.sent.isEmpty(), "nothing was ever sent for a step the user stopped at the pause")
    }

    @Test
    fun a_transfer_on_a_connection_without_sftp_fails_the_step_instead_of_hanging() = runnerTest { r, term ->
        // A local shell, a telnet or a serial session has no SFTP channel to open.
        term.sftp = null

        r.startNow(runbook(transfer("s1")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.only.steps[0].status)
        assertEquals(RunbookStepFailure.NoSftpChannel, r.only.steps[0].failure)
        assertEquals(RunbookPhase.FAILED, r.phase)
    }

    @Test
    fun a_transfer_step_resolves_its_paths_from_the_run_values() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/releases")
        term.sftp = sftp

        r.startNow(
            runbook(transfer("s1", localPath = "/tmp/\${{tag}}.tgz", remotePath = "/srv/releases/\${{tag}}.tgz")),
            term.target(),
        ) { "0.2.1" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals("/tmp/0.2.1.tgz" to "/srv/releases/0.2.1.tgz", sftp.lastUpload)
    }

    @Test
    fun stopping_during_a_transfer_leaves_the_step_stopped() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        sftp.uploadSize = 4096
        sftp.transferGate = CompletableDeferred()
        term.sftp = sftp

        r.startNow(runbook(transfer("s1"), step("s2", "uptime")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()
        r.stop()
        sftp.transferGate?.complete(Unit)
        testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)
        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertTrue(term.sent.isEmpty(), "the step after a stopped transfer must not be sent")
    }

    // --- interactive steps: the command is sent bare and the user decides when it is done ---

    @Test
    fun an_interactive_step_is_sent_bare_and_waits_for_the_user() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "htop")), term.target()) { "" }

        // As-is: no probe around the line — an interactive program never exits, so a marker would
        // never appear — and nothing declared to the terminal, so nothing is captured or hidden.
        assertEquals(listOf("htop\n"), term.sent)
        assertTrue(term.expected.isEmpty(), "no step token may be declared for an interactive step")
        assertEquals(RunbookStepStatus.AWAITING_COMPLETE, r.only.steps[0].status)
        assertEquals(RunbookPhase.RUNNING, r.phase)

        // However long it runs, only the user finishes it — and the terminal is never polled for a
        // mark that cannot come.
        testScheduler.advanceTimeBy(stallAfter * 5); testScheduler.runCurrent()
        assertEquals(RunbookStepStatus.AWAITING_COMPLETE, r.only.steps[0].status)
        assertEquals(0, term.polls, "an interactive step has no mark to poll for")
        assertFalse(r.only.steps[0].stalled, "the watchdog has no meaning without a probe")
    }

    @Test
    fun completing_an_interactive_step_moves_to_the_next() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "mc"), step("s2", "df -h")), term.target()) { "" }

        r.completeStep()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[0].status)
        assertNull(r.only.steps[0].exitCode, "the user's word is not an exit code")
        // The next step is an ordinary one again: probe and declaration return.
        assertEquals(RunbookMarker.probeLine("df -h", RunbookMarker.token(RUN_ID, 1)) + "\n", term.sent[1])
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[1].status)
    }

    @Test
    fun completing_the_last_interactive_step_finishes_the_run() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "htop")), term.target()) { "" }
        r.completeStep()

        assertEquals(RunbookPhase.DONE, r.phase)
        assertFalse(r.hadFailures)
    }

    @Test
    fun completing_is_refused_while_an_ordinary_step_runs() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "sleep 5"), step("s2", "df -h")), term.target()) { "" }

        r.completeStep()

        // Only the shell's exit code may finish a probed step — the button must not exist for it.
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[0].status)
        assertEquals(1, term.sent.size)
    }

    @Test
    fun skipping_an_interactive_step_moves_on_without_counting_it_done() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "mc"), step("s2", "uptime")), term.target()) { "" }

        r.skipStep()

        assertEquals(RunbookStepStatus.SKIPPED, r.only.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.only.steps[1].status)
    }

    @Test
    fun stopping_an_interactive_step_settles_it() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "htop"), step("s2", "uptime")), term.target()) { "" }

        r.stop()

        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)
        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(1, term.sent.size, "nothing further may be sent after Stop")
    }

    @Test
    fun losing_the_session_ends_an_interactive_run() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "htop")), term.target()) { "" }
        term.live = false
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)
    }

    @Test
    fun an_interactive_step_with_confirm_pauses_first() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "htop", confirm = true)), term.target()) { "" }

        assertEquals(RunbookStepStatus.AWAITING_CONFIRM, r.only.steps[0].status)
        assertTrue(term.sent.isEmpty())

        r.confirmStep()

        assertEquals(listOf("htop\n"), term.sent)
        assertEquals(RunbookStepStatus.AWAITING_COMPLETE, r.only.steps[0].status)
    }

    @Test
    fun an_interactive_line_still_resolves_its_variables() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "docker exec -it \${{container}} sh")), term.target()) { "web-1" }

        assertEquals(listOf("docker exec -it web-1 sh\n"), term.sent)
    }

    @Test
    fun a_late_complete_after_stop_is_refused() = runnerTest { r, term ->
        // The liveness watcher's stop() and the user's click race on different threads; whichever
        // settles the step first wins, and the loser must be a no-op. Here stop wins: the click
        // landing afterwards must not overwrite STOPPED or type the next step into a dead session.
        // (settleInteractive does its whole check-then-act under the lock for exactly this.)
        r.startNow(runbook(interactive("s1", "htop"), step("s2", "uptime")), term.target()) { "" }
        r.stop()

        r.completeStep()

        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)
        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(1, term.sent.size, "a resurrected run would have typed s2")
    }

    @Test
    fun a_late_skip_after_stop_is_refused() = runnerTest { r, term ->
        r.startNow(runbook(interactive("s1", "htop"), step("s2", "uptime")), term.target()) { "" }
        r.stop()

        r.skipStep()

        assertEquals(RunbookStepStatus.STOPPED, r.only.steps[0].status)
        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(1, term.sent.size)
    }

    @Test
    fun skipping_an_interactive_step_advances_into_a_transfer() = runnerTest { r, term ->
        // The one step-kind hand-off new to this feature: an interactive settle must start SFTP
        // for the next step exactly as an exit code would.
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        term.sftp = sftp

        r.startNow(runbook(interactive("s1", "mc"), transfer("s2")), term.target()) { "" }
        r.skipStep()
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SKIPPED, r.only.steps[0].status)
        assertEquals("/tmp/release.tgz" to "/srv/incoming/release.tgz", sftp.lastUpload)
        assertEquals(RunbookStepStatus.SUCCEEDED, r.only.steps[1].status)
        assertEquals(RunbookPhase.DONE, r.phase)
    }

    @Test
    fun a_report_from_a_previous_step_cannot_finish_an_interactive_one() = runnerTest { r, term ->
        // The interactive program may print anything, including a stale mark of an earlier run
        // parked in the terminal. The user's click is the only way this step ends.
        r.startNow(runbook(interactive("s1", "htop")), term.target()) { "" }
        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll * 5); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.AWAITING_COMPLETE, r.only.steps[0].status)
    }

    private companion object {
        const val RUN_ID = "run"
    }
}
