package app.skerry.ui.terminal

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolbarOverflowTest {

    @Test
    fun `with no pane under the row nothing overflows`() {
        assertTrue(overflowedActions(available = null, syncShown = false).isEmpty())
    }

    @Test
    fun `a wide pane keeps every action in the row`() {
        assertTrue(overflowedActions(available = 800.dp, syncShown = true).isEmpty())
    }

    @Test
    fun `a narrow pane drops the rarely-reached actions first`() {
        val hidden = overflowedActions(available = 340.dp, syncShown = false)
        // The player and the recorder give way before the file panel does.
        assertTrue(ToolbarAction.Play in hidden)
        assertTrue(ToolbarAction.Record in hidden)
        assertTrue(ToolbarAction.Files !in hidden)
    }

    @Test
    fun `the sync toggle costs one more slot, so it pushes one more action out`() {
        val without = overflowedActions(available = 340.dp, syncShown = false)
        val with = overflowedActions(available = 340.dp, syncShown = true)
        assertEquals(without.size + 1, with.size)
    }

    @Test
    fun `a pane narrower than its own header hides everything that can be hidden`() {
        val hidden = overflowedActions(available = 150.dp, syncShown = true)
        // Add-pane, sync and power are not in the enum: those three never leave the row.
        assertEquals(ToolbarAction.entries.toSet(), hidden)
    }

    @Test
    fun `hiding starts only when the row actually runs out of room`() {
        // Just wide enough for every icon plus the pane's own header: nothing is dropped, and one
        // step narrower something is — the threshold is real, not a constant "always overflow".
        val roomy = overflowedActions(available = 500.dp, syncShown = false)
        val tight = overflowedActions(available = 300.dp, syncShown = false)
        assertTrue(roomy.isEmpty())
        assertTrue(tight.isNotEmpty())
    }
}
