package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.terminal_search_no_matches
import app.skerry.ui.generated.resources.terminal_search_placeholder
import kotlin.test.Test

/**
 * Searching the terminal's scrollback from the find bar.
 *
 * The matcher is covered as a pure search over the grid. This is the bar around it: that the chord
 * opens it, that what is typed reaches the search rather than the PTY, and that a query with nothing
 * behind it says so instead of silently showing the last hit.
 *
 * The session's output is the fake channel's canned banner, which is what the queries below are
 * looking for.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalSearchTest {

    @Test
    fun `the chord opens the find bar over the session`() = runDesktopShell {
        openSearch()
        searchField().assertIsDisplayed()
    }

    @Test
    fun `a query with no hits says so`() = runDesktopShell {
        openSearch()
        searchField().performTextReplacement(MISSING_TERM)
        waitForIdle()
        onNodeWithText(string(Res.string.terminal_search_no_matches)).assertIsDisplayed()

        searchField().performTextReplacement(PRESENT_TERM)
        waitForIdle()
        onNodeWithText(string(Res.string.terminal_search_no_matches)).assertDoesNotExist()
    }

    /** Esc is the find bar's own key: it closes the bar rather than reaching the session. */
    @Test
    fun `escape closes the find bar`() = runDesktopShell {
        openSearch()
        searchField().performTextReplacement(PRESENT_TERM)
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        searchField().assertDoesNotExist()
    }

    private fun ComposeUiTest.openSearch() {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } } }
        waitForIdle()
    }

    /** The bar has no caption; the field names itself by what it says it searches. */
    private fun ComposeUiTest.searchField() =
        onNodeWithContentDescription(string(Res.string.terminal_search_placeholder))
}

// The fake channel's banner: one is in it, the other is not.
private const val PRESENT_TERM = "deploy"
private const val MISSING_TERM = "zzzznotinthebanner"
