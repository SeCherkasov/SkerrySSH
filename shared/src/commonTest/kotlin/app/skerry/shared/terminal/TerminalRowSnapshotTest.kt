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

    @Test
    fun `erasing a tail that is already blank keeps the row's snapshot`() {
        // A TUI redraws every frame ending each row with EL; a row whose tail is blank since the
        // previous frame must not be recopied.
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("abc$esc[K")
        val before = emu.lines[0]
        emu.feed("$esc[1;4H$esc[K")
        assertSame(before, emu.lines[0])
    }

    @Test
    fun `erasing a tail that holds text makes a new snapshot`() {
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("abcdef")
        val before = emu.lines[0]
        emu.feed("$esc[1;4H$esc[K")
        val after = emu.lines[0]
        assertNotSame(before, after)
        assertEquals("abc", after.joinToString("") { it.text }.trimEnd())
    }

    @Test
    fun `printing the same text over itself keeps the row's snapshot`() {
        // A TUI repaints unchanged regions every frame; the row must not be recopied for it.
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("abc")
        val before = emu.lines[0]
        emu.feed("\rabc")
        assertSame(before, emu.lines[0])
    }

    @Test
    fun `printing text that differs in one cell makes a new snapshot`() {
        val emu = TerminalEmulator(cols = 10, rows = 3)
        emu.feed("abc")
        val before = emu.lines[0]
        emu.feed("\rabX")
        assertNotSame(before, emu.lines[0])
        assertEquals("abX", emu.lines[0].joinToString("") { it.text }.trimEnd())
    }
}
