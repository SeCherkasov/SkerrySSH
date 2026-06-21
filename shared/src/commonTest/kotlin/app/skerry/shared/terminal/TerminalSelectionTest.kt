package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalSelectionTest {

    /** Сетка из строк-строчек: каждый символ — ячейка стиля по умолчанию. */
    private fun grid(vararg rows: String): List<List<TermCell>> =
        rows.map { row -> row.map { TermCell(it) } }

    @Test
    fun `single line selection extracts the spanned cells`() {
        val sel = TerminalSelection(TerminalPos(0, 1), TerminalPos(0, 4))
        // "hello" → конец эксклюзивен: колонки [1,4) = "ell".
        assertEquals("ell", sel.extract(grid("hello")))
    }

    @Test
    fun `anchor after focus is normalized (backwards drag)`() {
        val forward = TerminalSelection(TerminalPos(0, 1), TerminalPos(0, 4))
        val backward = TerminalSelection(TerminalPos(0, 4), TerminalPos(0, 1))
        assertEquals(forward.extract(grid("hello")), backward.extract(grid("hello")))
    }

    @Test
    fun `multi line selection joins rows with newline`() {
        // С (0,3) до (2,2): хвост первой строки + вся средняя + голова последней.
        val sel = TerminalSelection(TerminalPos(0, 3), TerminalPos(2, 2))
        val text = sel.extract(grid("abcdef", "middle", "xyz"))
        assertEquals("def\nmiddle\nxy", text)
    }

    @Test
    fun `trailing spaces on a fully spanned line are trimmed`() {
        // Полностью охваченная строка отдаётся без хвостовых пробелов.
        val sel = TerminalSelection(TerminalPos(0, 0), TerminalPos(1, 3))
        val text = sel.extract(grid("ab    ", "cde"))
        assertEquals("ab\ncde", text)
    }

    @Test
    fun `empty selection (anchor equals focus) yields empty string`() {
        val sel = TerminalSelection(TerminalPos(0, 2), TerminalPos(0, 2))
        assertEquals("", sel.extract(grid("hello")))
        assertTrue(sel.isEmpty)
    }

    @Test
    fun `contains reports cells inside the linear range`() {
        val sel = TerminalSelection(TerminalPos(0, 2), TerminalPos(2, 1))
        // Первая строка: с колонки 2 и дальше.
        assertFalse(sel.contains(0, 1))
        assertTrue(sel.contains(0, 2))
        // Средняя строка целиком.
        assertTrue(sel.contains(1, 0))
        assertTrue(sel.contains(1, 99))
        // Последняя строка: до колонки 1 эксклюзивно → колонка 0 внутри, 1 уже нет.
        assertTrue(sel.contains(2, 0))
        assertFalse(sel.contains(2, 1))
        // Вне диапазона строк.
        assertFalse(sel.contains(3, 0))
    }

    @Test
    fun `extract clamps columns beyond the row length`() {
        // Выделение уходит за конец короткой строки — берём что есть, без падения.
        val sel = TerminalSelection(TerminalPos(0, 0), TerminalPos(0, 99))
        assertEquals("hi", sel.extract(grid("hi")))
    }

    @Test
    fun `line selection spans the whole row under the position`() {
        val screen = grid("first", "second", "third")
        val sel = lineSelectionAt(screen, TerminalPos(1, 3))
        assertEquals(TerminalPos(1, 0), sel.start)
        assertEquals(TerminalPos(1, 6), sel.end) // "second" = 6 ячеек, конец эксклюзивен
        assertEquals("second", sel.extract(screen))
    }

    @Test
    fun `line selection clamps row beyond the grid`() {
        val screen = grid("only")
        val sel = lineSelectionAt(screen, TerminalPos(9, 0))
        assertEquals("only", sel.extract(screen))
    }
}
