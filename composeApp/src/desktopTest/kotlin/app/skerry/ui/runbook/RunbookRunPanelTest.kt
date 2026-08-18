package app.skerry.ui.runbook

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.ui.desktop.drawnText
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_close
import app.skerry.ui.generated.resources.runbook_panel_collapse
import app.skerry.ui.generated.resources.runbook_panel_complete_step
import app.skerry.ui.generated.resources.runbook_panel_expand
import app.skerry.ui.generated.resources.runbook_panel_run_step
import app.skerry.ui.generated.resources.runbook_panel_skip_step
import app.skerry.ui.generated.resources.runbook_panel_stop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The docked panel's interactive-step wiring. The panel is the phone's only run surface and its
 * button row is written independently of [RunbookRunView]'s — nothing but a test keeps the two
 * from drifting, and the runner's state machine cannot see a button that was never composed.
 */
@OptIn(ExperimentalTestApi::class)
class RunbookRunPanelTest {

    private fun panelRunner(scope: CoroutineScope): RunbookRunner = RunbookRunner(
        scope = scope,
        newId = { "run" },
        environment = {
            SnippetRunEnvironment(
                moment = SnippetMoment(2026, 8, 14, 12, 0, 0, epochSeconds = 1_786_000_000L),
                newUuid = { "u" },
                randomChars = { n, _ -> "r".repeat(n) },
            )
        },
    )

    private fun target(sent: MutableList<String> = mutableListOf()): RunbookTarget = RunbookTarget(
        sessionId = "tab-1",
        send = { line, _ -> sent += line },
        expectStep = { _, _ -> },
        takeMark = { null },
        outputVersion = { 0L },
    )

    /**
     * The panel is what is on screen while a shared procedure runs, and its rows carry the author's
     * own title and line. Filtered and spelled out like every other surface that draws them — the
     * one place this had to be tested through a live run rather than a form.
     */
    @Test
    fun `the running panel draws neither the step title nor its line raw`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb",
            label = "Rollout\u202Etuollor",
            steps = listOf(
                RunbookStep.Command(
                    id = "s1",
                    title = "Deploy\u202Eyolped",
                    command = "echo ok \u202E# rm -rf /",
                    confirm = false,
                    interactive = true,
                ),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                val drawn = drawnText()
                assertTrue(drawn.isNotEmpty(), "the panel drew nothing")
                assertTrue(
                    drawn.none { text -> text.codePoints().anyMatch { Character.getType(it) == Character.FORMAT.toInt() } },
                    "a reordering character reached the running panel, was $drawn",
                )
                // And the positive half: the filter that merely dropped the character passes the
                // check above too. What separates the two is that this one spells it out.
                assertTrue(
                    drawn.any { it.contains("<U+202E>") },
                    "the panel dropped the override instead of spelling it out, was $drawn",
                )
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `the panel completes and skips an interactive step`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "mc", confirm = false, interactive = true),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithText(string(Res.string.runbook_panel_complete_step)).performClick()
                waitForIdle()
                assertEquals(RunbookStepStatus.SUCCEEDED, runner.run?.steps?.get(0)?.status)

                onNodeWithText(string(Res.string.runbook_panel_skip_step)).performClick()
                waitForIdle()
                assertEquals(RunbookStepStatus.SKIPPED, runner.run?.steps?.get(1)?.status)
                assertEquals(RunbookPhase.DONE, runner.phase)

