package app.skerry.ui.session

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.skerry.ui.desktop.DesktopShell
import app.skerry.ui.desktop.onTab
import app.skerry.ui.desktop.clickIconWhenEnabled
import app.skerry.ui.desktop.runDesktopShell
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_tip_add_pane
import app.skerry.ui.generated.resources.shell_tip_close_tab
import app.skerry.ui.generated.resources.shell_tip_new_tab
import app.skerry.ui.generated.resources.shell_tip_sync_panes
import app.skerry.ui.generated.resources.shtail_new_tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The title bar's session tabs and the panes inside one, driven by clicking them.
 *
 * [SessionsController] is covered as state ([SessionsControllerTest]) and the chip's own gestures
 * are covered as gestures ([app.skerry.ui.desktop.SessionTabMiddleClickTest]). Between the two sits
 * the wiring nobody was checking: that the chip on screen belongs to the tab it names, that ✕
 * closes *that* session, and that the work bar's split controls act on the tab in focus.
 *
 * The seeded shell opens two tabs over a fake transport, so there is a session to switch away from.
 */
@OptIn(ExperimentalTestApi::class)
class SessionTabsTest {

    @Test
    fun `clicking a tab makes it the active session`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val other = sessions.tabs.first { it.id != sessions.activeId }

        onTab(shell.chipTitle(other)).performClick()
        waitForIdle()
        assertEquals(other.id, sessions.activeId, "the chip must activate the tab it names")
    }

    @Test
    fun `the cross closes the tab it belongs to`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val active = sessions.tabs.first { it.id == sessions.activeId }
        val survivor = sessions.tabs.first { it.id != active.id }

        onNodeWithContentDescription(string(Res.string.shell_tip_close_tab, shell.chipTitle(active))).performClick()
        waitForIdle()
        assertEquals(listOf(survivor.id), sessions.tabs.map { it.id }, "only the closed tab may go")
        assertEquals(survivor.id, sessions.activeId, "closing the active tab hands focus to what is left")
    }

    /** The "+" opens an empty tab, not a connection: the first host clicked fills it in. */
    @Test
    fun `the plus opens a blank tab and puts it in focus`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val before = sessions.tabs.size

        onNodeWithContentDescription(string(Res.string.shell_tip_new_tab)).performClick()
        waitForIdle()
        assertEquals(before + 1, sessions.tabs.size)
        val opened = sessions.tabs.last()
        assertEquals(opened.id, sessions.activeId, "a new tab is the one you are now in")
        onTab(string(Res.string.shtail_new_tab)).assertIsDisplayed()
    }

    @Test
    fun `the work bar splits the active tab and synchronizes its panes`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val tab = requireNotNull(sessions.activeTerminal)
        assertFalse(tab.isSplit, "the seeded tab starts as a single pane")
        // With one pane there is nothing to synchronize, so the toggle is not drawn yet.
        onNodeWithContentDescription(string(Res.string.shell_tip_sync_panes)).assertDoesNotExist()

        clickIconWhenEnabled(string(Res.string.shell_tip_add_pane), shell)
        waitForIdle()
        assertTrue(requireNotNull(sessions.activeTerminal).isSplit, "adding a pane splits the tab")

        onNodeWithContentDescription(string(Res.string.shell_tip_sync_panes)).performClick()
        waitForIdle()
        assertTrue(
            requireNotNull(sessions.activeTerminal).syncInput,
            "the toggle must reach the tab the bar is showing",
        )
    }

    /**
     * A tab's grid is bounded ([MAX_PANES]). The button goes disabled at the limit rather than
     * staying lit over a refusal, so the presses past it land on nothing at all.
     */
    @Test
    fun `a full grid refuses another pane`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        repeat(MAX_PANES + 1) {
            val button = onNodeWithContentDescription(string(Res.string.shell_tip_add_pane))
            if (it < MAX_PANES - 1) button.performClick() else button.assertIsNotEnabled()
            waitForIdle()
        }
        assertEquals(
            MAX_PANES,
            requireNotNull(sessions.activeTerminal).panes.size,
            "the grid must stop at its limit instead of growing",
        )
    }

    /** Switching tabs is not closing them: the session behind the chip has to survive the trip. */
    @Test
    fun `walking the tabs keeps every session open`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val ids = sessions.tabs.map { it.id }
        sessions.tabs.map { shell.chipTitle(it) }.forEach { name ->
            onTab(name).performClick()
            waitForIdle()
        }
        assertEquals(ids, sessions.tabs.map { it.id })
        assertNotEquals(null, sessions.activeId)
    }
}

/** What the chip of [tab] shows — the focused pane's title, honoring the tabs setting. */
private fun DesktopShell.chipTitle(tab: Tab): String = tab.tabTitle(state.settings.showTerminalTitleOnTabs)
