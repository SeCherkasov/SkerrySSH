package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.press
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_field_name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The phone's runbook editor. It shares [app.skerry.ui.runbook.RunbookEditorFields] with the
 * desktop panel, so this is about the sheet around them saving through to the library — and about
 * the same rule holding: a name without a step is not a procedure.
 */
@OptIn(ExperimentalTestApi::class)
class MobileRunbookFormTest {

    @Test
    fun `a runbook written on the phone lands in the library`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Runbooks)
        waitForIdle()
        openEditor()
        onField(Res.string.runbook_field_name).performTextInput(NAME)
        firstStepCommand().performTextInput(COMMAND)
        press(UiTags.FORM_SAVE)

        val saved = shell.runbooks.runbooks.map { it.runbook }.singleOrNull { it.label == NAME }
        assertNotNull(saved, "the phone editor saved nothing")
        val step = saved.steps.single()
        assertTrue(step is RunbookStep.Command)
        assertEquals(COMMAND, step.command)
    }

    @Test
    fun `a runbook with no step cannot be saved`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Runbooks)
        waitForIdle()
        openEditor()
        onField(Res.string.runbook_field_name).performTextInput(NAME)

        // Save is refused where the user can see it, not silently on the tap.
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        // And refused for real: a disabled control still carries its click action, which is the
        // one an accessibility service invokes.
        onNodeWithTag(UiTags.FORM_SAVE).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertTrue(shell.runbooks.runbooks.none { it.runbook.label == NAME })
    }

    private fun ComposeUiTest.firstStepCommand() = onAllNodesWithTag(UiTags.RUNBOOK_STEP_COMMAND)[0]

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.NEW_RUNBOOK).performClick()
        waitForIdle()
    }
}

private const val NAME = "restart nginx"
private const val COMMAND = "systemctl restart nginx"
