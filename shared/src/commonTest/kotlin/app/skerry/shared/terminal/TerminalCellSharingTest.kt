package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Printed cells are immutable, so a character printed twice in one rendition can be one instance —
 * streamed text allocated a TermCell per byte. These pin both halves: the sharing, and that a
 * rendition or link change still reaches the next cell.
 */
class TerminalCellSharingTest {

    private val esc = 27.toChar().toString()

    private fun emulate(vararg chunks: String): TerminalEmulator =
        TerminalEmulator(cols = 20, rows = 3).apply { chunks.forEach { feed(it.encodeToByteArray()) } }

    @Test
    fun `the same ASCII character in the same rendition is one cell`() {
        val row = emulate("aa").lines[0]
        assertSame(row[0], row[1])
    }

    @Test
    fun `the same box-drawing glyph in the same rendition is one cell`() {
        val row = emulate("──").lines[0]
        assertSame(row[0], row[1])
    }

    @Test
    fun `a rendition change reaches the next cell`() {
        val row = emulate("a$esc[31ma$esc[0ma").lines[0]
        assertEquals(TermColor.Default, row[0].style.fg)
        assertEquals(TermColor.Red, row[1].style.fg)
        assertEquals(TermColor.Default, row[2].style.fg)
        assertNotSame(row[0], row[1])
    }

    @Test
    fun `an equal rendition set again still shares the cell`() {
        val row = emulate("$esc[31ma$esc[31ma").lines[0]
        assertSame(row[0], row[1])
    }

    @Test
    fun `a hyperlink opened and closed between characters reaches only its own cells`() {
        val row = emulate("a$esc]8;;https://example.com${esc}\\a$esc]8;;${esc}\\a").lines[0]
        assertNull(row[0].hyperlink)
        assertEquals("https://example.com", row[1].hyperlink)
        assertNull(row[2].hyperlink)
    }
}
