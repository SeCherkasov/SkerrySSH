package app.skerry.ui.runbook

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.generated.resources.runbook_field_name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The runbook editor. A runbook is a named list of steps run against a host, so a name alone is not
 * a runbook — [RunbookFormState.canSave] wants at least one filled step, and Save has to say so
 * rather than store a procedure that does nothing.
 */
@OptIn(ExperimentalTestApi::class)
class RunbookEditorFormTest {

    @Test
    fun `a runbook needs a step, not just a name`() = runDesktopShell {
        openEditor()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onField(Res.string.runbook_field_name).performTextInput(NAME)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        firstStepCommand().performTextInput(COMMAND)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
    }

    @Test
    fun `a saved runbook keeps what was typed`() = runDesktopShell { shell ->
        openEditor()
        onField(Res.string.runbook_field_name).performTextInput(NAME)
        firstStepCommand().performTextInput(COMMAND)
        onField(Res.string.runbook_field_description).performTextInput(DESCRIPTION)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performScrollTo().performClick()
        waitForIdle()

        val saved = shell.runbooks.runbooks.map { it.runbook }.singleOrNull { it.label == NAME }
        assertNotNull(saved, "the editor saved nothing")
        assertEquals(DESCRIPTION, saved.description)
        val step = saved.steps.single()
        assertTrue(step is RunbookStep.Command, "the editor's first step should be a command step")
        assertEquals(COMMAND, step.command)
    }

    @Test
    fun `cancelling the editor writes nothing`() = runDesktopShell { shell ->
        openEditor()
        onField(Res.string.runbook_field_name).performTextInput(NAME)
        firstStepCommand().performTextInput(COMMAND)
        onNodeWithTag(UiTags.FORM_CANCEL).performScrollTo().performClick()
        waitForIdle()

        assertEquals(emptyList(), shell.runbooks.runbooks.map { it.runbook.label })
    }

    /**
     * The step rows are a repeating list rather than captioned fields, so the command box is reached
     * by its tag rather than a label — see [UiTags.RUNBOOK_STEP_COMMAND].
     */
    private fun ComposeUiTest.firstStepCommand() = onAllNodesWithTag(UiTags.RUNBOOK_STEP_COMMAND)[0]

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.railView(DesktopView.Runbooks)).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.NEW_RUNBOOK).performClick()
        waitForIdle()
    }
}

private const val NAME = "restart nginx"
private const val DESCRIPTION = "reload the unit and check it came back"
private const val COMMAND = "systemctl restart nginx"
