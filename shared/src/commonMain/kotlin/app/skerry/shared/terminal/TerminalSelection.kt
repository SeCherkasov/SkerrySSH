package app.skerry.shared.terminal

/** Whether a cell counts as blank for word selection: empty text (continuation cell) or whitespace. */
private fun TermCell.isBlank(): Boolean = text.isBlank()

/** Cell position in the screen grid: row top-to-bottom, column left-to-right (both zero-based). */
data class TerminalPos(val row: Int, val col: Int) : Comparable<TerminalPos> {
    override fun compareTo(other: TerminalPos): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/**
 * Selects the "whole word" under position [pos] for long-press (as in messaging apps): on the row,
 * takes the contiguous run of cells of the same class as the one under the finger (word = non-blank
 * run, or blank run if the finger is on whitespace). The end bound is exclusive. An empty row or an
 * out-of-grid position returns an empty selection at [pos].
 */
fun wordSelectionAt(screen: List<List<TermCell>>, pos: TerminalPos): TerminalSelection {
    if (screen.isEmpty()) return TerminalSelection(pos, pos)
    val row = pos.row.coerceIn(0, screen.lastIndex)
    val line = screen[row]
    if (line.isEmpty()) return TerminalSelection(TerminalPos(row, 0), TerminalPos(row, 0))
    val col = pos.col.coerceIn(0, line.lastIndex)
    val wordClass = !line[col].isBlank()
    var start = col
    while (start > 0 && !line[start - 1].isBlank() == wordClass) start--
    var end = col
    while (end < line.lastIndex && !line[end + 1].isBlank() == wordClass) end++
    return TerminalSelection(TerminalPos(row, start), TerminalPos(row, end + 1))
}

/**
 * Selects the whole row under position [pos], for triple-click: from the first to the last column
 * of the row (end exclusive). Out-of-grid positions are clamped; an empty grid returns an empty
 * selection.
 */
fun lineSelectionAt(screen: List<List<TermCell>>, pos: TerminalPos): TerminalSelection {
    if (screen.isEmpty()) return TerminalSelection(pos, pos)
    val row = pos.row.coerceIn(0, screen.lastIndex)
    return TerminalSelection(TerminalPos(row, 0), TerminalPos(row, screen[row].size))
}

/**
 * Linear text selection on the terminal screen, as in typical emulators: from `anchor` (where it
 * started) to `focus` (current pointer position), wrapping across row ends (not block selection).
 * A plain model with no Compose dependency: operates on a `List<List<TermCell>>` grid, so it's
 * tested directly.
 *
 * The range is half-open: the start cell is included, the end cell is not (as in text selection,
 * where the cursor sits "before" a character). Drag direction doesn't matter — bounds are normalized.
 */
data class TerminalSelection(val anchor: TerminalPos, val focus: TerminalPos) {

    /** Top-left bound (inclusive). */
    val start: TerminalPos get() = minOf(anchor, focus)

    /** Bottom-right bound (exclusive). */
    val end: TerminalPos get() = maxOf(anchor, focus)

    /** Empty when anchor and focus coincide — nothing to select. */
    val isEmpty: Boolean get() = start == end

    /** Whether cell (row, col) is inside the selection under linear semantics. */
    fun contains(row: Int, col: Int): Boolean {
        if (isEmpty) return false
        val s = start
        val e = end
        if (row < s.row || row > e.row) return false
        val fromOk = row > s.row || col >= s.col
        val toOk = row < e.row || col < e.col
        return fromOk && toOk
    }

    /**
     * Selection text: takes the covered segment of each spanned row, trims trailing whitespace (as
     * terminals do on copy), and joins rows with `\n`. Columns past the end of a row are clamped to
     * its length.
     */
    fun extract(screen: List<List<TermCell>>): String {
        if (isEmpty) return ""
        val s = start
        val e = end
        val firstRow = s.row.coerceAtLeast(0)
        val lastRow = e.row.coerceAtMost(screen.lastIndex)
        if (firstRow > lastRow) return ""
        return (firstRow..lastRow).joinToString("\n") { r ->
            val row = screen[r]
            val from = (if (r == s.row) s.col else 0).coerceIn(0, row.size)
            val to = (if (r == e.row) e.col else row.size).coerceIn(from, row.size)
            buildString { for (c in from until to) append(row[c].text) }.trimEnd()
        }
    }
}
