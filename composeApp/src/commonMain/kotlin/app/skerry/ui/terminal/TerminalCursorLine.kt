package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.wrapsToNextRow

/**
 * The shell line the cursor sits on, read off a published screen snapshot.
 *
 * Every reader here — the production guard, the autocomplete ghost, the password-prompt detectors —
 * asks about the same line from the same snapshot, so the reads live together rather than as
 * methods on the state that publishes it. [row] indexes the snapshot directly (the emulator counts
 * scrollback into it), so it must NOT be offset by the screen's start — doing that ran off the end
 * as soon as any history existed, and the guard then saw an empty line.
 */
internal class CursorLine(
    private val grid: List<List<TermCell>>,
    private val row: Int,
    private val col: Int,
    private val rows: Int,
) {
    private val line: List<TermCell>? =
        if (grid.isEmpty() || rows <= 0) null else grid.getOrNull(row)

    /**
     * The cursor row as drawn, or "" when there is no screen yet. What the password-prompt
     * classifiers read: a prompt is one row, and joining the wrapped ones would let output above it
     * decide whether a saved password is offered.
     */
    fun rowText(): String = line?.joinToString("") { it.text } ?: ""

    /**
     * Visible cursor row up to the cursor column — the shell line as the user sees it, prompt
     * included ([ProductionGuard.promptCandidates] strips it).
     */
    fun toCursor(): String {
        val cells = line ?: return ""
        return cells.take(col.coerceIn(0, cells.size)).joinToString("") { it.text }
    }

    /**
     * The whole visible shell line, joined across its soft-wrapped rows, trailing blanks trimmed.
     * Beside [toCursor] because the guard needs both: the shell runs the LINE, not the part of it
     * left of the cursor — a recalled line with the cursor stepped back inside it is longer than
     * what the cursor bounds, and one that wrapped leaves the cursor on the tail row with the head,
     * where the risk usually sits, above it. Bounded by the grid.
     */
    fun logicalText(): String {
        if (line == null) return ""
        return buildString {
            for (at in logicalLineRows()) grid[at].forEach { append(it.text) }
        }.trimEnd()
    }

    /**
     * Whether the shell's line continues past the cursor. Measured in CELLS, never in string
     * characters: a wide glyph is one character in two columns (its continuation cell draws
     * nothing) and a combining sequence is several characters in one, while [col] is a column —
     * comparing against a joined string's length called a CJK row complete with text still right of
     * the cursor. Counted across the logical line's soft-wrapped rows, so a cursor inside a wrapped
     * line reports the tail rows too — and a cursor at the very end of the last one honestly
     * reports nothing left.
     */
    fun continues(): Boolean {
        val cells = line ?: return false
        val lineRows = logicalLineRows()
        // Clamped to the row as [toCursor] clamps: a cursor parked past the row's width would
        // overcount what is behind it and call a continuing line complete.
        var beforeCursor = col.coerceIn(0, cells.size)
        for (at in lineRows.first until row) beforeCursor += grid[at].size
        var total = 0
        for (at in lineRows) {
            val cellsOfRow = grid[at]
            var width = cellsOfRow.size
            if (at == lineRows.last) while (width > 0 && cellsOfRow[width - 1].text.isBlank()) width--
            total += width
        }
        return total > beforeCursor
    }

    /**
     * Rows of the logical line the cursor sits in: soft-wrap joined, up and down from the cursor
     * row. Capped, not merely grid-bounded: the snapshot includes scrollback, and a host that never
     * prints a newline chains wrap flags across thousands of rows — joining them would build a
     * megabyte string on the caller's thread for a classifier that reads 512 characters of a
     * candidate. The window keeps the rows nearest the cursor, which are the ones the shell's line
     * actually lives on; past it the join degrades to what the old single-row read saw.
     */
    private fun logicalLineRows(): IntRange {
        var first = row
        while (
            first > 0 && row - first < MAX_JOINED_WRAP_ROWS &&
            grid.getOrNull(first - 1)?.wrapsToNextRow() == true
        ) first--
        var last = row
        while (
            last - row < MAX_JOINED_WRAP_ROWS && last + 1 < grid.size &&
            grid.getOrNull(last)?.wrapsToNextRow() == true
        ) last++
        return first..last
    }
}

/** How many soft-wrapped rows [CursorLine.logicalLineRows] joins in each direction. */
private const val MAX_JOINED_WRAP_ROWS = 64
