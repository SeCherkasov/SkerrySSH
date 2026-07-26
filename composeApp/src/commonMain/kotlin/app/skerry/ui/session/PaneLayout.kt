package app.skerry.ui.session

import androidx.compose.runtime.Immutable

/**
 * Panes one tab can hold at once. Four is the readable limit on a desktop screen: each pane is a
 * live session with its own connection, and a fifth one on a 1080p window leaves columns too narrow
 * for real output.
 */
const val MAX_PANES = 4

/**
 * Smallest share of an axis a pane can be dragged down to. Keeps a squeezed pane visible (and its
 * header hittable) instead of letting a divider collapse it to nothing.
 */
const val MIN_PANE_WEIGHT = 0.15f

/** One pane in a row: which session sits there and its share of the row's width. */
@Immutable
data class PaneCell(val paneId: String, val weight: Float)

/** One row of the grid: its panes left-to-right and the row's share of the tab's height. */
@Immutable
data class PaneRow(val cells: List<PaneCell>, val weight: Float)

/** Where a pane goes when it is added or dropped. */
sealed interface PaneSlot {
    /** Into an existing [row], before the pane currently at [column] (`column == size` appends). */
    data class InRow(val row: Int, val column: Int) : PaneSlot

    /** As a whole new row inserted at [row] (`row == rows.size` appends at the bottom). */
    data class NewRow(val row: Int) : PaneSlot
}

/**
 * Pane grid of one tab: rows top-to-bottom, each row holding its panes left-to-right. Two levels are
 * enough for the shapes this is for ("two on top, one below", "one on top, three below", 2×2, plain
 * columns or rows) and keep drag-and-drop addressable by a (row, column) pair; arbitrary nesting
 * would need a recursive tree and buys nothing at [MAX_PANES] panes.
 *
 * Immutable — every operation returns a new layout, and one that changes nothing returns `this`, so
 * the caller's snapshot state doesn't recompose on a no-op drop. Weights on both axes always sum to
 * 1 within their container, so the UI can hand them straight to `Modifier.weight`.
 */
@Immutable
data class PaneLayout(val rows: List<PaneRow>) {

    /** Panes in visual order: row by row, left to right within each row. */
    val paneIds: List<String> get() = rows.flatMap { row -> row.cells.map { it.paneId } }

    val size: Int get() = rows.sumOf { it.cells.size }

    val isFull: Boolean get() = size >= MAX_PANES

    operator fun contains(paneId: String): Boolean = rows.any { row -> row.cells.any { it.paneId == paneId } }

    /**
     * Place [paneId] at [slot]. Refused (returns `this`) when the tab is already full or the pane is
     * placed already; an out-of-range slot is clamped rather than rejected, so a drop that lands
     * past the last row appends instead of vanishing.
     */
    fun add(paneId: String, slot: PaneSlot): PaneLayout {
        if (isFull || paneId in this) return this
        return insert(paneId, slot)
    }

    /**
     * Take [paneId] out. The row it leaves is dropped when it was its last pane; the freed share
     * goes back to its siblings proportionally. Removing the only pane is refused — a tab always has
     * its primary pane (closing that one closes the tab).
     */
    fun remove(paneId: String): PaneLayout {
        if (paneId !in this || size <= 1) return this
        val remaining = rows.mapNotNull { row ->
            val cells = row.cells.filterNot { it.paneId == paneId }
            if (cells.isEmpty()) null else row.copy(cells = rescaled(cells, { it.weight }) { cell, w -> cell.copy(weight = w) })
        }
        return PaneLayout(rescaled(remaining, { it.weight }) { row, w -> row.copy(weight = w) })
    }

    /**
     * Move [paneId] to [slot] (a drag-and-drop drop). Removal happens first, so the slot is
     * interpreted against the layout without the dragged pane: when its old row collapses, rows
     * below shift up and the target index follows them. Dropping a pane back onto its own position
     * lands it where it already was.
     */
    fun move(paneId: String, slot: PaneSlot): PaneLayout {
        val from = positionOf(paneId) ?: return this
        // A pane alone in its row takes the row with it, which shifts every row below up by one.
        val rowCollapses = rows[from.first].cells.size == 1
        val shifted = when (slot) {
            is PaneSlot.NewRow -> PaneSlot.NewRow(if (rowCollapses && slot.row > from.first) slot.row - 1 else slot.row)
            is PaneSlot.InRow -> PaneSlot.InRow(
                row = if (rowCollapses && slot.row > from.first) slot.row - 1 else slot.row,
                // Within the same row the pane's own cell is gone, so slots to its right shift left.
                column = if (!rowCollapses && slot.row == from.first && slot.column > from.second) slot.column - 1 else slot.column,
            )
        }
        // size <= 1 can't happen here (the pane exists), so remove() always takes effect.
        return remove(paneId).insert(paneId, shifted)
    }

    /**
     * Swap the session behind a pane, keeping its place and size — used when a pane is pointed at
     * another host and gets a fresh session object in the same slot.
     */
    fun replace(paneId: String, newPaneId: String): PaneLayout {
        if (paneId !in this) return this
        return PaneLayout(
            rows.map { row -> row.copy(cells = row.cells.map { if (it.paneId == paneId) it.copy(paneId = newPaneId) else it }) },
        )
    }

    /**
     * Drag the horizontal divider under row [boundary] by [delta] (a share of the tab's height,
     * positive = down). Only the two rows around it change, so a resize never disturbs the rest of
     * the grid; neither side goes below [MIN_PANE_WEIGHT].
     */
    fun resizeRows(boundary: Int, delta: Float): PaneLayout {
        val resized = resizeAt(rows.map { it.weight }, boundary, delta) ?: return this
        return PaneLayout(rows.mapIndexed { i, row -> row.copy(weight = resized[i]) })
    }

