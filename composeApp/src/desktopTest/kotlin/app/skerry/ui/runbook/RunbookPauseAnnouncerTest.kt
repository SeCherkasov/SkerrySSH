package app.skerry.ui.runbook

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.shared.runbook.RunbookTransferDirection
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_done_with_failures
import app.skerry.ui.generated.resources.runbook_panel_stalled
import app.skerry.ui.generated.resources.runbook_panel_stopped
import app.skerry.ui.generated.resources.runbook_status_failed
import app.skerry.ui.generated.resources.runbook_status_failed_count
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * The announcer's message across the run's own pauses: it must change on every pause — a repeat of
 * the same string is deduplicated into silence by the live region, which is exactly why the step
 * number is part of it (two consecutive interactive steps would otherwise announce once).
 */
@OptIn(ExperimentalTestApi::class)
class RunbookPauseAnnouncerTest {

    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    // The poll interval is effectively infinite: the step-mark watcher re-derives the stall flag on
    // every poll, and a test that sets the flag by hand must not race it.
    private fun announcerRunner(scope: CoroutineScope): RunbookRunner = RunbookRunner(
        scope = scope,
        newId = { "run" },
        environment = {
            SnippetRunEnvironment(
                moment = SnippetMoment(2026, 8, 14, 12, 0, 0, epochSeconds = 1_786_000_000L),
                newUuid = { "u" },
                randomChars = { n, _ -> "r".repeat(n) },
            )
        },
        pollIntervalMillis = 1_000_000L,
    )

    private fun target(): RunbookTarget = RunbookTarget(
        sessionId = "tab-1",
        send = { _, _ -> },
        expectStep = { _, _ -> },
        takeMark = { null },
        outputVersion = { 0L },
    )

    @Test
    fun `each pause announces itself with its step number`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = announcerRunner(scope)
        val target = target()
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "mc", confirm = false, interactive = true),
                RunbookStep.Command(id = "s3", command = "reboot", confirm = true),
            ),
        )
        try {
            runForm({ RunbookPauseAnnouncer(runner) }) {
                // Composed before the run exists: the first pause is a change, not an insertion.
                onNode(polite).assert(hasContentDescription(""))

                runner.requestStart(runbook, target)
                runner.confirmStart { "" }
                waitForIdle()
                onNode(polite).assert(hasContentDescription("Step 1", substring = true))

                runner.completeStep()
                waitForIdle()
                // The next interactive pause is a different string — not deduplicated into silence.
                onNode(polite).assert(hasContentDescription("Step 2", substring = true))

                runner.completeStep()
                waitForIdle()
                // A confirmation pause announces too, with its own wording.
                onNode(polite).assert(hasContentDescription("Step 3", substring = true))

                runner.stop()
                waitForIdle()
                // The ending is a signal too: it reopens a collapsed panel, and it must be said —
                // Compose announces no node insertion, so this line is all a non-sighted user gets.
                onNode(polite).assert(hasContentDescription(string(Res.string.runbook_panel_stopped)))
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a stalled step announces itself`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = announcerRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(RunbookStep.Command(id = "s1", command = "sleep 600", confirm = false)),
        )
        try {
            runForm({ RunbookPauseAnnouncer(runner) }) {
                runner.requestStart(runbook, target())
                runner.confirmStart { "" }
                waitForIdle()
                onNode(polite).assert(hasContentDescription(""))

                // Flagged directly, like the panel's own stall test: detection is the runner's
                // business — this is about the warning being said, not derived.
                runner.run?.steps?.get(0)?.stalled = true
                waitForIdle()
                onNode(polite).assert(hasContentDescription(string(Res.string.runbook_panel_stalled), substring = true))
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `coalesced failures are announced as a count`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = announcerRunner(scope)
        fun transfer(id: String) = RunbookStep.Transfer(
            id = id, localPath = "/tmp/a", remotePath = "/tmp/b", confirm = false, continueOnError = true,
        )
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(transfer("s1"), transfer("s2"), RunbookStep.Command(id = "s3", command = "sleep 600", confirm = false)),
        )
        try {
            runForm({ RunbookPauseAnnouncer(runner) }) {
                runner.requestStart(runbook, target())
                runner.confirmStart { "" }
                // Both transfers fail on dispatch, possibly inside one recomposition — a
                // single-step line would voice only the newest and drop the first one.
                waitUntil(timeoutMillis = 5_000) { runner.run?.steps?.get(1)?.status == RunbookStepStatus.FAILED }
                waitForIdle()
                onNode(polite).assert(hasContentDescription(string(Res.string.runbook_status_failed_count, 2)))
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a tolerated failure on the last step folds into the ending`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = announcerRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Transfer(
                    id = "s1", localPath = "/tmp/a", remotePath = "/tmp/b", confirm = false, continueOnError = true,
                ),
            ),
        )
        try {
            runForm({ RunbookPauseAnnouncer(runner) }) {
                runner.requestStart(runbook, target())
                runner.confirmStart { "" }
                // failStep -> advance past the end -> finish(DONE) happen in one call stack, so the
                // failure and the ending land in the same snapshot — deterministically, not as a
                // race — and the ending's own words carry the failure.
                waitUntil(timeoutMillis = 5_000) { runner.phase == RunbookPhase.DONE }
                waitForIdle()
                onNode(polite).assert(hasContentDescription(string(Res.string.runbook_panel_done_with_failures)))
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a tolerated failure announces itself`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = announcerRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                // No SFTP channel on the test target: fails on dispatch, the run carries on.
                RunbookStep.Transfer(
                    id = "s1", localPath = "/tmp/a", remotePath = "/tmp/b",
                    direction = RunbookTransferDirection.UPLOAD, confirm = false, continueOnError = true,
                ),
                RunbookStep.Command(id = "s2", command = "sleep 600", confirm = false),
            ),
        )
        try {
            runForm({ RunbookPauseAnnouncer(runner) }) {
                runner.requestStart(runbook, target())
                runner.confirmStart { "" }
                waitUntil(timeoutMillis = 5_000) { runner.run?.steps?.get(0)?.status == RunbookStepStatus.FAILED }
                waitForIdle()
                // The failure keeps the run in RUNNING and moves on — no pause, no ending, and the
                // reopened panel's red row is a node insertion. This line is all a screen reader gets.
                onNode(polite).assert(hasContentDescription(string(Res.string.runbook_status_failed), substring = true))
                onNode(polite).assert(hasContentDescription("Step 1", substring = true))
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }
}
