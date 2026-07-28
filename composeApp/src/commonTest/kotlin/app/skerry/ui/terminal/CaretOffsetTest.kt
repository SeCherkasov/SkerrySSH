package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Placement of a collaborator's caret. The marker is only useful if it stands on the cell being
 * typed into, and every case here is one where an earlier version put it on the wrong line.
 *
 * Rows are snapshot rows throughout — the coordinate [TerminalScreenState.cursorRow] speaks, and the
 * one the canvas draws in.
 */
class CaretOffsetTest {

    private val metrics = TerminalMetrics(cellWidth = 8f, cellHeight = 16f)

    private fun offset(
        cursorRow: Int,
        cursorCol: Int,
        snapshotRows: Int,
        scrollPx: Float = 0f,
    ) = caretOffsetPx(cursorRow, cursorCol, snapshotRows, metrics, scrollPx)

    @Test
    fun `output shorter than the viewport puts the caret on its own line`() {
        // Six rows printed, nothing in history: the cursor's row is snapshot row 5.
        val (x, y) = offset(cursorRow = 5, cursorCol = 10, snapshotRows = 6)

        assertEquals(80f, x)
        // Under the cursor's row: the bar reaches up into it, the tag hangs below the line.
        assertEquals(16f * 6, y)
    }

    @Test
    fun `with scrollback the caret follows the row the cursor reports`() {
        // 500 rows of history, cursor on the last of them (the live screen's bottom row).
        val (_, y) = offset(cursorRow = 499, cursorCol = 0, snapshotRows = 500)

        assertEquals(16f * 500, y)
    }

    @Test
    fun `scrolling back moves the caret with the content`() {
        val (_, atBottom) = offset(cursorRow = 499, cursorCol = 0, snapshotRows = 500)
        val (_, scrolled) = offset(cursorRow = 499, cursorCol = 0, snapshotRows = 500, scrollPx = 160f)

        assertEquals(atBottom - 160f, scrolled, "the caret must follow the rows, not the viewport")
    }

    @Test
    fun `after clear the caret stays on the cursor's row, not below the scrollback`() {
        // What `clear` leaves behind: the old screen is pushed into scrollback (400 rows) and the
        // cursor sits on the first row of the fresh grid — snapshot row 400, exactly what
        // TerminalScreenState.cursorRow reports (emulator: scrollback.size + cy).
        val (_, y) = offset(cursorRow = 400, cursorCol = 0, snapshotRows = 424)

        assertEquals(16f * 401, y, "the caret was left behind at the bottom of the scrollback")
    }

    @Test
    fun `a caret scrolled above the top edge is clamped into the terminal`() {
        val (_, y) = offset(cursorRow = 0, cursorCol = 0, snapshotRows = 1, scrollPx = 400f)

        assertEquals(0f, y, "the marker would otherwise be drawn over the pane's header")
    }

    @Test
    fun `a cursor past the snapshot lands on its last row instead of past the end`() {
        val (_, y) = offset(cursorRow = 30, cursorCol = 0, snapshotRows = 10)

        assertEquals(16f * 10, y)
    }
}
