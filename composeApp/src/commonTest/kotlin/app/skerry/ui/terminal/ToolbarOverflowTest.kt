package app.skerry.ui.terminal

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolbarOverflowTest {

    @Test
    fun `with the bar not measured yet nothing overflows`() {
        assertTrue(overflowedActions(available = null, syncShown = false).isEmpty())
    }

    @Test
    fun `a wide window keeps every action in the row`() {
        assertTrue(overflowedActions(available = 800.dp, syncShown = true).isEmpty())
    }

    @Test
    fun `a narrow window drops the rarely-reached actions first`() {
        val hidden = overflowedActions(available = 500.dp, syncShown = false)
        // The player and the recorder give way before the file panel does.
        assertTrue(ToolbarAction.Play in hidden)
        assertTrue(ToolbarAction.Record in hidden)
        assertTrue(ToolbarAction.Files !in hidden)
    }

    // 534dp is wide enough that both groups still hold something either way, so one extra button
    // costs exactly one action and nothing else moves. Narrower, a group empties and its hairline
    // pays part of the bill — that is the subject of its own test below.
    @Test
    fun `the sync toggle costs one more slot, so it pushes one more action out`() {
        val without = overflowedActions(available = 534.dp, syncShown = false)
        val with = overflowedActions(available = 534.dp, syncShown = true)
        assertEquals(without.size + 1, with.size)
    }

    @Test
    fun `the assistant button costs a slot only while it is shown`() {
        // Hidden for a host with AI off, so its slot must not be reserved: the row would collapse
        // one action early for every such host.
        val off = overflowedActions(available = 534.dp, syncShown = false, assistantShown = false)
        val on = overflowedActions(available = 534.dp, syncShown = false, assistantShown = true)
        assertEquals(off.size + 1, on.size)
    }

    @Test
    fun `a window narrower than the bar's own title hides everything that can be hidden`() {
        val hidden = overflowedActions(available = 150.dp, syncShown = true)
        // Add-pane, sync and power are not in the enum: those three never leave the row.
        assertEquals(ToolbarAction.entries.toSet(), hidden)
    }

    @Test
    fun `hiding starts only when the row actually runs out of room`() {
        // Just wide enough for every icon plus the bar's own title: nothing is dropped, and one
        // step narrower something is — the threshold is real, not a constant "always overflow".
        val roomy = overflowedActions(available = 800.dp, syncShown = false)
        val tight = overflowedActions(available = 400.dp, syncShown = false)
        assertTrue(roomy.isEmpty())
        assertTrue(tight.isNotEmpty())
    }

    @Test
    fun `a group emptied by overflow stops costing its separator`() {
        // 465dp fits five icons and one hairline. With all four session actions in the menu the
        // Workspace-Session separator is not drawn any more, so charging for it anyway would push
        // the info panel out of a row that still has room for it.
        assertEquals(
            setOf(
                ToolbarAction.Play,
                ToolbarAction.Record,
                ToolbarAction.Share,
                ToolbarAction.Runbook,
                ToolbarAction.Snippets,
            ),
            overflowedActions(available = 465.dp, syncShown = false),
        )
    }

    /**
     * Pins the threshold to the bar's actual geometry, not just to "somewhere between 400 and 800":
     * title room (240) + the bar's own chrome — padding 2×10, the leading chevron 26 and the two
     * 8dp gaps around the title (62) — plus the two group separators (2×11) and 9 slots of
     * 30 = 594dp. Sliding any of those constants without re-deriving this number has to break the
     * test.
     */
    @Test
    fun `the threshold sits exactly where the bar's geometry puts it`() {
        assertTrue(overflowedActions(available = 594.dp, syncShown = false).isEmpty())
        assertTrue(overflowedActions(available = 593.dp, syncShown = false).isNotEmpty())
    }
}

/** What the action row offers when there is no session for the actions to act on. */
class ToolbarAvailabilityTest {

    @Test
    fun `with a session the row offers everything`() {
        assertEquals(ToolbarAction.entries.toSet(), availableActions(hasSession = true))
    }

    @Test
    fun `without a session only the actions that do not need one are drawn`() {
        // The player opens a recording in its own tab; every other action steers a session, and
        // with none open they would be buttons that quietly do nothing.
        assertEquals(setOf(ToolbarAction.Play), availableActions(hasSession = false))
    }

    @Test
    fun `the actions are split into three groups, in row order`() {
        assertEquals(
            listOf(ToolbarGroup.Workspace, ToolbarGroup.Session, ToolbarGroup.Global),
            ToolbarGroup.entries,
        )
        // An action missing from the row order sorts to -1, i.e. to the top of the overflow menu
        // instead of into its group's place — and nothing else would say so.
        assertEquals(ToolbarAction.entries.toSet(), TOOLBAR_ROW_ORDER.toSet())
        assertEquals(ToolbarAction.entries.size, TOOLBAR_ROW_ORDER.size)
        assertEquals(ToolbarGroup.Workspace, ToolbarAction.Files.group)
        assertEquals(ToolbarGroup.Session, ToolbarAction.Snippets.group)
        assertEquals(ToolbarGroup.Global, ToolbarAction.Play.group)
    }

    @Test
    fun `a row with no session has room to spare, so nothing overflows`() {
        // One icon left: a window that would collapse the full row keeps it.
        assertTrue(overflowedActions(available = 400.dp, syncShown = false, hasSession = false).isEmpty())
    }

    @Test
    fun `overflow only ever hides actions the row would have drawn`() {
        // A window too narrow for anything: the one session-free action is all there is to hide,
        // and the session-scoped ones must not turn up in the overflow menu they never entered.
        val hidden = overflowedActions(available = 150.dp, syncShown = false, hasSession = false)
        assertEquals(setOf(ToolbarAction.Play), hidden)
    }
}

/** Which panes the monitor is offered for (see [monitorAvailable]). */
class MonitorAvailabilityTest {

    @Test
    fun `a session of our own gets the monitor`() {
        assertTrue(monitorAvailable(hasSession = true, watched = false, mock = false))
    }

    @Test
    fun `a pane watching a colleague's session does not`() {
        // Every number on that screen comes from a connection this app owns; a viewer has none of
        // them, so the button is left out rather than opening a screen full of dashes.
        assertFalse(monitorAvailable(hasSession = true, watched = true, mock = false))
    }

    @Test
    fun `with no session there is nothing to poll`() {
        assertFalse(monitorAvailable(hasSession = false, watched = false, mock = false))
    }

    @Test
    fun `the mock path keeps its static screen`() {
        assertTrue(monitorAvailable(hasSession = false, watched = false, mock = true))
    }

    @Test
    fun `a row without the monitor neither draws nor overflows it`() {
        val shown = availableActions(hasSession = true, monitorShown = false)
        assertFalse(ToolbarAction.Monitor in shown)
        assertFalse(ToolbarAction.Monitor in overflowedActions(available = 150.dp, syncShown = false, monitorShown = false))
    }
}
