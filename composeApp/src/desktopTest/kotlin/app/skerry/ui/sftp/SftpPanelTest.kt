package app.skerry.ui.sftp

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onScreen
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.design.uppercaseForLocale
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.sftp_col_modified
import app.skerry.ui.generated.resources.sftp_columns
import app.skerry.ui.generated.resources.shell_tip_files
import app.skerry.ui.session.SessionView
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The file panel of a live session: getting into it, walking the remote tree, and the column
 * settings that decide what a row shows.
 *
 * The pane controller is covered as state; what a click reaches is not. Opening the panel on the
 * wrong session, or a directory row that puts the cursor somewhere but never enters, are both
 * invisible to a state test and immediately obvious here.
 *
 * The remote side answers from the fake SFTP client ([app.skerry.ui.desktop.FakeSftpClient]) — one
 * canned `/var/www` listing, no server. The local side is the real filesystem, which is why nothing
 * here asserts about it.
 */
@OptIn(ExperimentalTestApi::class)
class SftpPanelTest {

    @Test
    fun `the files button opens the remote listing of the active session`() = runDesktopShell {
        openFiles()
        onScreen(UiTags.screen(SessionView.Sftp)).assertIsDisplayed()
        onNodeWithText(REMOTE_FILE).assertIsDisplayed()
        onNodeWithText(REMOTE_ROOT).assertIsDisplayed()
    }

    /** A single click only moves the cursor; entering a directory is the double click. */
    @Test
    fun `double-clicking a directory takes the pane into it`() = runDesktopShell {
        openFiles()
        onNodeWithText(REMOTE_DIR).performClick()
        waitForIdle()
        onNodeWithText(REMOTE_ROOT).assertIsDisplayed()

        onNodeWithText(REMOTE_DIR).performMouseInput { doubleClick() }
        waitUntil { onAllNodesWithText("$REMOTE_ROOT/$REMOTE_DIR").fetchSemanticsNodes().isNotEmpty() }

        // And back out the same way, from the parent row — the local pane has one too, so this is
        // the one sitting next to the remote listing.
        onNode(hasText(PARENT_ROW) and hasAnySibling(hasText(REMOTE_FILE))).performMouseInput { doubleClick() }
        waitUntil { onAllNodesWithText(REMOTE_ROOT).fetchSemanticsNodes().isNotEmpty() }
    }

    /** One setting for both panes, so the column has to leave both of them. */
    @Test
    fun `the columns menu takes a column out of both listings`() = runDesktopShell {
        openFiles()
        val header = uppercaseForLocale(string(Res.string.sftp_col_modified), LOCALE)
        assertEquals(2, columnHeaders(header), "each pane heads its own listing")

        onNodeWithContentDescription(string(Res.string.sftp_columns)).performClick()
        waitForIdle()
        onNodeWithContentDescription(string(Res.string.sftp_col_modified)).assertIsOn().performClick()
        waitForIdle()

        assertEquals(0, columnHeaders(header))
    }

    private fun ComposeUiTest.columnHeaders(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun ComposeUiTest.openFiles() {
        onNodeWithContentDescription(string(Res.string.shell_tip_files)).performClick()
        waitForIdle()
    }
}

// The listing uppercases its column captions the locale-aware way ([uppercaseForLocale]); the run's
// locale is whatever the machine has, and only Turkish/Azeri differ from a plain uppercase.
private const val LOCALE = "en"

// The fake client's canned listing, and the path it reports for it.
private const val REMOTE_ROOT = "/var/www"
private const val REMOTE_DIR = "html"
private const val REMOTE_FILE = "nginx.conf"
private const val PARENT_ROW = ".."
