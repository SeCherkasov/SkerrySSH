package app.skerry.ui.help

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.skerry.ui.app.DesktopView
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.help_add
import app.skerry.ui.generated.resources.help_close
import app.skerry.ui.generated.resources.vault_help_intro
import app.skerry.ui.runbook.RUNBOOK_HELP_EXAMPLES
import app.skerry.ui.snippet.SNIPPET_HELP_EXAMPLES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The help dialogs of the three library screens: they open from the header, an example lands in
 * the library on one click and only once, and Close puts the screen back.
 */
@OptIn(ExperimentalTestApi::class)
class HelpDialogTest {

    @Test
    fun `a snippet example is added from the help dialog on one click`() = runDesktopShell { shell ->
        openView(DesktopView.Snippets)
        openHelp()

        val example = SNIPPET_HELP_EXAMPLES.first()
        scrollHelpToExamples()
        onAllNodesWithText(string(Res.string.help_add))[0].performClick()
        waitForIdle()

        assertEquals(listOf(example.label), shell.snippets.snippets.map { it.snippet.label })

        // The button flipped to its inert "added" state: clicking the row again must not duplicate.
        onAllNodesWithText(string(Res.string.help_add)).fetchSemanticsNodes().let { remaining ->
            assertEquals(SNIPPET_HELP_EXAMPLES.size - 1, remaining.size)
        }

        closeHelp()
        // Twice on screen once added: the library row and the run panel that selected it.
        onAllNodesWithText(example.label).onFirst().assertIsDisplayed()
    }

    @Test
    fun `a runbook example is added from the help dialog on one click`() = runDesktopShell { shell ->
        openView(DesktopView.Runbooks)
        openHelp()
        scrollHelpToExamples()

        onAllNodesWithText(string(Res.string.help_add))[0].performClick()
        waitForIdle()

        val example = RUNBOOK_HELP_EXAMPLES.first()
        val saved = shell.runbooks.runbooks.single().runbook
        assertEquals(example.label, saved.label)
        assertEquals(example.steps.size, saved.steps.size)
        assertTrue(saved.steps.all { it.id.isNotBlank() }, "the manager assigns step ids")

        closeHelp()
        onAllNodesWithText(example.label).onFirst().assertIsDisplayed()
    }

    @Test
    fun `the vault help explains without offering anything to add`() = runDesktopShell {
        openView(DesktopView.Vault)
        openHelp()

        onNodeWithText(string(Res.string.vault_help_intro)).assertIsDisplayed()
        onAllNodesWithText(string(Res.string.help_add)).fetchSemanticsNodes().let {
            assertEquals(0, it.size, "the vault has no templated secrets to offer")
        }

        closeHelp()
    }

    private fun ComposeUiTest.openView(view: DesktopView) {
        onNodeWithTag(UiTags.railView(view)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openHelp() {
        onNodeWithTag(UiTags.HELP).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.HELP_DIALOG).assertIsDisplayed()
    }

    /**
     * The examples sit below the dialog's scroll viewport once the variables reference is in the
     * content; a click on a fully clipped node lands clamped somewhere else instead of failing,
     * and performScrollTo is a no-op there (clipped bounds are zero, so the delta is zero).
     */
    private fun ComposeUiTest.scrollHelpToExamples() {
        onNodeWithTag(UiTags.HELP_DIALOG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 2000f) }
        waitForIdle()
    }

    private fun ComposeUiTest.closeHelp() {
        onNodeWithText(string(Res.string.help_close)).performClick()
        waitForIdle()
    }
}
