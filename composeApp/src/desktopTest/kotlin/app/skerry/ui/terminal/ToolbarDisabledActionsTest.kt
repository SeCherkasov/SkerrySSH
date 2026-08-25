package app.skerry.ui.terminal

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.app.UiTags
import app.skerry.ui.connection.toTarget
import app.skerry.ui.desktop.clickIconWhenEnabled
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.runbook_palette_placeholder
import app.skerry.ui.generated.resources.runbook_toolbar_tip
import app.skerry.ui.generated.resources.share_session
import app.skerry.ui.generated.resources.shell_tip_add_pane
import app.skerry.ui.generated.resources.shell_tip_record
import app.skerry.ui.generated.resources.shell_tip_play
import app.skerry.ui.generated.resources.shell_tip_snippets
import app.skerry.ui.generated.resources.term_run_snippet_placeholder
import app.skerry.ui.session.MAX_PANES
import kotlin.test.Test

/**
 * A toolbar action with nothing to act on has to be a disabled button, not a lit one that drops the
 * press.
 *
 * The palettes and the add-pane button drew themselves faint and kept their click action, guarding
 * inside the handler instead: the press landed, took focus, and nothing happened — no palette, no
 * reason, and a screen reader still announcing a button. It is the same gap the palette tests kept
 * tripping over, since a click sent one frame before the toolbar caught up with the connection is
 * swallowed in exactly this way.
 */
@OptIn(ExperimentalTestApi::class)
class ToolbarDisabledActionsTest {

    @Test
    fun `the palettes are disabled with no session to run into`() = runDesktopShell(withSessions = false) {
        onNodeWithContentDescription(string(Res.string.runbook_toolbar_tip)).assertIsNotEnabled()
        toolbarButton(string(Res.string.shell_tip_snippets)).assertIsNotEnabled()
        toolbarButton(string(Res.string.shell_tip_record)).assertIsNotEnabled()
    }

    /**
     * The other half of the contract, and the one that keeps the fix honest: disabling the button
     * for good would satisfy the test above on its own. Waited for rather than asserted on the spot
     * — the seeded session connects on a background scope, and the toolbar learns about it a frame
     * later.
     */
    @Test
    fun `the palettes become buttons once a session is connected`() = runDesktopShell {
        waitUntil("the palettes to become clickable", timeoutMillis = 10_000) {
            onAllNodes(isEnabled() and hasContentDescription(string(Res.string.runbook_toolbar_tip)))
                .fetchSemanticsNodes().isNotEmpty()
        }
        toolbarButton(string(Res.string.shell_tip_snippets)).assertIsEnabled()
        toolbarButton(string(Res.string.shell_tip_record)).assertIsEnabled()
    }

    /**
     * An open palette that outlives its session is worse than a stale panel: the popup is hidden
     * while the pane is down, but the flag behind it stays set, so an auto-reconnect brings the
     * palette back unasked — and it is focusable, so it takes the caret out of the shell the user
     * just got back. Driven on the pane's own controller, the way a reconnect goes: replacing the
     * pane would re-key the button's state and hide a missing reset instead of proving it.
     */
    @Test
    fun `a palette open when the session dropped does not come back with it`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val placeholder = string(Res.string.term_run_snippet_placeholder)
        val snippets = string(Res.string.shell_tip_snippets)
        clickIconWhenEnabled(snippets, shell)
        waitUntil("the snippet palette to open", timeoutMillis = 10_000) {
            onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty()
        }

        val controller = sessions.active!!.focusedPane.controller
        controller.disconnect()
        waitUntil("the palette to go with the session", timeoutMillis = 10_000) {
            onAllNodesWithText(placeholder).fetchSemanticsNodes().isEmpty()
        }

        val host = shell.hosts.hosts.first()
        controller.connect(host.toTarget(), SshAuth.Password(""))
        waitUntil("the pane to come back", timeoutMillis = 10_000) {
            onAllNodes(isEnabled() and hasContentDescription(snippets) and !isSelectable())
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(placeholder).assertDoesNotExist()
    }

    /**
     * The same contract for the runbook palette, which has one more moving part than the snippet
     * one: its button doubles as the way back to a run in progress, so the reset has to sit ahead
     * of that branch rather than inside the palette half of it.
     */
    @Test
    fun `the runbook palette does not come back with the session either`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val placeholder = string(Res.string.runbook_palette_placeholder)
        val runbooks = string(Res.string.runbook_toolbar_tip)
        clickIconWhenEnabled(runbooks, shell)
        waitUntil("the runbook palette to open", timeoutMillis = 10_000) {
            onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty()
        }

        val controller = sessions.active!!.focusedPane.controller
        controller.disconnect()
        waitUntil("the palette to go with the session", timeoutMillis = 10_000) {
            onAllNodesWithText(placeholder).fetchSemanticsNodes().isEmpty()
        }

        val host = shell.hosts.hosts.first()
        controller.connect(host.toTarget(), SshAuth.Password(""))
        waitUntil("the pane to come back", timeoutMillis = 10_000) {
            onAllNodes(isEnabled() and hasContentDescription(runbooks) and !isSelectable())
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(placeholder).assertDoesNotExist()
    }

    @Test
    fun `add pane is disabled once the tab is full`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        repeat(MAX_PANES - 1) { sessions.addPane() }
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.shell_tip_add_pane)).assertIsNotEnabled()
    }

    /**
     * The tooltip is the only name an icon button has, and `clickable` stops emitting hover the
     * moment it is disabled — so disabling the button is exactly where its name is easiest to lose.
     */
    @Test
    fun `a disabled action still names itself under the pointer`() = runDesktopShell(withSessions = false) {
        val name = string(Res.string.runbook_toolbar_tip)
        onNodeWithContentDescription(name).assertIsNotEnabled().performMouseInput { moveTo(center) }
        waitUntil("the tooltip of the disabled button", timeoutMillis = 10_000) {
            onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * The row and the overflow menu are two renderings of one action set, and the menu is the one a
     * narrow window leaves the user: a row that fires a request nothing acts on costs the press and
     * the menu both.
     */
    @Test
    fun `the overflow menu refuses what the row would refuse`() = runDesktopShell(withSessions = false, windowWidth = 560) {
        onNodeWithTag(UiTags.TOOLBAR_OVERFLOW).performClick()
        waitUntil("the overflow menu to open", timeoutMillis = 10_000) {
            onAllNodesWithText(string(Res.string.shell_tip_snippets)).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(string(Res.string.shell_tip_snippets)).assertIsNotEnabled()
        onNodeWithText(string(Res.string.runbook_toolbar_tip)).assertIsNotEnabled()
        // Share is gated on the session too, and by a rule of its own: a running stream keeps the
        // control that stops it, so the row cannot simply read `terminal != null`.
        onNodeWithText(string(Res.string.share_session)).assertIsNotEnabled()
        // Not everything in the menu is session-scoped: the recording player opens from a file.
        onNodeWithText(string(Res.string.shell_tip_play)).assertIsEnabled()
    }

    /**
     * The nav rail's Snippets entry carries the same name as the toolbar's button, and only the rail
     * one is a selectable navigation target.
     */
    private fun ComposeUiTest.toolbarButton(name: String) =
        onNode(hasContentDescription(name) and !isSelectable())
}
