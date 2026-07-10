package app.skerry.ui.terminal

import androidx.compose.ui.geometry.Offset
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalGeometryTest {

    // 8x18 px cell. Pointer coordinates already arrive in the terminal content's coordinate
    // space (after verticalScroll and padding in the modifier chain), so mapping is a plain division.
    private val metrics = TerminalMetrics(cellWidth = 8f, cellHeight = 18f)

    @Test
    fun `offset inside the first cell maps to origin`() {
        assertEquals(TerminalPos(0, 0), cellAtOffset(x = 1f, y = 2f, metrics = metrics))
    }

    @Test
    fun `column advances every cell width`() {
        // x = 2.5 cells -> column 2 (floor).
        assertEquals(TerminalPos(0, 2), cellAtOffset(x = 20f, y = 2f, metrics = metrics))
    }

    @Test
    fun `row advances every cell height`() {
        // y = 1.5 rows -> row 1.
        assertEquals(TerminalPos(1, 0), cellAtOffset(x = 1f, y = 27f, metrics = metrics))
    }

    @Test
    fun `negative coordinates clamp to origin`() {
        assertEquals(TerminalPos(0, 0), cellAtOffset(x = -5f, y = -5f, metrics = metrics))
    }

    @Test
    fun `selection anchor rect covers the start cell in content pixels`() {
        val sel = TerminalSelection(TerminalPos(1, 2), TerminalPos(3, 0))
        val r = selectionAnchorRect(sel, metrics)
        // start = (row 1, col 2): left=2*8, top=1*18, cell size 8x18.
        assertEquals(16f, r.left)
        assertEquals(18f, r.top)
        assertEquals(24f, r.right)
        assertEquals(36f, r.bottom)
    }

    @Test
    fun `selection anchor uses normalized start for a backwards selection`() {
        val sel = TerminalSelection(TerminalPos(3, 0), TerminalPos(1, 2))
        val r = selectionAnchorRect(sel, metrics)
        assertEquals(16f, r.left)
        assertEquals(18f, r.top)
    }

    @Test
    fun `handle anchors sit at the bottom corners of the selection bounds`() {
        // start = (row 1, col 2): bottom-left corner of the cell = (col*cw, (row+1)*ch).
        // end   = (row 3, col 0), an exclusive bound: right edge of the last char = (col*cw, (row+1)*ch).
        val sel = TerminalSelection(TerminalPos(1, 2), TerminalPos(3, 0))
        val (start, end) = selectionHandleAnchors(sel, metrics)
        assertEquals(Offset(16f, 36f), start)
        assertEquals(Offset(0f, 72f), end)
    }

    @Test
    fun `handle anchors normalize a backwards selection`() {
        val sel = TerminalSelection(TerminalPos(3, 0), TerminalPos(1, 2))
        val (start, end) = selectionHandleAnchors(sel, metrics)
        assertEquals(Offset(16f, 36f), start)
        assertEquals(Offset(0f, 72f), end)
    }

    @Test
    fun `hit test picks the start handle near its anchor`() {
        val sel = TerminalSelection(TerminalPos(1, 2), TerminalPos(3, 4))
        // near the start anchor (16, 36), within radius.
        assertEquals(
            SelectionHandle.START,
            hitTestSelectionHandle(Offset(18f, 40f), sel, metrics, radiusPx = 20f),
        )
    }

    @Test
    fun `hit test picks the end handle near its anchor`() {
        val sel = TerminalSelection(TerminalPos(1, 2), TerminalPos(3, 4))
        // near the end anchor (32, 72).
        assertEquals(
            SelectionHandle.END,
            hitTestSelectionHandle(Offset(34f, 70f), sel, metrics, radiusPx = 20f),
        )
    }

    @Test
    fun `hit test returns null when the point is far from both handles`() {
        val sel = TerminalSelection(TerminalPos(1, 2), TerminalPos(3, 4))
        assertNull(hitTestSelectionHandle(Offset(200f, 200f), sel, metrics, radiusPx = 20f))
    }

    @Test
    fun `hit test prefers the nearer handle when both are in range`() {
        // Short selection: handles are close together, the point is nearer the end.
        val sel = TerminalSelection(TerminalPos(0, 0), TerminalPos(0, 4))
        // start anchor = (0, 18), end anchor = (32, 18). Point x=30 is nearer the end.
        assertEquals(
            SelectionHandle.END,
            hitTestSelectionHandle(Offset(30f, 18f), sel, metrics, radiusPx = 40f),
        )
    }

    @Test
    fun `grid size divides the padded content area by the cell size`() {
        // padding 14 on both sides: content = 10*8 wide, 5*18 tall.
        val size = gridSizeFor(
            viewportWidthPx = 10 * 8f + 28f,
            viewportHeightPx = 5 * 18f + 28f,
            paddingPx = 14f,
            metrics = metrics,
        )
        assertEquals(10, size.cols)
        assertEquals(5, size.rows)
        // Pixel sizes are the content size (no padding), for the PTY report.
        assertEquals(80, size.widthPx)
        assertEquals(90, size.heightPx)
    }

    @Test
    fun `grid size floors a partial trailing cell`() {
        // Content 84px / 8 = 10.5 columns -> 10 (floor); 99px / 18 = 5.5 -> 5.
        val size = gridSizeFor(
            viewportWidthPx = 84f + 28f,
            viewportHeightPx = 99f + 28f,
            paddingPx = 14f,
            metrics = metrics,
        )
        assertEquals(10, size.cols)
        assertEquals(5, size.rows)
    }

    @Test
    fun `grid size never drops below one cell`() {
        // Viewport smaller than the padding: content goes negative, clamp to 1x1.
        val size = gridSizeFor(
            viewportWidthPx = 10f,
            viewportHeightPx = 10f,
            paddingPx = 14f,
            metrics = metrics,
        )
        assertEquals(1, size.cols)
        assertEquals(1, size.rows)
    }

    @Test
    fun `stick to bottom at or within slack of the previous bottom`() {
        assertTrue(shouldStickToBottom(value = 1000, previousMax = 1000, slackPx = 18))
        assertTrue(shouldStickToBottom(value = 985, previousMax = 1000, slackPx = 18))
    }

    @Test
    fun `do not stick to bottom when scrolled up into history`() {
        assertFalse(shouldStickToBottom(value = 400, previousMax = 1000, slackPx = 18))
    }

    @Test
    fun `stick to bottom while content is shorter than the viewport`() {
        assertTrue(shouldStickToBottom(value = 0, previousMax = 0, slackPx = 18))
    }
}
