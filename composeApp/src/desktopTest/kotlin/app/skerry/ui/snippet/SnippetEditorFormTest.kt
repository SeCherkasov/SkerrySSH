package app.skerry.ui.snippet

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_field_command
import app.skerry.ui.generated.resources.lib_snippets_field_name
import app.skerry.ui.generated.resources.lib_snippets_field_notes
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.conn_create
import app.skerry.ui.generated.resources.conn_group_new
import app.skerry.ui.generated.resources.conn_group_none
import app.skerry.ui.generated.resources.shell_group_name_placeholder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The snippet editor: reached from the rail, filled in, saved into the library.
 *
 * A snippet is a command the user will later run against a live host, so "what was typed is what
 * got stored" is the whole point of the form — [SnippetManagerTest] covers the store, this covers
 * the two fields feeding it.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetEditorFormTest {

    @Test
    fun `a snippet typed into the editor lands in the library`() = runDesktopShell { shell ->
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.snippets.snippets.singleOrNull { it.snippet.label == NAME }
        assertNotNull(saved, "the editor saved nothing")
        assertEquals(COMMAND, saved.snippet.command)
    }

    /** A snippet with no command is not a snippet: there would be nothing to run. */
    @Test
    fun `save is refused until both the name and the command are there`() = runDesktopShell {
        openEditor()
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
    }

    @Test
    fun `cancelling the editor writes nothing`() = runDesktopShell { shell ->
        val before = shell.snippets.snippets.size
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()

        assertEquals(before, shell.snippets.snippets.size)
        assertTrue(shell.snippets.snippets.none { it.snippet.label == NAME })
    }

    @Test
    fun `a snippet with notes saves notes into the library`() = runDesktopShell { shell ->
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onField(Res.string.lib_snippets_field_notes).performTextInput("Sorts partitions by space used")
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.snippets.snippets.singleOrNull { it.snippet.label == NAME }
        assertNotNull(saved, "the editor saved nothing")
        assertEquals("Sorts partitions by space used", saved.snippet.notes)
    }

    /**
     * The picker itself is proved in `GroupSelectFormTest`; what this pins is the line that wires it
     * into the editor. Dropping it leaves a select that opens, offers folders and changes nothing —
     * a fault no test that stubs the field can see.
     */
    @Test
    fun `a folder created in the editor lands on the snippet`() = runDesktopShell { shell ->
        openEditor()
        onField(Res.string.lib_snippets_field_name).performTextInput(NAME)
        onField(Res.string.lib_snippets_field_command).performTextInput(COMMAND)
        onNodeWithText(string(Res.string.conn_group_none)).performClick()
        onNodeWithText(string(Res.string.conn_group_new)).performClick()
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_group_name_placeholder)).performTextInput(FOLDER)
        onNodeWithText(string(Res.string.conn_create)).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        val saved = shell.snippets.snippets.singleOrNull { it.snippet.label == NAME }
        assertNotNull(saved, "the editor saved nothing")
        assertEquals(FOLDER, saved.snippet.group, "the folder picked in the editor never reached the record")
    }

    private fun ComposeUiTest.openEditor() {
        onNodeWithTag(UiTags.railView(DesktopView.Snippets)).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.NEW_SNIPPET).performClick()
        waitForIdle()
    }
}

private const val NAME = "disk usage"
private const val COMMAND = "df -h | sort -k5 -r"
private const val FOLDER = "Production"
