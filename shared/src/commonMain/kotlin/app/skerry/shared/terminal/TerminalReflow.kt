package app.skerry.shared.terminal

/**
 * A (row, col) point in the buffer that must find its place in the reflowed one — a shell
 * integration mark's anchor (see [ShellCommandMark]). Mutated in place by [TerminalReflow.reflow]:
 * the anchor follows its text through the join and the re-split, exactly as the cursor does.
 */
internal class ReflowAnchor(var row: Int, var col: Int)

/**
 * Reflow of the main buffer on resize — pure functions over row lists, no emulator state:
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
     * Where the cursor sits in the source rows ([abs]/[col]), how many visible screen rows were
     * below it, and whether the reflow should carry it along at all ([tracked] — false for a
     * buffer the cursor does not belong to).
     */
    class Cursor(val abs: Int, val col: Int, val rowsBelow: Int, val tracked: Boolean)

    /**
     * Reflows the main buffer to width [nc] / height [nr]. [src] is all physical rows (scrollback +
     * screen); the bottom [nr] rows of the result go into grid, the rest into scrollback (trimmed to
     * [maxScrollback]). [cursor] is the live cursor in [src]'s coordinates (see [Cursor]; blank
     * space under it is content, see [pickSignificantRows]).
     *
     * [anchors] — mark anchors in [src]'s coordinates — are rewritten in place to the result's
     * coordinates. An anchor never dies in a reflow: one whose row went with the blank tail or with
     * the front of history is clamped to the nearest surviving edge, which for an END anchor ("the
     * command stopped here") is exactly where the drawn content stops.
     */
    fun reflow(
        src: List<List<TermCell>>,
        nc: Int,
        nr: Int,
        maxScrollback: Int,
        cursor: Cursor,
        anchors: List<ReflowAnchor>? = null,
    ): Result {
        val blank = TermCell(" ")
        val grouping = groupLogicals(src, cursor, anchors)
        val split = resplitLogicals(grouping, nc, blank)
        val significant = pickSignificantRows(split.rows, cursor, split.cursorRow, nr)
        val kept = split.rows.subList(0, significant)
        val buffers = splitBuffers(kept, nc, nr, maxScrollback)
        rewriteAnchors(grouping, split, kept.size, significant, buffers.drop)
        return Result(
            scrollback = buffers.scrollback,
            grid = buffers.grid,
            cursorRow = split.cursorRow - buffers.gridStart,
            cursorCol = split.cursorCol,
        )
    }

    /**
     * Steps 1-2: physical rows joined into logical rows (chains by `wrapped`), with the cursor and
     * the mark anchors located in logical coordinates. [anchors] is sorted by row here; the parallel
     * arrays speak of that sorted order.
     */
    private class Grouping(
        val logicals: List<List<TermCell>>,
        val cursorLogical: Int,
        val cursorCol: Int,
        val anchors: List<ReflowAnchor>?,
        val anchorLogical: IntArray, // -1: the anchor's row is past the end of the buffer
        val anchorOffset: IntArray,
    )

    private fun groupLogicals(
        src: List<List<TermCell>>,
        cursor: Cursor,
        anchors: List<ReflowAnchor>?,
    ): Grouping {
        val logicals = ArrayList<MutableList<TermCell>>()
        val sorted = anchors?.sortedBy { it.row }
        val anchorLogical = IntArray(sorted?.size ?: 0) { -1 }
        val anchorOffset = IntArray(sorted?.size ?: 0)
        var curLogIndex = 0
        var curLogCol = cursor.col
        var ai = 0
        var i = 0
        var abs = 0
        while (i < src.size) {
            val cells = ArrayList<TermCell>()
            while (true) {
                val row = src[i]
                if (cursor.tracked && abs == cursor.abs) {
                    curLogIndex = logicals.size
                    curLogCol = cells.size + cursor.col
                }
                while (sorted != null && ai < sorted.size && sorted[ai].row == abs) {
                    anchorLogical[ai] = logicals.size
                    anchorOffset[ai] = cells.size + sorted[ai].col.coerceAtMost(row.size)
                    ai++
                }
                cells.addAll(row)
                val wrapped = row.wrapsToNextRow()
                i++; abs++
                if (!wrapped || i >= src.size) break
            }
            logicals.add(cells)
        }
        return Grouping(logicals, if (cursor.tracked) curLogIndex else -1, curLogCol, sorted, anchorLogical, anchorOffset)
    }

    /** Step 3's outcome: the re-split rows and where the cursor/anchors landed among them. */
    private class Split(
        val rows: List<TermRow>,
        val cursorRow: Int,
        val cursorCol: Int,
        val anchorRow: IntArray, // absolute row in `rows`; -1: never placed
    )

    /**
     * Step 3: trim each logical row of trailing default blanks and re-split it at [nc]. The cursor
     * column stays reachable on its own line (the cursor can sit past the text); its position comes
     * from the actual split pass (not offset/nc arithmetic — wide chars can leave rows with nc-1
     * visible cells). Anchor columns are written into the anchors here; their final rows wait for
     * the two trims ([rewriteAnchors]).
     */
    private fun resplitLogicals(g: Grouping, nc: Int, blank: TermCell): Split {
        val byLogical = HashMap<Int, MutableList<Int>>()
        if (g.anchors != null) for (k in g.anchors.indices) {
            if (g.anchorLogical[k] >= 0) byLogical.getOrPut(g.anchorLogical[k]) { ArrayList() }.add(k)
        }
        val out = ArrayList<TermRow>(g.logicals.size)
        var cursorAbsNew = 0
        var cursorColNew = 0
        val anchorRow = IntArray(g.anchors?.size ?: 0) { -1 }
        g.logicals.forEachIndexed { idx, cells ->
            var len = cells.size
            while (len > 0 && cells[len - 1] == blank) len--
            val isCursorLine = idx == g.cursorLogical
            if (isCursorLine) len = maxOf(len, g.cursorCol + 1)
            val logical = if (len == cells.size) cells else cells.subList(0, len.coerceAtMost(cells.size))
            val base = out.size
            val cursorOut = IntArray(2) { -1 }
            val mine = byLogical[idx]?.sortedBy { g.anchorOffset[it] }
            val spots = mine?.let { list -> AnchorSpots(List(list.size) { k -> g.anchorOffset[list[k]] }) }
            out.addAll(
                splitLogical(
                    logical, nc, blank,
                    cursorCol = if (idx == g.cursorLogical) g.cursorCol else -1,
                    cursorOut, spots,
                ),
            )
            if (isCursorLine && cursorOut[0] >= 0) {
                cursorAbsNew = base + cursorOut[0]
                cursorColNew = cursorOut[1]
            }
            writeBackAnchors(g, mine, spots, base, anchorRow)
        }
        return Split(out, cursorAbsNew, cursorColNew, anchorRow)
    }

    /** One logical row's split is done: note where its anchors landed, in output-row coordinates. */
    private fun writeBackAnchors(g: Grouping, mine: List<Int>?, spots: AnchorSpots?, base: Int, anchorRow: IntArray) {
        if (spots == null || mine == null) return
        mine.forEachIndexed { k, a ->
            anchorRow[a] = base + spots.out[k * 2]
            g.anchors!![a].col = spots.out[k * 2 + 1]
        }
    }

    /**
     * Step 4: how many of [out]'s rows are content — up to the last non-blank, plus the cursor row
     * and the blank screen space below it (after `clear`/Ctrl+L the screen below the prompt is blank
     * while history is already in scrollback: collapsing it would pull history back onto the visible
     * screen).
     */
    private fun pickSignificantRows(out: List<TermRow>, cursor: Cursor, cursorRow: Int, nr: Int): Int {
        var significant = out.size
        while (significant > 0 && out[significant - 1].all { it == BLANK_FOR_SIGNIFICANCE }) significant--
        if (cursor.tracked) {
            significant = maxOf(significant, cursorRow + 1 + cursor.rowsBelow.coerceIn(0, nr - 1))
        }
        return significant.coerceAtLeast(1).coerceAtMost(out.size)
    }

    /** Step 5's outcome: the two buffers, and where the grid begins / how much history was trimmed. */
    private class Buffers(
        val scrollback: List<TermRow>,
        val grid: MutableList<TermRow>,
        val gridStart: Int,
        val drop: Int,
    )

    private fun splitBuffers(kept: List<TermRow>, nc: Int, nr: Int, maxScrollback: Int): Buffers {
        val gridStart: Int
        val grid: MutableList<TermRow>
        if (kept.size >= nr) {
            gridStart = kept.size - nr
            grid = ArrayList(kept.subList(gridStart, kept.size))
        } else {
            gridStart = 0
            grid = ArrayList(kept)
            repeat(nr - kept.size) { grid.add(TermRow(nc, BLANK_FOR_SIGNIFICANCE)) }
        }
        val newScroll = if (gridStart > 0) kept.subList(0, gridStart) else emptyList()
        val drop = (newScroll.size - maxScrollback).coerceAtLeast(0)
        return Buffers(
            scrollback = if (drop > 0) newScroll.subList(drop, newScroll.size) else newScroll,
            grid = grid,
            gridStart = gridStart,
            drop = drop,
        )
    }

    /**
     * The anchors' final rows, mapped through the blank-tail trim ([significant]) and the history
     * trim ([drop]). A never-placed anchor takes the head of the buffer; a clamp is a clamp — see
     * [reflow] for why an anchor does not die here. The one exception is an anchor whose row left
     * with the trimmed front ([drop]): it is parked at row -1, and the CALLER decides — a mark
     * without its prompt dies (it would point at someone else's text), a lesser anchor clamps
     * back to the head.
     */
    private fun rewriteAnchors(g: Grouping, split: Split, keptSize: Int, significant: Int, drop: Int) {
        val sorted = g.anchors ?: return
        for (a in sorted.indices) {
            var abs = if (split.anchorRow[a] >= 0) split.anchorRow[a] else 0
            if (abs >= significant) abs = significant - 1
            val mapped = abs - drop
            sorted[a].row = if (mapped < 0) -1 else mapped.coerceIn(0, keptSize - 1)
        }
    }

    /**
     * The anchors of one logical row, placed while it splits: [offsets] (ascending, cell offsets
     * from the logical row's start) and where each landed — `out[2k]` row, `out[2k+1]` column.
     */
    private class AnchorSpots(offsets: List<Int>) {
        val offsets = offsets.toIntArray()
        val out = IntArray(offsets.size * 2)
        var placed = 0

        /** Anchors sitting directly before cell [cellIdx] (offsets arrive ascending, so one check). */
        fun place(cellIdx: Int, row: Int, col: Int) {
            while (placed < this.offsets.size && this.offsets[placed] == cellIdx) {
                out[placed * 2] = row; out[placed * 2 + 1] = col; placed++
            }
        }

        /** The anchors left after the last cell: all on [row], each at [colAt] of its own offset. */
        fun placeOnRow(row: Int, colAt: (offset: Int) -> Int) {
            while (placed < offsets.size) {
                out[placed * 2] = row; out[placed * 2 + 1] = colAt(offsets[placed]); placed++
            }
        }
    }

    /**
     * Splits logical row [cells] into physical rows of width [nc], without breaking wide characters
     * (Wide+Continuation must stay on the same row). All but the last row are marked `wrapped = true`;
     * each is padded to [nc] with neutral [pad]. An empty logical row becomes one blank row.
     *
     * [cursorCol] >= 0 places the cursor via [cursorOut] (`[0]` row within the returned list,
     * `[1]` column) — from a direct pass, so it stays correct even on an early break at a wide
     * character (where the row carries nc-1 visible cells). [anchors] places mark anchors the same
     * way; an offset at the very end of the line takes the last row's tail column — past its final
     * cell, like an exclusive bound.
     */
    private fun splitLogical(
        cells: List<TermCell>,
        nc: Int,
        pad: TermCell,
        cursorCol: Int = -1,
        cursorOut: IntArray? = null,
        anchors: AnchorSpots? = null,
    ): List<TermRow> {
        if (cells.isEmpty()) {
            if (cursorCol >= 0 && cursorOut != null) { cursorOut[0] = 0; cursorOut[1] = cursorCol.coerceIn(0, nc - 1) }
            anchors?.placeOnRow(0) { it.coerceIn(0, nc) }
            return listOf(TermRow(nc, pad))
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
                anchors?.place(idx, out.size, chunk.size)
                chunk.add(cell); idx++
            }
            while (chunk.size < nc) chunk.add(pad)
            out.add(TermRow(chunk, wrapped = idx < cells.size))
        }
        // Anchors at the line's very end (offset == cells.size) have no cell to sit before.
        anchors?.placeOnRow(out.size - 1) { out.last().size }
        return out
    }

    // The blank the significance check compares cells against — the same neutral cell reflow trims.
    private val BLANK_FOR_SIGNIFICANCE = TermCell(" ")
}
