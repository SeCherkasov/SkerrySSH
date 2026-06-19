package app.skerry.ui.terminal

import app.skerry.shared.terminal.TerminalPos
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
