package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A render snapshot copies a row only when it changed: the renderer memoizes per row by identity,
 * and a status-line redraw used to recopy — and re-lay-out — every row of the screen.
 */
class TerminalRowSnapshotTest {

    private val esc = 27.toChar().toString()

    private fun TerminalEmulator.feed(text: String) = feed(text.encodeToByteArray())

    @Test
    fun `a row nothing wrote to keeps its snapshot instance`() {
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("top\r\nbottom")
        val before = emu.lines
        emu.feed("$esc[2;1Hchanged")
        val after = emu.lines
        assertNotSame(before, after)
        assertSame(before[0], after[0])
        assertNotSame(before[1], after[1])
        assertEquals("changed", after[1].joinToString("") { it.text }.trimEnd())
    }

    @Test
    fun `a wrap flag change is a change`() {
        val emu = TerminalEmulator(cols = 3, rows = 3)
        emu.feed("abc")
        val before = emu.lines[0]
        emu.feed("d")
        val after = emu.lines[0]
        assertNotSame(before, after)
        assertTrue(after.wrapsToNextRow())
    }

    @Test
    fun `a row keeps its snapshot instance when it scrolls into history`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("first\r\nsecond")
        val published = emu.lines[0]
        emu.feed("\r\nthird")
        val lines = emu.lines
        assertEquals(3, lines.size)
        assertSame(published, lines[0])
    }

    @Test
    fun `a published snapshot does not change under later writes`() {
        val emu = TerminalEmulator(cols = 10, rows = 2)
        emu.feed("one")
        val row = emu.lines[0]
        emu.feed("\rtwo")
        assertEquals("one", row.joinToString("") { it.text }.trimEnd())
    }

    @Test
    fun `history reflows after rows retired without a published snapshot`() {
        val emu = TerminalEmulator(cols = 6, rows = 2)
        emu.feed("abcdefgh\r\nxy\r\nz")
        emu.resize(newCols = 12, newRows = 2)
        val text = emu.lines.map { row -> row.joinToString("") { it.text }.trimEnd() }
        assertEquals(listOf("abcdefgh", "xy", "z"), text)
    }
}
