package app.skerry.ui.runbook

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_field_description
import app.skerry.ui.generated.resources.runbook_field_name
import app.skerry.ui.generated.resources.runbook_run
import app.skerry.ui.generated.resources.runbook_none_runnable
import app.skerry.ui.generated.resources.runbook_run_no_steps
import app.skerry.ui.generated.resources.runbook_toolbar_tip
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

    /**
     * A runbook with no steps only arrives by sync — the editor above will not save one — and the
     * runner refuses to start it. The card has to say so, the way the phone's sheet does, instead
     * of offering a Run that quietly does nothing.
     */
    @Test
    fun `a synced runbook with no steps cannot be run`() = runDesktopShell(withSessions = true) { shell ->
        val label = "Synced empty procedure"
        shell.runbooks.save(RunbookDraft(label = label, description = DESCRIPTION))
        onNodeWithTag(UiTags.railView(DesktopView.Runbooks)).performClick()
        waitForIdle()
        // The only runbook in the library, so the section opens with its card already selected.
        // The reason rides on the button as its state; the line below it is drawn for the eye only.
        onNodeWithText(string(Res.string.runbook_run))
            .assertIsNotEnabled()
            .assert(hasStateDescription(string(Res.string.runbook_run_no_steps)))
        // The button has no guard of its own, so the disabled state is the whole defence: a click
        // action fires on a disabled control, and this one must reach nothing.
        onNodeWithText(string(Res.string.runbook_run)).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        onNodeWithTag(UiTags.screen(DesktopView.Runbooks), useUnmergedTree = true).assertExists()
    }

    /** The palette starts a run on one tap, so it may only offer what the runner would accept. */
    @Test
    fun `the terminal palette offers only runbooks that can start`() = runDesktopShell(withSessions = true) { shell ->
        shell.runbooks.save(RunbookDraft(label = "Runnable", steps = listOf(RunbookStep.Command(id = "s1", command = "uptime"))))
        shell.runbooks.save(RunbookDraft(label = "Synced empty procedure"))

        onNodeWithContentDescription(string(Res.string.runbook_toolbar_tip)).performClick()
        waitForIdle()

        onNodeWithText("Runnable").assertIsDisplayed()
        onNodeWithText("Synced empty procedure").assertDoesNotExist()
    }

    /** A library of step-less runbooks is not an empty library, and the palette must not say it is. */
    @Test
    fun `the palette says why it offers nothing`() = runDesktopShell(withSessions = true) { shell ->
        shell.runbooks.save(RunbookDraft(label = "Synced empty procedure"))

        onNodeWithContentDescription(string(Res.string.runbook_toolbar_tip)).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.runbook_none_runnable)).assertIsDisplayed()
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
