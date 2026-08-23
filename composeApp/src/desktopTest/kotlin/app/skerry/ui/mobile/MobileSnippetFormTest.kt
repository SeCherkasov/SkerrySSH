package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.lib_snippets_run_in_terminal
import app.skerry.ui.generated.resources.lib_snippets_run_needs_save
import app.skerry.ui.snippet.SnippetDraft
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import kotlin.test.assertNotNull

/**
 * The phone's snippet editor. Its form state is the same class the desktop editor uses, so the
 * validation is not what is in question here — the wiring is. Parity is a rule in this project, and
 * a sheet that drops the command on save would be a phone-only bug the desktop test cannot see.
 *
 * The sheet writes through [app.skerry.ui.snippet.SnippetManager], supplied to the shell.
 */
@OptIn(ExperimentalTestApi::class)
class MobileSnippetFormTest {

    @Test
    fun `a snippet typed on the phone lands in the library`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.snippets.snippets.map { it.snippet }.singleOrNull { it.label == NAME }
        assertEquals(COMMAND, saved?.command, "the phone editor saved nothing or lost the command")
    }

    @Test
    fun `a snippet with notes typed on the phone lands in the library`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onField(Res.string.lib_snippets_field_notes).performTextInput("Mobile test notes")
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.snippets.snippets.map { it.snippet }.singleOrNull { it.label == NAME }
        assertEquals("Mobile test notes", saved?.notes)
    }

    /** Same rule as the desktop editor: a snippet with no command is not saved. */
    @Test
    fun `a snippet with no command is not saved`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)

        // Refused where the user can see it, and refused for real: a disabled control still carries
        // the click action an accessibility service invokes.
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        onNodeWithTag(UiTags.FORM_SAVE).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()

        assertNull(shell.snippets.snippets.firstOrNull { it.snippet.label == NAME })
    }

    /**
     * The fourth one-tap surface: the Run button of the sheet the Snippets tab opens. Its card shows
     * three lines, so it owes the gate the palette and the phone's run sheet owe.
     */
    @Test
    fun `the snippets tab confirms a command its card cannot show whole`() = runMobileShell(withSessions = true) { shell ->
        shell.snippets.save(SnippetDraft(label = NAME, command = "echo " + "x".repeat(300)))
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        onNodeWithText(NAME).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.lib_snippets_run_in_terminal)).performClick()
        waitForIdle()

        assertNotNull(shell.snippets.pendingRun, "the sheet sent a command it only showed part of")
    }

    /**
     * Run starts the saved record while the field above it shows a draft. Edited and not saved, the
     * two are different commands, and the button would run the one that is not on screen.
     */
    @Test
    fun `an edited command cannot be run before it is saved`() = runMobileShell(withSessions = true) { shell ->
        shell.snippets.save(SnippetDraft(label = NAME, command = COMMAND))
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        onNodeWithText(NAME).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.lib_snippets_run_in_terminal)).assertIsEnabled()

        onField(Res.string.lib_snippets_field_command).performTextInput("x")
        waitForIdle()

        // The reason is on the button as its state — the line below it is drawn for the eye only
        // (`clearAndSetSemantics`), so a reader hears it once, attached to the control it explains.
        onNodeWithText(string(Res.string.lib_snippets_run_in_terminal))
            .assertIsNotEnabled()
            .assert(hasStateDescription(string(Res.string.lib_snippets_run_needs_save)))
        onNodeWithText(string(Res.string.lib_snippets_run_in_terminal))
            .performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
        assertNull(shell.snippets.pendingRun, "the sheet ran a command it was not showing")
    }

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.NEW_SNIPPET).performClick()
        waitForIdle()
    }
}

private const val NAME = "disk usage"
private const val COMMAND = "df -h"
