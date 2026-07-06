package app.skerry.shared.terminal

/**
 * Reflow of the main buffer on resize — pure functions over [TermRow] lists, no emulator state:
 * soft-wrapped (`wrapped`) physical rows are joined into logical rows and re-split at the new
 * width, with the cursor following its text. Tested directly and via emulator resize tests.
 */
internal object TerminalReflow {

    /** Reflow result: new scrollback/grid and cursor position in the new grid's coordinates. */
    class Result(
        val scrollback: List<TermRow>,
        val grid: MutableList<TermRow>,
        val cursorRow: Int,
        val cursorCol: Int,
    )

    /**
     * Reflows the main buffer to width [nc] / height [nr]. [src] is all physical rows (scrollback +
     * screen); the bottom [nr] rows of the result go into grid, the rest into scrollback (trimmed to
     * [maxScrollback]). [cursorAbs]/[cursorCol] is the cursor position in [src] (meaningful when
     * [trackCursor]); [rowsBelowCursor] is the number of visible screen rows below the cursor before
     * the resize (blank space under the cursor is content, see step 4).
     */
    fun reflow(
        src: List<TermRow>,
        nc: Int,
        nr: Int,
        maxScrollback: Int,
        cursorAbs: Int,
        cursorCol: Int,
        rowsBelowCursor: Int,
        trackCursor: Boolean,
    ): Result {
        val blank = TermCell(" ")

        // 1-2. Group physical rows into logical rows (chains by wrapped) and locate the cursor's
        //      logical index/column.
        val logicals = ArrayList<MutableList<TermCell>>()
        var curLogIndex = 0
        var curLogCol = cursorCol
        run {
            var i = 0
            var abs = 0
            while (i < src.size) {
                val cells = ArrayList<TermCell>()
                while (true) {
                    val row = src[i]
                    if (trackCursor && abs == cursorAbs) {
                        curLogIndex = logicals.size
                        curLogCol = cells.size + cursorCol
                    }
                    cells.addAll(row)
                    val wrapped = row.wrapped
                    i++; abs++
                    if (!wrapped || i >= src.size) break
                }
                logicals.add(cells)
            }
        }

        // 3. Trim each logical row of trailing default blanks and re-split it by nc.
        val out = ArrayList<TermRow>(logicals.size)
        var cursorAbsNew = 0
        var cursorColNew = 0
        logicals.forEachIndexed { idx, cells ->
            var len = cells.size
            while (len > 0 && cells[len - 1] == blank) len--
            val isCursorLine = trackCursor && idx == curLogIndex
            // Ensure the cursor column stays reachable after trimming (cursor can sit past the text).
            if (isCursorLine) len = maxOf(len, curLogCol + 1)
            val logical = if (len == cells.size) cells else cells.subList(0, len.coerceAtMost(cells.size))
            val base = out.size
            // Cursor position comes from the actual split pass (not curLogCol/nc arithmetic): wide
            // chars can leave rows with nc-1 visible cells, which would throw off the arithmetic.
            val cursorOut = IntArray(2) { -1 }
            out.addAll(splitLogical(logical, nc, blank, if (isCursorLine) curLogCol else -1, cursorOut))
            if (isCursorLine && cursorOut[0] >= 0) {
                cursorAbsNew = base + cursorOut[0]
                cursorColNew = cursorOut[1]
            }
        }

        // 4. Drop the insignificant blank tail (unused space at the bottom of the screen shouldn't go
        //    into scrollback): keep rows up to the last non-blank and up to the cursor row inclusive.
        //    But blank space on the VISIBLE screen below the cursor is content (after `clear`/`Ctrl+L`
        //    the screen below the prompt is blank while history is already in scrollback): keep it, or
        //    reflow would collapse the tail and pull history back onto the visible screen. Cap nr-1
        //    keeps the cursor from running off the bottom.
        var significant = out.size
        while (significant > 0 && out[significant - 1].all { it == blank }) significant--
        if (trackCursor) {
            val belowCursorInScreen = rowsBelowCursor.coerceIn(0, nr - 1)
            significant = maxOf(significant, cursorAbsNew + 1 + belowCursorInScreen)
        }
        significant = significant.coerceAtLeast(1).coerceAtMost(out.size)
        val kept = out.subList(0, significant)

        // 5. Split into grid (bottom nr rows) and scrollback (rest), padding with blanks if short.
        val gridStart: Int
        val newGrid: MutableList<TermRow>
        if (kept.size >= nr) {
            gridStart = kept.size - nr
            newGrid = ArrayList(kept.subList(gridStart, kept.size))
        } else {
            gridStart = 0
            newGrid = ArrayList(kept)
            repeat(nr - kept.size) { newGrid.add(TermRow(MutableList(nc) { blank })) }
        }
        val newScroll = if (gridStart > 0) kept.subList(0, gridStart) else emptyList()
        val drop = (newScroll.size - maxScrollback).coerceAtLeast(0)

        return Result(
            scrollback = if (drop > 0) newScroll.subList(drop, newScroll.size) else newScroll,
            grid = newGrid,
            cursorRow = cursorAbsNew - gridStart,
            cursorCol = cursorColNew,
        )
    }

    /**
     * Splits logical row [cells] into physical rows of width [nc], without breaking wide characters
     * (Wide+Continuation must stay on the same row). All but the last row are marked `wrapped = true`;
     * each is padded to [nc] with neutral [pad]. An empty logical row becomes one blank row.
     *
     * If [cursorCol] >= 0, [cursorOut] receives the cursor position in the result: `[0]` is the row
     * index (within the returned list), `[1]` is the column in it. The position comes from a direct
     * pass, so it stays correct even on an early break at a wide character (where the row carries
     * nc-1 visible cells).
     */
    private fun splitLogical(
        cells: List<TermCell>,
        nc: Int,
        pad: TermCell,
        cursorCol: Int = -1,
        cursorOut: IntArray? = null,
    ): List<TermRow> {
        if (cells.isEmpty()) {
            if (cursorCol >= 0 && cursorOut != null) { cursorOut[0] = 0; cursorOut[1] = cursorCol.coerceIn(0, nc - 1) }
            return listOf(TermRow(MutableList(nc) { pad }))
        }
        val out = ArrayList<TermRow>()
        var idx = 0
        while (idx < cells.size) {
            val chunk = ArrayList<TermCell>(nc)
            while (idx < cells.size && chunk.size < nc) {
                val cell = cells[idx]
                // Don't split a wide character: if the pair doesn't fit the remaining chunk, break early.
                if (cell.width == CellWidth.Wide && chunk.isNotEmpty() && chunk.size + 2 > nc) break
                if (idx == cursorCol && cursorOut != null) { cursorOut[0] = out.size; cursorOut[1] = chunk.size }
                chunk.add(cell); idx++
            }
            while (chunk.size < nc) chunk.add(pad)
            out.add(TermRow(chunk, wrapped = idx < cells.size))
        }
        return out
    }
}
