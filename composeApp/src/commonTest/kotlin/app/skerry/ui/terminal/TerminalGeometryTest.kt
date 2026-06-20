package app.skerry.ui.terminal

import androidx.compose.ui.geometry.Offset
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalGeometryTest {

    // Ячейка 8×18 px. Координаты указателя уже приходят в системе координат контента терминала
    // (после verticalScroll и padding в цепочке модификаторов), поэтому маппинг — чистое деление.
    private val metrics = TerminalMetrics(cellWidth = 8f, cellHeight = 18f)

    @Test
    fun `offset inside the first cell maps to origin`() {
        assertEquals(TerminalPos(0, 0), cellAtOffset(x = 1f, y = 2f, metrics = metrics))
    }

    @Test
    fun `column advances every cell width`() {
        // x = 2.5 ячейки → колонка 2 (floor).
        assertEquals(TerminalPos(0, 2), cellAtOffset(x = 20f, y = 2f, metrics = metrics))
    }

    @Test
    fun `row advances every cell height`() {
        // y = 1.5 строки → строка 1.
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
        // start = (row 1, col 2): left=2*8, top=1*18, размер ячейки 8×18.
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
        // start = (row 1, col 2): нижний-левый угол ячейки = (col*cw, (row+1)*ch).
        // end   = (row 3, col 0) — эксклюзивная граница: правый край последнего символа = (col*cw, (row+1)*ch).
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
        // около старта (16, 36) в пределах радиуса.
        assertEquals(
            SelectionHandle.START,
            hitTestSelectionHandle(Offset(18f, 40f), sel, metrics, radiusPx = 20f),
        )
    }

    @Test
    fun `hit test picks the end handle near its anchor`() {
        val sel = TerminalSelection(TerminalPos(1, 2), TerminalPos(3, 4))
        // около конца (32, 72).
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
        // Короткое выделение: ручки рядом, точка ближе к концу.
        val sel = TerminalSelection(TerminalPos(0, 0), TerminalPos(0, 4))
        // start anchor = (0, 18), end anchor = (32, 18). Точка x=30 ближе к концу.
        assertEquals(
            SelectionHandle.END,
            hitTestSelectionHandle(Offset(30f, 18f), sel, metrics, radiusPx = 40f),
        )
    }
}
