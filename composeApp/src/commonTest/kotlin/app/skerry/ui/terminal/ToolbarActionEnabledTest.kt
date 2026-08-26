package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule one action is drawn by, in both of its renderings: the button in the work bar and the
 * row of the overflow menu. Enumerated here rather than through the UI because a disagreement
 * between the two is a dead press — a lit row that fires a request the parked button drops — and
 * the arms that only show up on a narrow window are the ones a render test never reaches.
 */
class ToolbarActionEnabledTest {

    private fun enabled(
        action: ToolbarAction,
        terminal: Boolean,
        idle: Boolean = true,
        share: Boolean = false,
        busy: Boolean = false,
    ) = toolbarActionEnabled(
        action,
        hasTerminal = terminal,
        runnerIdle = idle,
        shareLive = share,
        playerBusy = busy,
    )

    @Test
    fun `what needs a session to send into is off without one`() {
        for (action in listOf(ToolbarAction.Snippets, ToolbarAction.Record, ToolbarAction.Runbook)) {
            assertFalse(enabled(action, terminal = false), "$action with no terminal")
            assertTrue(enabled(action, terminal = true), "$action with a terminal")
        }
    }

    /** One run at a time: the palette can start nothing while a runbook is on this session. */
    @Test
    fun `the runbook palette is off mid-run and on either side of it`() {
        assertFalse(enabled(ToolbarAction.Runbook, terminal = true, idle = false))
        assertTrue(enabled(ToolbarAction.Runbook, terminal = true, idle = true))
        // Only the runbook is: a run does not take the snippets or the recording away.
        assertTrue(enabled(ToolbarAction.Snippets, terminal = true, idle = false))
        assertTrue(enabled(ToolbarAction.Record, terminal = true, idle = false))
    }

    /**
     * Share is the one action that outlives its terminal. A stream on the air when the pane drops
     * still has to be stoppable, and on a toolbar narrow enough to park the button the menu row is
     * the only control left — greyed out, the stream keeps running to the team with no way to end it.
     */
    @Test
    fun `share stays on for a stream that outlived its pane`() {
        assertTrue(enabled(ToolbarAction.Share, terminal = false, share = true))
        assertTrue(enabled(ToolbarAction.Share, terminal = true, share = false))
        assertFalse(enabled(ToolbarAction.Share, terminal = false, share = false))
    }

    /** Not everything on the bar acts on a session: these three stand on their own. */
    @Test
    fun `what does not act on a session is always on`() {
        for (action in listOf(ToolbarAction.Files, ToolbarAction.Monitor, ToolbarAction.Play)) {
            assertTrue(enabled(action, terminal = false), "$action with nothing connected")
        }
    }

    /**
     * Playback is the exception to that: a recording needs no session, but it does need a picker that
     * is not already up. A long recording parses with the window fully interactive, so both the button
     * and the overflow row would otherwise draw lit for those seconds while every press is dropped —
     * and a second native file dialog on top of the first hangs the app.
     */
    @Test
    fun `playback is off while a picker is up`() {
        assertFalse(enabled(ToolbarAction.Play, terminal = true, busy = true))
        assertFalse(enabled(ToolbarAction.Play, terminal = false, busy = true))
        assertTrue(enabled(ToolbarAction.Play, terminal = false, busy = false))
    }
}
