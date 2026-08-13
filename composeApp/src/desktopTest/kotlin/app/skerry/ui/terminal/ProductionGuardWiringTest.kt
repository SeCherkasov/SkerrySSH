package app.skerry.ui.terminal

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
        awaitGuardDialog()
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
        awaitGuardDialog()
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()

        onNodeWithText(string(Res.string.guard_prod_command_title)).assertDoesNotExist()
        assertTrue(FakeShellInput.all().none { it.contains(RISKY_COMMAND) })
    }

    /**
     * The dialog quotes the command so the user can read what is about to run, and Confirm replays
     * the whole block that was held — not only the line that tripped the guard. A snippet is an
     * ordinary way to send several lines at once, and the ones under the risky line ran unseen.
     */
    @Test
    fun `the dialog shows every line the confirmation will run`() = runDesktopShell { shell ->
        shell.seedRiskySnippet(MULTILINE_COMMAND)
        openSnippets()
        FakeShellInput.clear()

        // Counted rather than matched: the library row behind the scrim quotes the snippet too, so
        // what the dialog has to add is one more node carrying the line under the risky one.
        val quotedBefore = onAllNodesWithText(TRAILING_LINE, substring = true).fetchSemanticsNodes().size
        runSelectedSnippet()
        // Waited for rather than asserted at once: the seeded session connects on its own coroutine,
        // and the guard is armed for the pane only once it is live.
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText(string(Res.string.guard_prod_command_title)).fetchSemanticsNodes().isNotEmpty() }
        val quotedAfter = onAllNodesWithText(TRAILING_LINE, substring = true).fetchSemanticsNodes().size
        assertTrue(quotedAfter > quotedBefore, "the dialog quoted only the line that tripped the guard")
    }

    /**
     * The snippet reaches the guard through the session's own coroutine, so the dialog is one frame
     * behind the click that ran it — asserting straight away is a race, and it lost once here.
     */
    private fun ComposeUiTest.awaitGuardDialog() {
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText(string(Res.string.guard_prod_command_title)).fetchSemanticsNodes().isNotEmpty() }
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

    private fun DesktopShell.seedRiskySnippet(command: String = RISKY_COMMAND) {
        snippets.save(SnippetDraft(label = "Wipe the build tree", command = command))
    }
}

private const val RISKY_COMMAND = "rm -rf /var/tmp/build"

/** What runs after the risky line — held with it, replayed with it, and never quoted. */
private const val TRAILING_LINE = "chown -R nobody:nogroup /srv/www"
private const val MULTILINE_COMMAND = "$RISKY_COMMAND\n$TRAILING_LINE"

/** The default second is not enough for a snippet to reach the guard on a loaded CI box. */
private const val WAIT_MS = 5_000L
