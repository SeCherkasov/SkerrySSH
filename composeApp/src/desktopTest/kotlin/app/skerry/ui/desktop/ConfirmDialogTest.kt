package app.skerry.ui.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.skerry.ui.app.UiTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The confirmations in front of the irreversible actions: deleting a profile, closing a session,
 * replacing what a pane is connected to.
 *
 * These have no fields, so they were not part of the form sweep — but they guard the destructive
 * half of the app, and the failure they exist to prevent is a press that goes through without ever
 * asking. Both directions matter: Cancel must leave everything alone, and Confirm must actually do
 * the thing rather than only closing the dialog.
 */
@OptIn(ExperimentalTestApi::class)
class ConfirmDialogTest {

    @Test
    fun `confirming removes the profile`() = runDesktopShell { shell ->
        val host = shell.hosts.hosts.first()
        shell.state.requestDeleteHost(host)
        waitForIdle()

        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
        assertNull(shell.hosts.find(host.id), "the confirmed delete left the profile in place")
    }

    @Test
    fun `cancelling keeps the profile`() = runDesktopShell { shell ->
        val host = shell.hosts.hosts.first()
        val count = shell.hosts.hosts.size
        shell.state.requestDeleteHost(host)
        waitForIdle()

        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        assertNotNull(shell.hosts.find(host.id), "a cancelled delete removed the profile anyway")
        assertEquals(count, shell.hosts.hosts.size)
    }

    /** Dismissing must also clear the pending state, or the dialog comes back on the next recomposition. */
    @Test
    fun `cancelling closes the dialog`() = runDesktopShell { shell ->
        shell.state.requestDeleteHost(shell.hosts.hosts.first())
        waitForIdle()
        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        onNodeWithTag(UiTags.FORM_SAVE).assertDoesNotExist()
    }

    @Test
    fun `confirming closes the session tab`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions) { "the shell was opened without sessions" }
        val tab = sessions.tabs.first()
        shell.state.requestCloseSession(tab.id)
        waitForIdle()

        onNodeWithTag(UiTags.FORM_SAVE).performClick()
        waitForIdle()
        assertNull(sessions.tabs.firstOrNull { it.id == tab.id }, "the confirmed close left the tab open")
    }

    @Test
    fun `cancelling keeps the session tab`() = runDesktopShell { shell ->
        val sessions = requireNotNull(shell.sessions)
        val before = sessions.tabs.map { it.id }
        shell.state.requestCloseSession(sessions.tabs.first().id)
        waitForIdle()

        onNodeWithTag(UiTags.FORM_CANCEL).performClick()
        waitForIdle()
        assertEquals(before, sessions.tabs.map { it.id }, "a cancelled close still ended the session")
    }
}
