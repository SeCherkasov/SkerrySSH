package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Control bytes inside a sequence, the sequence cancels, DECALN and what DECSC puts aside — the
 * corners vttest walks and a real stream hits when a sequence is split by a line discipline.
 */
class TerminalC0AndAlignmentTest {

    private val esc = 27.toChar().toString()

    private fun emulate(cols: Int = 10, rows: Int = 4, vararg chunks: String): TerminalEmulator =
        TerminalEmulator(cols = cols, rows = rows).apply { chunks.forEach { feed(it.encodeToByteArray()) } }

    private fun TerminalEmulator.text(row: Int) = lines[row].joinToString("") { it.text }.trimEnd()

    @Test
    fun `a control byte inside CSI runs and the sequence still completes`() {
        // vttest: "CSI 2 <BS> C" moves back one, then forward two.
        val emu = emulate(10, 4, "abcd$esc[2\bC")
        assertEquals(5, emu.cursorCol)
        assertEquals("abcd", emu.text(0))
    }

    @Test
    fun `a carriage return inside CSI runs and the sequence still completes`() {
        val emu = emulate(10, 4, "abcd$esc[\r3CX")
        assertEquals("abcX", emu.text(0))
    }

    @Test
    fun `a line feed inside CSI runs without leaking the rest as text`() {
        val emu = emulate(10, 4, "ab$esc[1\n;31mX")
        assertEquals("ab", emu.text(0))
        assertEquals("X", emu.text(1).trim())
        assertEquals(TermColor.Red, emu.lines[1].first { it.text == "X" }.style.fg)
    }

    @Test
    fun `CAN aborts a CSI sequence and nothing of it prints`() {
        val emu = emulate(10, 4, "a$esc[31${CAN}b")
        assertEquals("ab", emu.text(0))
        assertEquals(TermColor.Default, emu.lines[0][1].style.fg)
    }

    @Test
    fun `SUB aborts an escape sequence`() {
        val emu = emulate(10, 4, "a$esc${SUB}Db")
        assertEquals("aDb", emu.text(0))
    }

    @Test
    fun `CAN aborts an OSC and the title stays`() {
        val emu = emulate(10, 4, "$esc]0;first\u0007", "$esc]0;second${CAN}x")
        assertEquals("first", emu.title)
        assertEquals("x", emu.text(0))
    }

    @Test
    fun `CAN aborts a DCS and the tail prints`() {
        val emu = emulate(10, 4, "${esc}Pq#0${CAN}ok")
        assertEquals("ok", emu.text(0))
    }

    @Test
    fun `a control byte inside ESC runs and the escape completes`() {
        // ESC <CR> D: the CR runs, then IND moves down a row.
        val emu = emulate(10, 4, "abc$esc\rDx")
        assertEquals("abc", emu.text(0))
        assertEquals("x", emu.text(1))
    }

    @Test
    fun `a second ESC restarts the escape instead of dropping it`() {
        val emu = emulate(10, 4, "abc$esc$esc[2Dx")
        assertEquals("axc", emu.text(0))
    }

    @Test
    fun `DECALN fills the screen with E, homes the cursor and resets the margins`() {
        val emu = emulate(4, 3, "$esc[2;3r$esc[31m$esc#8")
        assertEquals(List(3) { "EEEE" }, (0 until 3).map { emu.text(it) })
        assertEquals(0, emu.cursorCol)
        assertEquals(0, emu.cursorRow)
        assertTrue(emu.lines.all { row -> row.all { it.style == TermStyle() } })
        // Margins are the whole screen again: a line feed at the bottom row scrolls everything.
        emu.feed("$esc[3;1H\nZ".encodeToByteArray())
        assertEquals(4, emu.lines.size) // the top row went to history, not just the region
        assertEquals("EEEE", emu.text(emu.lines.size - 3))
        assertEquals("Z", emu.text(emu.lines.size - 1))
    }

    @Test
    fun `DECRC restores origin mode`() {
        val emu = emulate(10, 5, "$esc[2;4r$esc[?6h${esc}7$esc[?6l${esc}8$esc[1;1HX")
        // Origin mode came back on, so row 1 is the top of the region (screen row 2).
        assertEquals("X", emu.text(1))
        assertEquals("", emu.text(0))
    }

    @Test
    fun `DECRC restores a pending wrap`() {
        val emu = emulate(4, 3, "abcd${esc}7\r${esc}8X")
        assertEquals("abcd", emu.text(0))
        assertEquals("X", emu.text(1))
    }

    @Test
    fun `DECRC drops a pending wrap the cursor no longer sits at`() {
        val emu = emulate(6, 3, "abcdef${esc}7")
        emu.resize(newCols = 4, newRows = 3)
        emu.feed("${esc}8".encodeToByteArray())
        val row = emu.cursorRow
        emu.feed("X".encodeToByteArray())
        assertEquals(row, emu.cursorRow, "the clamped cursor prints in place instead of wrapping")
    }

    @Test
    fun `CAN aborts a designation and its final byte prints`() {
        val emu = emulate(10, 4, "$esc(${CAN}0q")
        assertEquals("0q", emu.text(0))
    }

    @Test
    fun `ESC inside a designation starts a new sequence`() {
        val emu = emulate(10, 4, "ab$esc($esc[1Dq")
        assertEquals("aq", emu.text(0)) // CUB ran; q printed as ASCII, not line drawing
    }

    @Test
    fun `a control byte inside a designation runs and the designation completes`() {
        val emu = emulate(10, 4, "ab$esc(\r0q")
        assertEquals("\u2500b", emu.text(0)) // CR went home, then q is line drawing
    }

    @Test
    fun `a stray control byte inside an OSC is dropped and the OSC completes`() {
        val emu = emulate(10, 4, "$esc]0;ti\u0000t\ble\u0007x")
        assertEquals("title", emu.title)
        assertEquals("x", emu.text(0))
    }

    private companion object {
        const val CAN = '\u0018'
        const val SUB = '\u001a'
    }
}
