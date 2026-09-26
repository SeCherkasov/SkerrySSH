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

    @Test
    fun `the same link opened again still shares the cell`() {
        // Tools re-open OSC 8 per span (per word, per wrapped line) with the same URI.
        val link = "$esc]8;;https://example.com${esc}\\"
        val close = "$esc]8;;${esc}\\"
        val row = emulate("${link}a$close ${link}a$close").lines[0]
        assertSame(row[0], row[2])
    }

    @Test
    fun `wide characters in one rendition share the continuation half`() {
        val row = emulate("中文").lines[0]
        assertEquals(CellWidth.Continuation, row[1].width)
        assertSame(row[1], row[3])
    }

    @Test
    fun `a different link opened next reaches its own cells`() {
        val row = emulate("$esc]8;;https://a.example${esc}\\a$esc]8;;https://b.example${esc}\\a").lines[0]
        assertEquals("https://a.example", row[0].hyperlink)
        assertEquals("https://b.example", row[1].hyperlink)
    }

    @Test
    fun `a rendition or link change reaches the continuation half`() {
        val link = "$esc]8;;https://example.com${esc}\\"
        val row = emulate("中$esc[41m中${link}中").lines[0]
        assertEquals(TermColor.Default, row[1].style.bg)
        assertEquals(TermColor.Red, row[3].style.bg)
        assertNull(row[3].hyperlink)
        assertEquals(TermColor.Red, row[5].style.bg)
        assertEquals("https://example.com", row[5].hyperlink)
    }
}
