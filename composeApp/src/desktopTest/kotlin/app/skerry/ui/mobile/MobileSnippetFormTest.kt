package app.skerry.ui.mobile

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    /** Same rule as the desktop editor: a snippet with no command has nothing to run. */
    @Test
    fun `a snippet with no command is not saved`() = runMobileShell { shell ->
        shell.state.push(MobileRoute.Snippets)
        waitForIdle()
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        assertNull(shell.snippets.snippets.firstOrNull { it.snippet.label == NAME })
    }

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.NEW_SNIPPET).performClick()
        waitForIdle()
    }
}

private const val NAME = "disk usage"
private const val COMMAND = "df -h"
