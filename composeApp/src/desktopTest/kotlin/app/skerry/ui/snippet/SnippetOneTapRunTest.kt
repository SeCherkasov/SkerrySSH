package app.skerry.ui.snippet

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_run_title
import app.skerry.ui.generated.resources.shell_tip_snippets
import kotlin.test.Test
import kotlin.test.assertNotNull
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.generated.resources.term_header_menu
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import androidx.compose.ui.test.onNodeWithContentDescription
import app.skerry.ui.app.MobileRoute
import androidx.compose.ui.test.onAllNodesWithContentDescription
import app.skerry.ui.generated.resources.shell_tip_more_actions
import app.skerry.ui.generated.resources.term_menu_run_snippet

/**
 * The gate itself is pinned on the manager ([SnippetManagerTest]); what is pinned here is that the
 * surface asks for it. Reverted at the call site the manager keeps its branch and every unit test
 * stays green, while the palette goes back to sending a command it showed two lines of.
 */
@OptIn(ExperimentalTestApi::class)
class SnippetOneTapRunTest {

    @Test
    fun `the palette confirms a command its row cannot show whole`() = runDesktopShell { shell ->
        shell.snippets.save(SnippetDraft(label = "Rollout", command = "echo " + "x".repeat(300)))
        waitForIdle()

        // The nav rail's Snippets destination carries the same name — the one wanted here is the
        // toolbar button over the terminal, which has no rail tag.
        onNode(
            hasContentDescription(string(Res.string.shell_tip_snippets)) and
                !hasTestTag("nav.rail.view.Snippets"),
        ).performClick()
        waitForIdle()
        onNodeWithText("Rollout").performClick()
        waitForIdle()

        assertNotNull(shell.snippets.pendingRun, "the row sent a command it only showed part of")
        onNodeWithText(string(Res.string.lib_snippets_run_title)).assertIsDisplayed()
    }

    /**
     * And the phone's own one-tap surface: the run sheet behind the terminal's menu. Its card draws
     * three lines where the palette draws two, so it sends the same commands and owes the same gate.
     */
    @Test
    fun `the phone's run sheet confirms a command its card cannot show whole`() =
        runMobileShell(withSessions = true) { shell ->
            shell.snippets.save(SnippetDraft(label = "Rollout", command = "echo " + "x".repeat(300)))
            shell.state.push(MobileRoute.Terminal)
            waitForIdle()

            onNodeWithContentDescription(string(Res.string.term_header_menu)).performClick()
            waitForIdle()
            onNodeWithText(string(Res.string.lib_snippets_screen_title)).performClick()
            waitForIdle()
            onNodeWithText("Rollout").performClick()
            waitForIdle()

            assertNotNull(shell.snippets.pendingRun, "the card sent a command it only showed part of")
        }

    /**
     * And the third: the picker on a host row, which runs a snippet on a host with no session open.
     * Its rows are the palette's, so it shows the same two lines and owes the same gate.
     */
    @Test
    fun `the host row's picker confirms a command its row cannot show whole`() = runDesktopShell { shell ->
        shell.snippets.save(SnippetDraft(label = "Rollout", command = "echo " + "x".repeat(300)))
        waitForIdle()

        onAllNodesWithContentDescription(string(Res.string.shell_tip_more_actions))[0].performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.term_menu_run_snippet)).performClick()
        waitForIdle()
        onNodeWithText("Rollout").performClick()
        waitForIdle()

        assertNotNull(shell.snippets.pendingRun, "the host row sent a command it only showed part of")
    }
}
