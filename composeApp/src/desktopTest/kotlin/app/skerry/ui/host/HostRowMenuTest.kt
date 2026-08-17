package app.skerry.ui.host

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onCatalog
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.conn_field_name
import app.skerry.ui.host.HostSection
import app.skerry.ui.desktop.onScreen
import app.skerry.ui.generated.resources.shell_delete
import app.skerry.ui.generated.resources.shell_tip_more_actions
import app.skerry.ui.generated.resources.term_menu_delete
import app.skerry.ui.generated.resources.term_menu_duplicate
import app.skerry.ui.generated.resources.term_menu_run_snippet
import app.skerry.ui.generated.resources.term_menu_edit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The "⋮" on a catalog row — how an existing host is actually edited, duplicated or deleted.
 *
 * Everything reachable from this menu has a test of the thing it opens: the editor form, the delete
 * confirmation, the duplicate. What none of them covers is the menu itself, and it is the only way
 * in: the editor tests reach the form through "New connection" instead, so an Edit item wired to
 * nothing would leave every one of them green.
 */
@OptIn(ExperimentalTestApi::class)
class HostRowMenuTest {

    @Test
    fun `edit opens the row's own host in the editor`() = runDesktopShell { shell ->
        openMenu()
        onNodeWithText(string(Res.string.term_menu_edit)).performClick()
        waitForIdle()

        onNodeWithTag(UiTags.FORM_SAVE).assertIsDisplayed()
        onField(Res.string.conn_field_name).performTextReplacement(RENAMED)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        assertNotNull(shell.hosts.hosts.singleOrNull { it.label == RENAMED }, "the edit never reached the host")
    }

    /** Duplicate opens the form on a copy rather than saving one: the name still has to be free. */
    @Test
    fun `duplicate opens a copy that saves as a second host`() = runDesktopShell { shell ->
        val before = shell.hosts.hosts.size
        openMenu()
        onNodeWithText(string(Res.string.term_menu_duplicate)).performClick()
        waitForIdle()
        assertEquals(before, shell.hosts.hosts.size, "duplicate saved before the form was confirmed")

        onField(Res.string.conn_field_name).performTextReplacement(COPY)
        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()

        assertEquals(before + 1, shell.hosts.hosts.size)
        assertNotNull(shell.hosts.hosts.singleOrNull { it.label == FIRST_HOST }, "the original was renamed or lost")
        assertNotNull(shell.hosts.hosts.singleOrNull { it.label == COPY })
    }

    /** Deleting from the menu still asks: the confirmation is not something the menu bypasses. */
    @Test
    fun `delete asks first and then removes`() = runDesktopShell { shell ->
        openMenu()
        onNodeWithText(string(Res.string.term_menu_delete)).performClick()
        waitForIdle()
        assertNotNull(shell.hosts.hosts.singleOrNull { it.label == FIRST_HOST }, "the menu deleted without asking")

        onNode(hasText(string(Res.string.shell_delete)) and hasTestTag(UiTags.FORM_SAVE)).performClick()
        waitForIdle()
        assertNull(shell.hosts.hosts.singleOrNull { it.label == FIRST_HOST })
    }

    /**
     * A snippet is a line typed into a shell, and a remote desktop has none: offering to run one on
     * a framebuffer profile is a dead end — the row would open the desktop and the command would
     * have nowhere to go. The rest of the menu is a profile's, not a session's, so it stays.
     */
    @Test
    fun `a remote desktop row does not offer to run a snippet`() = runDesktopShell {
        onScreen(UiTags.railSection(HostSection.RemoteDesktops)).performClick()
        waitForIdle()
        openMenu(DESKTOP_HOST)

        onNodeWithText(string(Res.string.term_menu_run_snippet)).assertDoesNotExist()
        onNodeWithText(string(Res.string.term_menu_edit)).assertIsDisplayed()
        onNodeWithText(string(Res.string.term_menu_duplicate)).assertIsDisplayed()
        onNodeWithText(string(Res.string.term_menu_delete)).assertIsDisplayed()
    }

    /** The terminal side still offers it: that is where a snippet has somewhere to go. */
    @Test
    fun `a terminal host offers to run a snippet`() = runDesktopShell {
        openMenu()
        onNodeWithText(string(Res.string.term_menu_run_snippet)).assertIsDisplayed()
    }

    /**
     * Scoped twice over: to the sidebar, because the work bar draws the same host name, and to the
     * row itself, because every catalog row carries a button with this name.
     */
    private fun ComposeUiTest.openMenu(host: String = FIRST_HOST) {
        onCatalog(host).assertIsDisplayed()
        onNode(
            hasContentDescription(string(Res.string.shell_tip_more_actions)) and
                hasAnyAncestor(hasTestTag(UiTags.HOST_SIDEBAR)) and
                hasAnyAncestor(hasText(host)),
        ).performClick()
        waitForIdle()
    }

}

// The seeded catalog's first terminal host.
private const val FIRST_HOST = "prod-web-01"

/** A remote-desktop profile of the seeded catalog — see `seededHosts`. */
private const val DESKTOP_HOST = "win-bench"
private const val RENAMED = "prod-web-01-renamed"
private const val COPY = "prod-web-02"
