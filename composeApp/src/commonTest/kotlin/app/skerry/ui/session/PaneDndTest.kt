package app.skerry.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaneDndTest {

    // Two panes side by side in row 0, one full-width pane in row 1 — "a,b|c" on a 400×400 window.
    private val panes = listOf(
        PaneBounds("a", row = 0, column = 0, rect = Rect(0f, 0f, 200f, 200f)),
        PaneBounds("b", row = 0, column = 1, rect = Rect(200f, 0f, 400f, 200f)),
        PaneBounds("c", row = 1, column = 0, rect = Rect(0f, 200f, 400f, 400f)),
    )

    @Test
    fun `dropping on the upper edge of a pane makes a row above it`() {
        val drop = paneDropZone(panes, Offset(100f, 10f))!!
        assertEquals(PaneSlot.NewRow(0), drop.slot)
        assertEquals("a", drop.overPaneId)
        assertEquals(PaneEdge.Top, drop.edge)
    }

    @Test
    fun `dropping on the lower edge of a pane makes a row below it`() {
        val drop = paneDropZone(panes, Offset(100f, 190f))!!
        assertEquals(PaneSlot.NewRow(1), drop.slot)
        assertEquals(PaneEdge.Bottom, drop.edge)
    }

    @Test
    fun `the lower edge of the last row appends a row at the bottom`() {
        val drop = paneDropZone(panes, Offset(100f, 395f))!!
        assertEquals(PaneSlot.NewRow(2), drop.slot)
    }

    @Test
    fun `dropping on the left half of a pane inserts before it in its row`() {
        val drop = paneDropZone(panes, Offset(210f, 100f))!! // just inside b, left of its center
        assertEquals(PaneSlot.InRow(row = 0, column = 1), drop.slot)
        assertEquals("b", drop.overPaneId)
        assertEquals(PaneEdge.Left, drop.edge)
    }

    @Test
    fun `dropping on the right half of a pane inserts after it in its row`() {
        val drop = paneDropZone(panes, Offset(390f, 100f))!!
        assertEquals(PaneSlot.InRow(row = 0, column = 2), drop.slot)
        assertEquals(PaneEdge.Right, drop.edge)
    }

    @Test
    fun `the row of the pane under the pointer is what the drop targets`() {
        // Middle band of the second row: the pane there is row 1, so a side drop stays in row 1.
        val drop = paneDropZone(panes, Offset(50f, 300f))!!
        assertEquals(PaneSlot.InRow(row = 1, column = 0), drop.slot)
        assertEquals("c", drop.overPaneId)
    }

    @Test
    fun `a pointer outside every pane is not a drop`() {
        assertNull(paneDropZone(panes, Offset(500f, 500f)))
        assertNull(paneDropZone(emptyList(), Offset(10f, 10f)))
    }

    @Test
    fun `the drag tracks the pointer and clears on end`() {
        val state = PaneDragState()
        panes.forEach { state.setBounds(it.paneId, it.row, it.column, it.rect) }

        state.start("a", localOffset = Offset(10f, 10f)) // grabbed near a's top-left
        assertEquals(PaneSlot.NewRow(0), state.drop?.slot)

        state.dragBy(Offset(300f, 300f)) // dragged into the middle of c
        assertEquals(PaneSlot.InRow(row = 1, column = 1), state.drop?.slot)

        state.end()
        assertNull(state.drop)
        assertNull(state.draggingPaneId)
    }

    @Test
    fun `closing the dragged pane aborts the drag`() {
        val state = PaneDragState()
        panes.forEach { state.setBounds(it.paneId, it.row, it.column, it.rect) }
        state.start("a", localOffset = Offset(10f, 10f))

        state.paneClosed("a")

        assertNull(state.draggingPaneId)
        assertNull(state.drop)
    }

    @Test
    fun `closing another pane leaves the drag alone but forgets its geometry`() {
        val state = PaneDragState()
        panes.forEach { state.setBounds(it.paneId, it.row, it.column, it.rect) }
        state.start("a", localOffset = Offset(10f, 10f))

        state.paneClosed("c")
        state.dragBy(Offset(300f, 300f)) // where c used to be: nothing to drop onto now

        assertEquals("a", state.draggingPaneId)
        assertNull(state.drop)
    }
}
