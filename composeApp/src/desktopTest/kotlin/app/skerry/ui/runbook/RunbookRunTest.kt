package app.skerry.ui.runbook

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.FakeShellInput
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_delete
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_run_title
import app.skerry.ui.generated.resources.shell_cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starting a runbook from its section: the panel's Run only asks, and the confirmation is what
 * actually sends the first line into the session.
 *
 * [RunbookRunner] is covered as a state machine on its own; the wiring around it is not, and both
 * ends of it matter here. A Run that starts without the dialog would put commands on a production
 * host with nothing previewed, and a confirmed dialog that sends nothing would look like a step that
 * printed no output.
 */
@OptIn(ExperimentalTestApi::class)
class RunbookRunTest {

    @Test
    fun `confirming the start dialog sends the first step into the session`() = runDesktopShell { shell ->
        shell.seedRunbook()
        openRunbooks()
        FakeShellInput.clear()

        onRunbookRow(RUNBOOK_NAME).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.runbook_run)).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.runbook_run_title)).assertIsDisplayed()
        assertTrue(
            FakeShellInput.all().none { it.contains(FIRST_COMMAND) },
            "the panel's Run may only ask — nothing runs before the preview is confirmed",
        )

        confirmStart()
        waitUntil { FakeShellInput.all().any { it.contains(FIRST_COMMAND) } }
    }

    /** Dismissing the preview is a decision, not a delay: the run must not be left half-armed. */
    @Test
    fun `dismissing the start dialog runs nothing`() = runDesktopShell { shell ->
        shell.seedRunbook()
        openRunbooks()
        FakeShellInput.clear()

        onRunbookRow(RUNBOOK_NAME).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.runbook_run)).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.shell_cancel)).performClick()
        waitForIdle()

        assertNull(shell.runner.pending, "a dismissed dialog leaves no request behind")
        assertFalse(shell.runner.active)
        assertTrue(FakeShellInput.all().none { it.contains(FIRST_COMMAND) })
    }

    @Test
    fun `deleting a runbook takes its row with it`() = runDesktopShell { shell ->
        shell.seedRunbook()
        openRunbooks()
        onRunbookRow(RUNBOOK_NAME).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.runbook_delete)).performClick()
        waitForIdle()

        assertEquals(emptyList(), shell.runbooks.runbooks.map { it.runbook.label })
        onRunbookRow(RUNBOOK_NAME).assertDoesNotExist()
    }

    /** The list row rather than the panel's heading of the same name: only the row takes a click. */
    private fun ComposeUiTest.onRunbookRow(name: String): SemanticsNodeInteraction =
        onNode(hasText(name) and hasClickAction())

    private fun ComposeUiTest.openRunbooks() {
        onNodeWithTag(UiTags.railView(DesktopView.Runbooks)).performClick()
        waitForIdle()
    }

    /**
     * The panel and the dialog both call their button Run. The dialog is composed over the section,
     * so it is the last of the two.
     */
    private fun ComposeUiTest.confirmStart() {
        onAllNodes(hasText(string(Res.string.runbook_run)) and hasClickAction()).onLast().performClick()
        waitForIdle()
    }

    /**
     * One procedure to run, saved the way the editor saves it. The first step asks for no
     * confirmation of its own, so the run reaches the shell as soon as the start dialog is answered.
     */
    private fun DesktopShell.seedRunbook() {
        runbooks.save(
            RunbookDraft(
                label = RUNBOOK_NAME,
                steps = listOf(
                    RunbookStep.Command(id = "", title = "Uptime", command = FIRST_COMMAND, confirm = false),
                    RunbookStep.Command(id = "", title = "Disk", command = "df -h"),
                ),
            ),
        )
    }
}

private const val RUNBOOK_NAME = "Morning check"
private const val FIRST_COMMAND = "uptime"
