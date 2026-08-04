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
import kotlin.test.assertTrue

/**
 * Runbook run state machine. The terminal is faked as a byte sink plus a text buffer that echoes
 * whatever is sent (as a real PTY does) — that echo is exactly what must NOT be mistaken for a
 * finished step. Time is virtual: the runner polls the buffer for its marker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunbookRunnerTest {

    private val poll = 100L

    /** The runbooks under test carry a one-minute watchdog; time is virtual, so the wait is free. */
    private val stallAfter = 60_000L

    private class FakeTerminal {
        val sent = mutableListOf<String>()
        var buffer: String = ""
        var live: Boolean = true
        var reads: Int = 0

        /** SFTP side of the same session; `null` stands for a connection without one. */
        var sftp: FakeSftpClient? = null

        fun target(sessionId: String = "tab-1") = RunbookTarget(
            sessionId = sessionId,
            send = { line -> sent += line; buffer += line }, // the PTY echoes the typed line
            readOutput = { reads++; buffer },
            isLive = { live },
            openSftp = sftp?.let { client -> suspend { client as SftpClient } },
        )

        /** The shell finished the step and printed the marker. */
        fun complete(stepIndex: Int, exitCode: Int, runId: String = RUN_ID) {
            buffer += "\n" + RunbookMarker.token(runId, stepIndex) + ":" + exitCode + "\n"
        }
    }

    private fun environment() = SnippetRunEnvironment(
        moment = SnippetMoment(2026, 7, 26, 14, 5, 9, epochSeconds = 1_784_000_000L),
        newUuid = { "uuid" },
        randomChars = { n -> "r".repeat(n) },
    )

    private fun runbook(vararg steps: RunbookStep, policy: RunbookPolicy = RunbookPolicy(watchdogMinutes = 1)) =
        Runbook(id = "rb", label = "Deploy", steps = steps.toList(), policy = policy)

    private fun step(id: String, command: String, confirm: Boolean = false, continueOnError: Boolean = false) =
        RunbookStep.Command(id = id, title = id, command = command, confirm = confirm, continueOnError = continueOnError)

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
        assertFalse(r.steps[0].stalled)

        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()

        assertTrue(r.steps[0].stalled)
        // Not killed: `sleep 3600` and a silent migration look exactly the same from here, so the
        // decision stays the user's.
        assertEquals(RunbookStepStatus.RUNNING, r.steps[0].status)
        assertEquals(RunbookPhase.RUNNING, r.phase)
    }

    @Test
    fun a_step_that_keeps_printing_is_never_flagged() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "apt upgrade")), term.target()) { "" }

        repeat(6) {
            testScheduler.advanceTimeBy(stallAfter / 2); testScheduler.runCurrent()
            term.buffer += "Unpacking package $it\n"
        }
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertFalse(r.steps[0].stalled, "a long step that talks is not a stuck one")
    }

    @Test
    fun output_after_a_quiet_spell_clears_the_flag() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "./migrate.sh")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.steps[0].stalled)

        term.buffer += "migrating 1/400\n"
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertFalse(r.steps[0].stalled)
    }

    @Test
    fun a_step_that_reports_after_a_quiet_spell_does_not_stay_flagged() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "sleep 300")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.steps[0].stalled)

        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll * 2); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.steps[0].status)
        assertFalse(r.steps[0].stalled)
    }

    @Test
    fun stopping_a_flagged_step_clears_the_flag_and_a_late_poll_cannot_bring_it_back() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "cat <<EOF")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.steps[0].stalled)

        r.stop()
        // A poll that passed its staleness check just before Stop must not re-flag a run that is
        // over — the same race the generation guard exists for, now on this flag too.
        testScheduler.advanceTimeBy(stallAfter * 2); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.STOPPED, r.steps[0].status)
        assertFalse(r.steps[0].stalled)
    }

    @Test
    fun a_new_run_after_a_stalled_one_starts_unflagged() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "cat <<EOF")), term.target()) { "" }
        testScheduler.advanceTimeBy(stallAfter + poll * 2); testScheduler.runCurrent()
        assertTrue(r.steps[0].stalled)
        r.stop()

        r.startNow(runbook(step("s1", "uptime")), term.target()) { "" }

        assertFalse(r.steps[0].stalled)
    }

    @Test
    fun sends_the_command_with_the_exit_code_probe() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "systemctl restart nginx")), term.target()) { "" }

        val expected = RunbookMarker.probeLine("systemctl restart nginx", RunbookMarker.token(RUN_ID, 0)) + "\n"
        assertEquals(listOf(expected), term.sent)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[0].status)
    }

    @Test
    fun the_echoed_line_alone_never_finishes_a_step() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll * 20); testScheduler.runCurrent()

        // Only the first step was ever sent: the echo of its own line must not read as an exit code.
        assertEquals(1, term.sent.size)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[0].status)
    }

    @Test
    fun walks_the_steps_as_each_exit_code_arrives() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        term.complete(0, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals(0, r.steps[0].exitCode)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[1].status)

        term.complete(1, 0)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.SUCCEEDED, r.steps[1].status)
        assertEquals(RunbookPhase.DONE, r.phase)
        assertFalse(r.active)
        assertFalse(r.hadFailures)
    }

    @Test
    fun a_confirm_step_waits_until_the_user_says_go() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "reboot", confirm = true)), term.target()) { "" }
        testScheduler.advanceTimeBy(poll * 10); testScheduler.runCurrent()

        assertEquals(RunbookPhase.AWAITING_CONFIRM, r.phase)
        assertEquals(RunbookStepStatus.AWAITING_CONFIRM, r.steps[0].status)
        assertTrue(term.sent.isEmpty(), "nothing may reach the shell before the go-ahead")

        r.confirmStep()
        assertEquals(1, term.sent.size)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[0].status)
    }

    @Test
    fun a_failing_step_stops_the_run_and_leaves_the_rest_untouched() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "migrate"), step("s2", "restart")), term.target()) { "" }
        term.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.steps[0].status)
        assertEquals(1, r.steps[0].exitCode)
        assertEquals(RunbookStepStatus.PENDING, r.steps[1].status)
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

        assertEquals(RunbookStepStatus.FAILED, r.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[1].status)
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

        assertEquals(RunbookStepStatus.SKIPPED, r.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[1].status)
        assertEquals(listOf(RunbookMarker.probeLine("uptime", RunbookMarker.token(RUN_ID, 1)) + "\n"), term.sent)
    }

    @Test
    fun stopping_ends_the_watch_and_sends_nothing_more() = runnerTest { r, term ->
        r.startNow(runbook(step("s1", "uptime"), step("s2", "df -h")), term.target()) { "" }
        r.stop()

        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertEquals(RunbookStepStatus.STOPPED, r.steps[0].status)

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
        val after = term.reads
        testScheduler.advanceTimeBy(poll * 20); testScheduler.runCurrent()

        assertEquals(after, term.reads, "a stopped run must not keep scanning the buffer")
    }

    @Test
    fun a_stop_landing_during_the_poll_does_not_send_the_next_step() = runTest {
        // Single-threaded stand-in for the cross-thread race (same trick as PingControllerTest): the
        // buffer read is where Stop lands, so the watcher holds a finished step's exit code that is
        // no longer allowed to advance the run.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val term = FakeTerminal()
        var runner: RunbookRunner? = null
        val target = RunbookTarget(
            sessionId = "tab-1",
            send = { line -> term.sent += line; term.buffer += line },
            readOutput = { runner!!.stop(); term.buffer },
            isLive = { true },
        )
        val r = RunbookRunner(scope, newId = { RUN_ID }, environment = ::environment, pollIntervalMillis = poll)
        runner = r
        try {
            r.requestStart(runbook(step("s1", "uptime"), step("s2", "df -h")), target)
            r.confirmStart { "" }
            term.complete(0, 0)
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

        assertTrue(term.sent.single().startsWith("deploy billing;"), term.sent.single())
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
        assertTrue(r.steps.isEmpty())
    }

    @Test
    fun a_watchdog_turned_off_never_flags_a_quiet_step() = runnerTest { r, term ->
        r.startNow(
            runbook(step("s1", "cat <<EOF"), policy = RunbookPolicy(watchdogMinutes = 0)),
            term.target(),
        ) { "" }

        testScheduler.advanceTimeBy(stallAfter * 10); testScheduler.runCurrent()

        assertFalse(r.steps[0].stalled)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[0].status)
    }

    @Test
    fun a_run_that_does_not_stop_on_failure_carries_on_to_the_next_step() = runnerTest { r, term ->
        r.startNow(
            runbook(step("s1", "migrate"), step("s2", "restart"), policy = RunbookPolicy(stopOnFirstFailure = false)),
            term.target(),
        ) { "" }
        term.complete(0, 1)
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.steps[0].status)
        assertEquals(RunbookStepStatus.RUNNING, r.steps[1].status)
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
        assertEquals(RunbookStepStatus.SUCCEEDED, r.steps[0].status)
        assertEquals(0, r.steps[0].exitCode)
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

        assertEquals(4096L, r.steps[0].transferredBytes)
        assertEquals(4096L, r.steps[0].totalBytes)
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
        assertEquals(RunbookStepStatus.SUCCEEDED, r.steps[0].status)
    }

    @Test
    fun a_failing_transfer_stops_the_run_and_says_why() = runnerTest { r, term ->
        val sftp = FakeSftpClient()
        sftp.seedDir("/srv/incoming")
        sftp.uploadError = "Permission denied"
        term.sftp = sftp

        r.startNow(runbook(transfer("s1"), step("s2", "systemctl restart app")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.steps[0].status)
        val failure = assertIs<RunbookStepFailure.Transfer>(r.steps[0].failure)
        assertTrue(failure.message.contains("Permission denied"), failure.message)
        assertEquals(RunbookPhase.FAILED, r.phase)
        assertTrue(term.sent.isEmpty(), "the next step must not run after a failed transfer")
    }

    @Test
    fun a_transfer_on_a_connection_without_sftp_fails_the_step_instead_of_hanging() = runnerTest { r, term ->
        // A local shell, a telnet or a serial session has no SFTP channel to open.
        term.sftp = null

        r.startNow(runbook(transfer("s1")), term.target()) { "" }
        testScheduler.advanceTimeBy(poll); testScheduler.runCurrent()

        assertEquals(RunbookStepStatus.FAILED, r.steps[0].status)
        assertEquals(RunbookStepFailure.NoSftpChannel, r.steps[0].failure)
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

        assertEquals(RunbookStepStatus.STOPPED, r.steps[0].status)
        assertEquals(RunbookPhase.STOPPED, r.phase)
        assertTrue(term.sent.isEmpty(), "the step after a stopped transfer must not be sent")
    }

    private companion object {
        const val RUN_ID = "run"
    }
}
