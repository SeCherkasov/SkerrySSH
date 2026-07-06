package app.skerry.ui.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection

/**
 * Monospace terminal cell size in pixels. Measured once on the UI side (mono-char advance +
 * lineHeight) and passed in here, so coordinate conversion stays pure testable arithmetic.
 */
data class TerminalMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
)

/**
 * Columns and rows that fit in the terminal viewport. Padding on both sides ([paddingPx]) is
 * subtracted from the viewport size, and the remainder is divided by cell size (floored — a partial
 * cell doesn't count). Never returns below 1×1: a PTY needs at least one cell. The pixel sizes in
 * [PtySize] are the content area (padding excluded), usable by the server for graphics.
 */
fun gridSizeFor(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    paddingPx: Float,
    metrics: TerminalMetrics,
): PtySize {
    val contentW = (viewportWidthPx - 2 * paddingPx).coerceAtLeast(0f)
    val contentH = (viewportHeightPx - 2 * paddingPx).coerceAtLeast(0f)
    val cols = (contentW / metrics.cellWidth).toInt().coerceAtLeast(1)
    val rows = (contentH / metrics.cellHeight).toInt().coerceAtLeast(1)
    return PtySize(cols = cols, rows = rows, widthPx = contentW.toInt(), heightPx = contentH.toInt())
}

/**
 * Converts a pointer position into a grid cell. Coordinates already arrive in the terminal content's
 * coordinate system: `pointerInput` sits after `verticalScroll` and `padding` in the modifier chain,
 * so Compose gives an offset relative to the text (scroll accounted for, padding excluded) — only
 * dividing by cell size remains. Row/column are floored; negative coordinates clamp to zero. Row isn't
 * upper-bounded here; the caller maps it against the screen (extract clamps past the last row).
 */
fun cellAtOffset(x: Float, y: Float, metrics: TerminalMetrics): TerminalPos {
    val col = (x / metrics.cellWidth).toInt().coerceAtLeast(0)
    val row = (y / metrics.cellHeight).toInt().coerceAtLeast(0)
    return TerminalPos(row, col)
}

/**
 * Rect of the selection's starting cell in content pixels — anchor for the system text menu
 * (`LocalTextToolbar.showMenu` needs a rect to show "Copy" above). Uses the normalized top-left bound
 * of [TerminalSelection.start]; the UI maps this rect into window coordinates.
 */
fun selectionAnchorRect(selection: TerminalSelection, metrics: TerminalMetrics): Rect {
    val s = selection.start
    val left = s.col * metrics.cellWidth
    val top = s.row * metrics.cellHeight
    return Rect(left = left, top = top, right = left + metrics.cellWidth, bottom = top + metrics.cellHeight)
}

/** Which selection boundary a touch handle drags: top-left (start) or bottom-right (end). */
enum class SelectionHandle { START, END }

/**
 * Anchor points of the two selection touch handles in content pixels — where the draggable "drop"
 * handles (messenger-style) attach. Uses the bottom corners of the normalized bounds: start — bottom-
 * left of the first cell, end — bottom-right of the last character ([TerminalSelection.end] is
 * exclusive, so its column is the right edge). The UI draws the handle below the anchor and maps the
 * coordinate into the window accounting for scroll.
 */
fun selectionHandleAnchors(selection: TerminalSelection, metrics: TerminalMetrics): Pair<Offset, Offset> {
    val s = selection.start
    val e = selection.end
    val start = Offset(s.col * metrics.cellWidth, (s.row + 1) * metrics.cellHeight)
    val end = Offset(e.col * metrics.cellWidth, (e.row + 1) * metrics.cellHeight)
    return start to end
}

/**
 * Whether a finger hit one of the selection touch handles. Compares [point] (content coordinates) to
 * the handle anchors ([selectionHandleAnchors]) within [radiusPx]; if both are within range, returns
 * the closer one; otherwise the one in range, or `null` (gesture falls through to long-press/scroll).
 */
fun hitTestSelectionHandle(
    point: Offset,
    selection: TerminalSelection,
    metrics: TerminalMetrics,
    radiusPx: Float,
): SelectionHandle? {
    if (selection.isEmpty) return null
    val (start, end) = selectionHandleAnchors(selection, metrics)
    val dStart = (point - start).getDistance()
    val dEnd = (point - end).getDistance()
    val startHit = dStart <= radiusPx
    val endHit = dEnd <= radiusPx
    return when {
        startHit && endHit -> if (dStart <= dEnd) SelectionHandle.START else SelectionHandle.END
        startHit -> SelectionHandle.START
        endHit -> SelectionHandle.END
        else -> null
    }
}