                // The run is over: the interactive controls are gone with the state they served.
                onNodeWithText(string(Res.string.runbook_panel_complete_step)).assertDoesNotExist()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `the panel collapses to its header and expands back`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true)),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithText("htop").assertExists()

                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                // Collapsed: the step list and the button row are gone, the header stays.
                onNodeWithText("htop").assertDoesNotExist()
                onNodeWithText(string(Res.string.runbook_panel_stop)).assertDoesNotExist()
                onNodeWithText("Ops").assertExists()

                onNodeWithContentDescription(string(Res.string.runbook_panel_expand)).performClick()
                waitForIdle()
                onNodeWithText("htop").assertExists()
                onNodeWithText(string(Res.string.runbook_panel_stop)).assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `the end of the run expands a collapsed panel`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true)),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()

                // A collapsed panel on a finished run must reopen by itself: its Close button is
                // the only way to dismiss the run, and it lives in the collapsible body.
                runner.stop()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_close)).assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a stalled step expands a collapsed panel`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true)),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText("htop").assertDoesNotExist()

                // Flagged directly: stall *detection* is the runner's business and has its own
                // tests — this one is about the panel reacting to the flag.
                runner.run?.steps?.get(0)?.stalled = true
                waitForIdle()
                onNodeWithText("htop").assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `advancing between steps keeps the panel collapsed`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "mc", confirm = false, interactive = true),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()

                // s1 done, s2 (interactive) starts: the phase never leaves RUNNING, so the user's
                // collapse must survive the step change — an interactive step is exactly what the
                // panel gets collapsed for.
                runner.completeStep()
                waitForIdle()
                onNodeWithText("mc").assertDoesNotExist()
                onNodeWithText(string(Res.string.runbook_panel_stop)).assertDoesNotExist()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a tolerated failure expands a collapsed panel`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                // No SFTP channel on the test target: fails on dispatch, and the run carries on.
                RunbookStep.Transfer(id = "s2", localPath = "/tmp/a", remotePath = "/tmp/b", confirm = false, continueOnError = true),
                RunbookStep.Command(id = "s3", command = "mc", confirm = false, interactive = true),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText("htop").assertDoesNotExist()

                // The failure keeps the phase at RUNNING (continueOnError), so only the failure
                // count can reopen the panel — a run must not fail behind a collapsed header.
                runner.completeStep()
                waitUntil(timeoutMillis = 5_000) { runner.run?.steps?.get(1)?.status == RunbookStepStatus.FAILED }
                waitForIdle()
                onNodeWithText("mc").assertExists()
                assertEquals(RunbookPhase.RUNNING, runner.phase)
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a new run starts expanded even after a collapsed finish`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val first = Runbook(
            id = "rb1", label = "Ops",
            steps = listOf(RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true)),
        )
        val second = Runbook(
            id = "rb2", label = "Deploy",
            steps = listOf(RunbookStep.Command(id = "s1", command = "mc", confirm = false, interactive = true)),
        )
        try {
            runner.requestStart(first, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                runner.stop()
                waitForIdle()
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_close)).assertDoesNotExist()

                // The collapse belonged to the finished run: the next run is a new decision.
                runner.requestStart(second, target())
                runner.confirmStart { "" }
                waitForIdle()
                onNodeWithText("mc").assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `the collapse survives the panel leaving composition`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true)),
        )
        val visible = mutableStateOf(true)
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ if (visible.value) runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText("htop").assertDoesNotExist()

                // A tab switch takes the panel out of composition; coming back must not undo the
                // user's collapse mid-interactive-step — the flag lives on the run, not in remember.
                visible.value = false
                waitForIdle()
                visible.value = true
                waitForIdle()
                onNodeWithText("Ops").assertExists()
                onNodeWithText("htop").assertDoesNotExist()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a re-collapse during a pause survives the panel leaving composition`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "systemctl restart app", confirm = true),
            ),
        )
        val visible = mutableStateOf(true)
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ if (visible.value) runner.run?.let { RunbookRunPanel(runner, it) } }) {
                runner.completeStep()
                waitForIdle()
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_run_step)).assertDoesNotExist()

                // The reopen effect re-runs on every re-entry into composition; the pause it would
                // act on is the one the user already collapsed over — not a fresh signal.
                visible.value = false
                waitForIdle()
                visible.value = true
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_run_step)).assertDoesNotExist()
                onNodeWithText("Ops").assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `each new tolerated failure reopens a re-collapsed panel`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        fun transfer(id: String) = RunbookStep.Transfer(
            id = id, localPath = "/tmp/a", remotePath = "/tmp/b", confirm = false, continueOnError = true,
        )
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                transfer("s2"),
                RunbookStep.Command(id = "s3", command = "mc", confirm = false, interactive = true),
                transfer("s4"),
                RunbookStep.Command(id = "s5", command = "vi", confirm = false, interactive = true),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()

                runner.completeStep()
                waitUntil(timeoutMillis = 5_000) { runner.run?.steps?.get(1)?.status == RunbookStepStatus.FAILED }
                waitForIdle()
                onNodeWithText("mc").assertExists()

                // The first failure was seen and dismissed; the second is news of its own.
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText("mc").assertDoesNotExist()
                runner.completeStep()
                waitUntil(timeoutMillis = 5_000) { runner.run?.steps?.get(3)?.status == RunbookStepStatus.FAILED }
                waitForIdle()
                onNodeWithText("vi").assertExists()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a re-collapse during a pause is not fought by the panel`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "systemctl restart app", confirm = true),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                runner.completeStep()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_run_step)).assertExists()

                // The reopen is keyed on the signal, not the collapse flag: collapsing an
                // already-paused run is the user's word and stays until the next signal.
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_run_step)).assertDoesNotExist()
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }

    @Test
    fun `a confirmation pause expands a collapsed panel`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = panelRunner(scope)
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "systemctl restart app", confirm = true),
            ),
        )
        try {
            runner.requestStart(runbook, target())
            runner.confirmStart { "" }
            runForm({ runner.run?.let { RunbookRunPanel(runner, it) } }) {
                onNodeWithContentDescription(string(Res.string.runbook_panel_collapse)).performClick()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_stop)).assertDoesNotExist()

                // The interactive step is declared done off-screen; the run pauses on s2's
                // confirmation — the panel must reopen by itself, or the pause has no buttons.
                runner.completeStep()
                waitForIdle()
                onNodeWithText(string(Res.string.runbook_panel_run_step)).assertExists()
                assertEquals(RunbookPhase.AWAITING_CONFIRM, runner.phase)
            }
        } finally {
            runner.close()
            scope.cancel()
        }
    }
}
