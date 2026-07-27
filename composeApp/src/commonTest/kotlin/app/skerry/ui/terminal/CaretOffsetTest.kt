package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Placement of a collaborator's caret. The marker is only useful if it stands on the cell being
 * typed into, and every case here is one where a naive "count from the bottom edge" version put it
 * on the wrong line.
 */
class CaretOffsetTest {

    private val metrics = TerminalMetrics(cellWidth = 8f, cellHeight = 16f)

    private fun offset(
        cursorRow: Int,
        cursorCol: Int,
        snapshotRows: Int,
        gridRows: Int,
        scrollPx: Float = 0f,
    ) = caretOffsetPx(cursorRow, cursorCol, snapshotRows, gridRows, metrics, scrollPx)

    @Test
    fun `output shorter than the viewport puts the caret on its own line`() {
        // Six rows printed into an eight-row grid: the cursor's screen row IS the snapshot row.
        val (x, y) = offset(cursorRow = 5, cursorCol = 10, snapshotRows = 6, gridRows = 8)

        assertEquals(80f, x)
        // Under the cursor's row: the bar reaches up into it, the tag hangs below the line.
        assertEquals(16f * 6, y)
    }

    @Test
    fun `with scrollback the screen rows sit at the end of the snapshot`() {
        // 500 rows of history, a 24-row screen: the cursor on screen row 23 is snapshot row 499.
        val (_, y) = offset(cursorRow = 23, cursorCol = 0, snapshotRows = 500, gridRows = 24)

        assertEquals(16f * 500, y)
    }

    @Test
    fun `scrolling back moves the caret with the content`() {
        val (_, atBottom) = offset(cursorRow = 23, cursorCol = 0, snapshotRows = 500, gridRows = 24)
        val (_, scrolled) = offset(cursorRow = 23, cursorCol = 0, snapshotRows = 500, gridRows = 24, scrollPx = 160f)

        assertEquals(atBottom - 160f, scrolled, "the caret must follow the rows, not the viewport")
    }

    @Test
    fun `a caret scrolled above the top edge is clamped into the terminal`() {
        val (_, y) = offset(cursorRow = 0, cursorCol = 0, snapshotRows = 1, gridRows = 24, scrollPx = 400f)

        assertEquals(0f, y, "the marker would otherwise be drawn over the pane's header")
    }

    @Test
    fun `a cursor past the snapshot lands on its last row instead of past the end`() {
        val (_, y) = offset(cursorRow = 30, cursorCol = 0, snapshotRows = 10, gridRows = 8)

        assertEquals(16f * 10, y)
    }
}
