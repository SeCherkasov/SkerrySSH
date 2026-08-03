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

    @Test
    fun `the sync toggle costs one more slot, so it pushes one more action out`() {
        val without = overflowedActions(available = 500.dp, syncShown = false)
        val with = overflowedActions(available = 500.dp, syncShown = true)
        assertEquals(without.size + 1, with.size)
    }

    @Test
    fun `the assistant button costs a slot only while it is shown`() {
        // Hidden for a host with AI off, so its slot must not be reserved: the row would collapse
        // one action early for every such host.
        val off = overflowedActions(available = 500.dp, syncShown = false, assistantShown = false)
        val on = overflowedActions(available = 500.dp, syncShown = false, assistantShown = true)
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

    /**
     * Pins the threshold to the bar's actual geometry, not just to "somewhere between 400 and 800":
     * title room (240) + the bar's own chrome — padding 2×10, the sidebar chevron 26 and the two
     * 8dp gaps around the title (62) — plus 10 slots of 30 = 602dp. Sliding any of those constants
     * without re-deriving this number has to break the test.
     */
    @Test
    fun `the threshold sits exactly where the bar's geometry puts it`() {
        assertTrue(overflowedActions(available = 602.dp, syncShown = false).isEmpty())
        assertTrue(overflowedActions(available = 601.dp, syncShown = false).isNotEmpty())
    }
}

/** Which panes the info panel is offered for (see [infoPanelAvailable]). */
class InfoPanelAvailabilityTest {

    @Test
    fun `a session of our own gets the info panel`() {
        assertTrue(infoPanelAvailable(hasSession = true, watched = false, mock = false))
    }

    @Test
    fun `a pane watching a colleague's session does not`() {
        // Host, cipher, uptime and metrics all come from a connection this app owns; a viewer has
        // none of them, and the panel would be a column of dashes.
        assertFalse(infoPanelAvailable(hasSession = true, watched = true, mock = false))
    }

    @Test
    fun `with no session there is nothing to describe`() {
        assertFalse(infoPanelAvailable(hasSession = false, watched = false, mock = false))
    }

    @Test
    fun `the mock path keeps its static panel`() {
        assertTrue(infoPanelAvailable(hasSession = false, watched = false, mock = true))
    }
}
