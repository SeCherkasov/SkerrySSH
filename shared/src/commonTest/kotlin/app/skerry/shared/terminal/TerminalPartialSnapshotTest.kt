package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TerminalPartialSnapshotTest {
    @Test
    fun `a single row stays immutable through mutation scrollback trim and resize`() {
        val terminal = TerminalEmulator(cols = 8, rows = 2, maxScrollback = 2)
        terminal.feed("first\r\nsecond\r\nthird".encodeToByteArray())
        val row = terminal.rowSnapshot(1)
        assertSame(terminal.lines[1], row)
        val text = row?.joinToString("") { it.text }
        terminal.feed("\rchanged\r\nfourth\r\nfifth".encodeToByteArray())
        terminal.resize(4, 3)
        assertEquals(text, row?.joinToString("") { it.text })
        for (index in terminal.lines.indices) assertSame(terminal.lines[index], terminal.rowSnapshot(index))
        assertNull(terminal.rowSnapshot(-1))
        assertNull(terminal.rowSnapshot(terminal.lines.size))
    }

    @Test
    fun `partial rows respect alternate screen and soft wraps`() {
        val terminal = TerminalEmulator(cols = 4, rows = 2)
        terminal.feed("abcdefgh".encodeToByteArray())
        assertEquals(true, terminal.rowSnapshot(0)?.wrapsToNextRow())
        terminal.feed("\u001b[?1049h\u001b[HALT".encodeToByteArray())
        assertEquals("ALT ", terminal.rowSnapshot(0)?.joinToString("") { it.text })
        assertNull(terminal.rowSnapshot(2))
        terminal.feed("\u001b[?1049l".encodeToByteArray())
        assertEquals("abcd", terminal.rowSnapshot(0)?.joinToString("") { it.text })
    }
}
