package app.skerry.ui.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import kotlin.math.roundToInt

/**
 * Monospace terminal cell size in pixels. Measured once on the UI side (mono-char advance +
 * lineHeight) and passed in here, so coordinate conversion stays pure testable arithmetic.
 */
data class TerminalMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
)

/** Bounds for [fitFontScale]: a recording is never blown up or shrunk past readability. */
private val FIT_FONT_SCALE_RANGE = 0.3f..4f

/**
 * Font scale that makes a fixed [cols]×[rows] grid fill the viewport, given [metrics] measured at
 * the unscaled font size. Used by the recording player: a recording has the geometry it was taken
 * at, so instead of re-flowing it into the pane (empty columns on the right in a wide window,
 * wrapped lines in a narrow one) the glyphs are scaled and the grid is kept.
 *
 * The smaller of the two axis ratios wins — the whole grid has to fit — and the result is clamped
 * to [FIT_FONT_SCALE_RANGE]. A viewport or grid that isn't measured yet gives 1 (no scaling).
 */
fun fitFontScale(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    paddingPx: Float,
    metrics: TerminalMetrics,
    cols: Int,
    rows: Int,
): Float {
    if (cols <= 0 || rows <= 0 || metrics.cellWidth <= 0f || metrics.cellHeight <= 0f) return 1f
    val contentW = viewportWidthPx - 2 * paddingPx
    val contentH = viewportHeightPx - 2 * paddingPx
    if (contentW <= 0f || contentH <= 0f) return 1f
    val scale = minOf(contentW / (cols * metrics.cellWidth), contentH / (rows * metrics.cellHeight))
    return scale.coerceIn(FIT_FONT_SCALE_RANGE.start, FIT_FONT_SCALE_RANGE.endInclusive)
}

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
 * Whether the viewport should snap to the bottom after new output: only when the user was already
 * at (or within [slackPx] of) the bottom *before* the content grew — scrolling up to read history
 * must survive streaming output, like in a real terminal. [previousMax] is the scroll max before
 * the new snapshot relaid out; [slackPx] absorbs sub-row jitter (a row or two of tolerance).
 */
fun shouldStickToBottom(value: Int, previousMax: Int, slackPx: Int): Boolean =
    value >= previousMax - slackPx

/**
 * Per-emission autoscroll decision for the terminal viewport, one instance per collect loop. The
 * first emission always snaps: a freshly (re)attached screen starts with scroll value 0 over
 * existing scrollback while the previous max is not yet meaningful, so [shouldStickToBottom] alone
 * would leave it stuck at the top of history. After that: user input ([inputVersion] changed)
 * snaps unconditionally (xterm's scroll-on-keypress), otherwise the sticky-bottom rule applies
 * against the max *before* this emission's relayout.
 */
class TerminalAutoScroll(initialInputVersion: Int, private val slackPx: Int) {
    private var previousMax: Int? = null
    private var lastInput = initialInputVersion

    /** Whether to snap to the bottom for this (scroll value, new scroll max, input version) emission. */
    fun shouldSnap(value: Int, max: Int, inputVersion: Int): Boolean {
        val typed = inputVersion != lastInput
        lastInput = inputVersion
        val previous = previousMax
        previousMax = max
        return typed || previous == null || shouldStickToBottom(value, previous, slackPx)
    }
}

/**
 * Buffer rows that intersect the viewport, with one row of slack on each side (a row at the scroll
 * boundary is partly visible and still drawn; clipping cuts the spill). Both the draw pass and the
 * search highlight take their row range from here — computed apart, they drifted by the slack row
 * and a match on it went unpainted. Empty when there is nothing in the buffer.
 */
fun visibleRowWindow(scrollPx: Float, viewportPx: Float, cellHeight: Float, rowCount: Int): IntRange {
    if (rowCount <= 0 || cellHeight <= 0f) return IntRange.EMPTY
    val first = ((scrollPx / cellHeight).toInt() - 1).coerceAtLeast(0)
    val last = (((scrollPx + viewportPx) / cellHeight).toInt() + 1).coerceAtMost(rowCount - 1)
    return first..last
}

/**
 * Scroll offset that brings buffer row [row] into view, or `null` if the whole row is already
 * visible (search navigation must not jog the viewport for a hit the user can already see).
 * [viewportPx] is the content height (padding excluded), [currentScroll]/[maxScroll] the live
 * scroll state. An off-screen row is centered vertically and clamped to the scroll range.
 */
fun scrollToRow(row: Int, currentScroll: Int, viewportPx: Float, maxScroll: Int, cellHeight: Float): Int? {
    val top = row * cellHeight
    val bottom = top + cellHeight
    if (top >= currentScroll && bottom <= currentScroll + viewportPx) return null
    val centered = top - (viewportPx - cellHeight) / 2f
    return centered.roundToInt().coerceIn(0, maxScroll.coerceAtLeast(0))
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
