package app.skerry.ui.runbook

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.snippet.SnippetMoment
import app.skerry.shared.snippet.SnippetRunEnvironment
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_panel_complete_step
import app.skerry.ui.generated.resources.runbook_panel_skip_step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The docked panel's interactive-step wiring. The panel is the phone's only run surface and its
 * button row is written independently of [RunbookRunView]'s — nothing but a test keeps the two
 * from drifting, and the runner's state machine cannot see a button that was never composed.
 */
@OptIn(ExperimentalTestApi::class)
class RunbookRunPanelTest {

    @Test
    fun `the panel completes and skips an interactive step`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sent = mutableListOf<String>()
        val runner = RunbookRunner(
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
        val target = RunbookTarget(
            sessionId = "tab-1",
            send = { line, _ -> sent += line },
            expectStep = { _, _ -> },
            takeMark = { null },
            outputVersion = { 0L },
        )
        val runbook = Runbook(
            id = "rb", label = "Ops",
            steps = listOf(
                RunbookStep.Command(id = "s1", command = "htop", confirm = false, interactive = true),
                RunbookStep.Command(id = "s2", command = "mc", confirm = false, interactive = true),
            ),
        )
        try {
            runner.requestStart(runbook, target)
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
}
