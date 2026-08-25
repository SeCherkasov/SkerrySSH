package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.skerry.shared.ssh.SshAuth
import app.skerry.ui.app.MobileRoute
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.connection.toTarget
import app.skerry.ui.desktop.runMobileShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.lib_snippets_screen_title
import app.skerry.ui.generated.resources.term_header_menu
import app.skerry.ui.generated.resources.lib_snippets_run_title
import kotlin.test.Test

/**
 * The phone's half of what the desktop toolbar fixed: a sheet raised over a session must not come
 * back by itself when that session reconnects.
 *
 * The pane id survives a drop — the controller reconnects in place — so the flag behind the sheet
 * is not reset by anything, and its render guard only hides it while the terminal is gone. Auto
 * reconnect then brings the sheet up over the shell the user just got their caret back in.
 */
@OptIn(ExperimentalTestApi::class)
class MobileSheetOutlivesSessionTest {

    @Test
    fun `a sheet open when the session dropped does not come back with it`() = runMobileShell(withSessions = true) { shell ->
        val sessions = requireNotNull(shell.sessions)
        shell.state.push(MobileRoute.Terminal)
        waitForIdle()

        onNodeWithContentDescription(string(Res.string.term_header_menu)).performClick()
        waitForIdle()
        onNodeWithText(string(Res.string.lib_snippets_screen_title)).performClick()
        val placeholder = string(Res.string.lib_snippets_run_title)
        waitUntil("the snippet sheet to open", timeoutMillis = 10_000) {
            onAllNodesWithText(placeholder).fetchSemanticsNodes().isNotEmpty()
        }

        val controller = sessions.active!!.focusedPane.controller
        controller.disconnect()
        waitUntil("the sheet to go with the session", timeoutMillis = 10_000) {
            onAllNodesWithText(placeholder).fetchSemanticsNodes().isEmpty()
        }

        val host = shell.hosts.hosts.first()
        controller.connect(host.toTarget(), SshAuth.Password(""))
        waitUntil("the session to come back", timeoutMillis = 10_000) {
            controller.uiState is ConnectionUiState.Connected
        }
        waitForIdle()
        onNodeWithText(placeholder).assertDoesNotExist()
        // The screen itself is still there — this is about the sheet, not the terminal.
        onNodeWithContentDescription(string(Res.string.term_header_menu)).assertIsDisplayed()
    }
}