    /**
     * Drag the vertical divider after cell [boundary] of row [row] by [delta] (a share of the row's
     * width, positive = right). Other rows are untouched.
     */
    fun resizeCells(row: Int, boundary: Int, delta: Float): PaneLayout {
        val target = rows.getOrNull(row) ?: return this
        val resized = resizeAt(target.cells.map { it.weight }, boundary, delta) ?: return this
        val cells = target.cells.mapIndexed { i, cell -> cell.copy(weight = resized[i]) }
        return PaneLayout(rows.toMutableList().apply { set(row, target.copy(cells = cells)) })
    }

    /**
     * Where the "add pane" button puts the next one, without the user aiming at a slot: the second
     * pane goes beside the first (the side-by-side shape a split has always meant here), and after
     * that a short row is filled before a new one is started — so four panes land as a 2×2 grid
     * rather than four stripes.
     */
    fun defaultSlot(): PaneSlot {
        if (size == 1) return PaneSlot.InRow(row = 0, column = 1)
        val last = rows.lastIndex
        return if (rows[last].cells.size < rows.first().cells.size) {
            PaneSlot.InRow(row = last, column = rows[last].cells.size)
        } else {
            PaneSlot.NewRow(rows.size)
        }
    }

    /** (row, column) of [paneId], or `null` when it isn't placed. */
    private fun positionOf(paneId: String): Pair<Int, Int>? {
        rows.forEachIndexed { r, row ->
            val c = row.cells.indexOfFirst { it.paneId == paneId }
            if (c >= 0) return r to c
        }
        return null
    }

    /**
     * Insert without the guards of [add]: the new pane takes an even share of its container
     * (`1/n`) and the ones already there are scaled down to fit, so a grid the user has resized by
     * hand keeps its proportions instead of snapping back to equal.
     */
    private fun insert(paneId: String, slot: PaneSlot): PaneLayout = when (slot) {
        is PaneSlot.NewRow -> {
            val share = 1f / (rows.size + 1)
            val scaled = rows.map { it.copy(weight = it.weight * (1f - share)) }.toMutableList()
            scaled.add(slot.row.coerceIn(0, rows.size), PaneRow(listOf(PaneCell(paneId, 1f)), share))
            PaneLayout(floored(scaled, { it.weight }) { row, w -> row.copy(weight = w) })
        }
        is PaneSlot.InRow -> {
            val index = slot.row.coerceIn(0, rows.size - 1)
            val row = rows[index]
            val share = 1f / (row.cells.size + 1)
            val cells = row.cells.map { it.copy(weight = it.weight * (1f - share)) }.toMutableList()
            cells.add(slot.column.coerceIn(0, row.cells.size), PaneCell(paneId, share))
            val fitted = floored(cells, { it.weight }) { cell, w -> cell.copy(weight = w) }
            PaneLayout(rows.toMutableList().apply { set(index, row.copy(cells = fitted)) })
        }
    }

    companion object {
        /** The layout of a tab that has only its primary pane. */
        fun of(paneId: String): PaneLayout = PaneLayout(listOf(PaneRow(listOf(PaneCell(paneId, 1f)), 1f)))
    }
}

/**
 * Weights with [delta] moved across the divider after index [boundary], clamped so neither
 * neighbour drops below [MIN_PANE_WEIGHT]. `null` when there is no such divider (a single-element
 * axis has none), which callers turn into a no-op.
 */
private fun resizeAt(weights: List<Float>, boundary: Int, delta: Float): List<Float>? {
    if (boundary !in 0..weights.size - 2) return null
    val pair = weights[boundary] + weights[boundary + 1]
    val first = (weights[boundary] + delta).coerceIn(MIN_PANE_WEIGHT, pair - MIN_PANE_WEIGHT)
    return weights.toMutableList().apply {
        set(boundary, first)
        set(boundary + 1, pair - first)
    }
}

/**
 * Lifts anything under [MIN_PANE_WEIGHT] back up to it and takes the difference from the panes
 * above the floor, proportionally. Scaling existing panes down to make room for a new one can push
 * an already-narrow one under the floor (a row squeezed by hand, then dropped into), and [resizeAt]
 * only clamps what a divider drag touches. At [MAX_PANES] the floors always fit, so one pass over
 * the offenders settles it; the loop is a guard, not an algorithm.
 */
private fun <T> floored(items: List<T>, weightOf: (T) -> Float, copy: (T, Float) -> T): List<T> {
    var current = items
    repeat(items.size) {
        if (current.none { weightOf(it) < MIN_PANE_WEIGHT }) return current
        val room = 1f - current.count { weightOf(it) < MIN_PANE_WEIGHT } * MIN_PANE_WEIGHT
        val above = current.filter { weightOf(it) >= MIN_PANE_WEIGHT }.sumOf { weightOf(it).toDouble() }.toFloat()
        current = current.map {
            val w = weightOf(it)
            when {
                w < MIN_PANE_WEIGHT -> copy(it, MIN_PANE_WEIGHT)
                above > 0f -> copy(it, w / above * room)
                else -> copy(it, room / current.size)
            }
        }
    }
    return current
}

/**
 * Rescales [items] so their weights sum to 1 again after one of them was taken out, keeping their
 * proportions. A total of zero (only reachable if a caller built a degenerate layout) falls back to
 * an even split rather than dividing by it.
 */
private fun <T> rescaled(items: List<T>, weightOf: (T) -> Float, copy: (T, Float) -> T): List<T> {
    val total = items.sumOf { weightOf(it).toDouble() }.toFloat()
    return if (total <= 0f) items.map { copy(it, 1f / items.size) } else items.map { copy(it, weightOf(it) / total) }
}
