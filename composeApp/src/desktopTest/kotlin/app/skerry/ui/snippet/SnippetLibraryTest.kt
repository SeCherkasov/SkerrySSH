package app.skerry.ui.snippet

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTextExactly
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.FakeShellInput
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_chip_all
import app.skerry.ui.generated.resources.lib_snippets_delete
import app.skerry.ui.generated.resources.lib_snippets_run
import app.skerry.ui.generated.resources.lib_snippets_search
import app.skerry.ui.generated.resources.lib_snippets_starter_pack
import app.skerry.ui.generated.resources.shell_group_rename_subtitle
import app.skerry.ui.generated.resources.shtail_group_rename
import app.skerry.ui.generated.resources.shtail_group_rename_records
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.skerry.ui.design.tagChipLabel

/**
 * The snippet library as a list you act on: filling it, narrowing it, running a line into a session
 * and deleting one.
 *
 * The filtering arithmetic is covered as a pure function ([app.skerry.ui.snippet.filterSnippets]);
 * what no test reached is whether Run reaches the terminal at all. A snippet that silently goes
 * nowhere is indistinguishable from a command that printed nothing, which is exactly why the assert
 * here is on what the session was sent ([FakeShellInput]) and not on the panel's own state.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetLibraryTest {

    @Test
    fun `the starter pack fills an empty library`() = runDesktopShell { shell ->
        openSnippets()
        assertTrue(shell.snippets.snippets.isEmpty(), "the shell seeds an empty library")

        onNodeWithText(string(Res.string.lib_snippets_starter_pack)).performClick()
        waitForIdle()

        assertEquals(STARTER_SNIPPETS.size, shell.snippets.snippets.size)
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).assertIsDisplayed()
    }

    @Test
    fun `search narrows the library to what matches`() = runDesktopShell {
        openSnippets()
        installStarterPack()

        search("docker")
        onSnippetRow(DOCKER_SNIPPET, DOCKER_COMMAND).assertIsDisplayed()
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).assertDoesNotExist()

        search("")
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).assertIsDisplayed()
    }

    /** A tag chip is the other filter, and the two are meant to compose rather than replace. */
    @Test
    fun `a tag chip narrows the library and All puts it back`() = runDesktopShell {
        openSnippets()
        installStarterPack()

        // The chip says only its tag; a row that carries the tag says its command too.
        onNode(hasTextExactly(tagChipLabel(DB_TAG))).performClick()
        waitForIdle()
        onSnippetRow(PSQL_SNIPPET, PSQL_COMMAND).assertIsDisplayed()
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).assertDoesNotExist()

        onNode(hasTextExactly(string(Res.string.lib_snippets_chip_all))).performClick()
        waitForIdle()
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).assertIsDisplayed()
    }

    /** The point of the section: a saved line arriving in the session that is open. */
    @Test
    fun `running a snippet sends its command into the session`() = runDesktopShell {
        openSnippets()
        installStarterPack()
        FakeShellInput.clear()

        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.lib_snippets_run)).performClick()
        waitUntil { FakeShellInput.all().any { it.contains(DISK_COMMAND) } }
    }

    @Test
    fun `deleting a snippet takes its row with it`() = runDesktopShell { shell ->
        openSnippets()
        installStarterPack()
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.lib_snippets_delete)).performClick()
        waitForIdle()

        assertNull(shell.snippets.snippets.firstOrNull { it.snippet.label == DISK_SNIPPET })
        onSnippetRow(DISK_SNIPPET, DISK_COMMAND).assertDoesNotExist()
    }

    /**
     * The folder header's pencil opens the host sidebar's own dialog, and its subtitle used to say
     * the group's *hosts* move with the name. A library folder holds no hosts, and the copy is the
     * only thing telling the two apart.
     */
    @Test
    fun `the rename dialog opened from a library folder does not talk about hosts`() = runDesktopShell { shell ->
        openSnippets()
        shell.snippets.save(SnippetDraft(label = DISK_SNIPPET, command = DISK_COMMAND, group = OPS_GROUP))
        waitForIdle()

        onNodeWithContentDescription(string(Res.string.shtail_group_rename, OPS_GROUP)).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.shtail_group_rename_records)).assertIsDisplayed()
        onNodeWithText(string(Res.string.shell_group_rename_subtitle)).assertDoesNotExist()
    }

    private fun ComposeUiTest.openSnippets() {
        onNodeWithTag(UiTags.railView(DesktopView.Snippets)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.installStarterPack() {
        onNodeWithText(string(Res.string.lib_snippets_starter_pack)).performClick()
        waitForIdle()
    }

    /**
     * A library row, told apart by the command drawn under the name: the run panel echoes the name
     * of whatever is selected, so the name alone matches twice.
     */
    private fun ComposeUiTest.onSnippetRow(label: String, command: String): SemanticsNodeInteraction =
        onNode(hasText(label) and hasText(command))

    private fun ComposeUiTest.search(query: String) {
        onNodeWithContentDescription(string(Res.string.lib_snippets_search)).performTextReplacement(query)
        waitForIdle()
    }
}

// From [STARTER_SNIPPETS] — user data, so these are the literal English labels the pack installs.
private const val DISK_SNIPPET = "Disk usage"
private const val DISK_COMMAND = "df -h"
private const val DOCKER_SNIPPET = "Running containers"
private const val DOCKER_COMMAND = "docker ps"
private const val PSQL_SNIPPET = "PostgreSQL shell"
private const val PSQL_COMMAND = "psql -U postgres"
private const val DB_TAG = "db"

/** A folder to file a snippet under — any name a user could type. */
private const val OPS_GROUP = "Ops"
