package app.skerry.ui.terminal

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.FakeShellInput
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.guard_prod_command_title
import app.skerry.ui.generated.resources.lib_snippets_run
import app.skerry.ui.snippet.SnippetDraft
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The production guard where it actually has to work: between a command the user launched and the
 * session it would run in.
 *
 * The classifier has its own tests (which lines are risky) and so does the hold. What neither can
 * see is whether the guard is armed for the session on screen and whether the dialog's buttons are
 * wired to the held command — and there the failure mode is `rm -rf` reaching a production host with
 * no question asked.
 *
 * The seeded shell's active tab is the production host of the demo catalog, and its session runs
 * over the fake transport, so a confirmed command lands in [FakeShellInput] and nowhere else.
 */
@OptIn(ExperimentalTestApi::class)
class ProductionGuardWiringTest {

    @Test
    fun `a risky snippet is held until it is confirmed`() = runDesktopShell { shell ->
        shell.seedRiskySnippet()
        openSnippets()
        FakeShellInput.clear()

        runSelectedSnippet()
        onNodeWithText(string(Res.string.guard_prod_command_title)).assertIsDisplayed()
        assertTrue(FakeShellInput.all().none { it.contains(RISKY_COMMAND) }, "the guard must hold the command, not trail it")

        // By tag, not by caption: the guard's confirm button reads "Run" in English and so does the
        // snippet library's own Run button underneath it, so a text lookup finds two nodes.
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitUntil { FakeShellInput.all().any { it.contains(RISKY_COMMAND) } }
    }

    @Test
    fun `a dismissed guard sends nothing`() = runDesktopShell { shell ->
        shell.seedRiskySnippet()
        openSnippets()
        FakeShellInput.clear()

        runSelectedSnippet()
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.guard_prod_command_title)).assertDoesNotExist()
        assertTrue(FakeShellInput.all().none { it.contains(RISKY_COMMAND) })
    }

    private fun ComposeUiTest.openSnippets() {
        onNodeWithTag(UiTags.railView(DesktopView.Snippets)).performClick()
        waitForIdle()
    }

    /** The library holds one snippet, so it is the one the panel has selected. */
    private fun ComposeUiTest.runSelectedSnippet() {
        onNodeWithText(string(Res.string.lib_snippets_run)).performClick()
        waitForIdle()
    }

    private fun DesktopShell.seedRiskySnippet() {
        snippets.save(SnippetDraft(label = "Wipe the build tree", command = RISKY_COMMAND))
    }
}

private const val RISKY_COMMAND = "rm -rf /var/tmp/build"
