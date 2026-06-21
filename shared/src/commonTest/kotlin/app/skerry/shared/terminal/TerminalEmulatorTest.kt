package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {

    // ESC/BEL задаём числом — никаких невидимых управляющих байтов в исходнике.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    private fun emulate(cols: Int = 80, rows: Int = 24, vararg chunks: String): TerminalEmulator {
        val emu = TerminalEmulator(cols = cols, rows = rows)
        chunks.forEach { emu.feed(it.encodeToByteArray()) }
        return emu
    }

    /** Видимый текст экрана: строки через \n, хвостовые пробелы и пустые строки обрезаны. */
    private fun TerminalEmulator.asText(): String =
        lines.joinToString("\n") { row -> row.joinToString("") { it.char.toString() }.trimEnd() }.trimEnd('\n')

    // --- Базовая печать ----------------------------------------------------

    @Test
    fun `plain text fills one line`() {
        assertEquals("hello", emulate(chunks = arrayOf("hello")).asText())
    }

    @Test
    fun `grid has fixed dimensions`() {
        val emu = emulate(cols = 40, rows = 10, chunks = arrayOf("hi"))
        assertEquals(10, emu.lines.size)
        assertTrue(emu.lines.all { it.size == 40 })
    }

    @Test
    fun `crlf starts a new line at column zero`() {
        assertEquals("ab\ncd", emulate(chunks = arrayOf("ab\r\ncd")).asText())
    }

    @Test
    fun `bare lf keeps the column (staircase)`() {
        assertEquals("ab\n  cd", emulate(chunks = arrayOf("ab\ncd")).asText())
    }

    @Test
    fun `carriage return moves cursor to column zero and overwrites`() {
        assertEquals("Xbc", emulate(chunks = arrayOf("abc\rX")).asText())
    }

    @Test
    fun `backspace moves cursor left and next char overwrites`() {
        assertEquals("abX", emulate(chunks = arrayOf("abc\bX")).asText())
    }

    @Test
    fun `tab advances to next multiple of eight`() {
        assertEquals("a       b", emulate(chunks = arrayOf("a\tb")).asText())
    }

    // --- Автоперенос (DECAWM) ----------------------------------------------

    @Test
    fun `printing past the last column wraps to the next line`() {
        // cols=3: "abc" заполняет строку, "d" переносится на следующую (pending-wrap).
        assertEquals("abc\nd", emulate(cols = 3, rows = 4, chunks = arrayOf("abcd")).asText())
    }

    @Test
    fun `autowrap off keeps overwriting the last column`() {
        assertEquals("abd", emulate(cols = 3, rows = 4, chunks = arrayOf("$esc[?7l", "abcd")).asText())
    }

    // --- SGR ---------------------------------------------------------------

    @Test
    fun `sgr sets foreground color until reset`() {
        val emu = emulate(chunks = arrayOf("$esc[31mR${esc}[0mG"))
        assertEquals(TermColor.Red, emu.lines[0][0].style.fg)
        assertEquals(TermColor.Default, emu.lines[0][1].style.fg)
        assertEquals("RG", emu.asText())
    }

    @Test
    fun `sgr bold flag is tracked`() {
        val emu = emulate(chunks = arrayOf("$esc[1mB${esc}[22mn"))
        assertTrue(emu.lines[0][0].style.bold)
        assertFalse(emu.lines[0][1].style.bold)
    }

    @Test
    fun `sgr bright foreground 91 maps to bright red`() {
        assertEquals(TermColor.BrightRed, emulate(chunks = arrayOf("$esc[91mR")).lines[0][0].style.fg)
    }

    @Test
    fun `sgr 256-color indexed`() {
        assertEquals(TermColor.Indexed(201), emulate(chunks = arrayOf("$esc[38;5;201mX")).lines[0][0].style.fg)
    }

    @Test
    fun `sgr truecolor rgb`() {
        assertEquals(TermColor.Rgb(10, 20, 30), emulate(chunks = arrayOf("$esc[38;2;10;20;30mX")).lines[0][0].style.fg)
    }

    @Test
    fun `sgr colon-form truecolor is parsed`() {
        assertEquals(TermColor.Rgb(1, 2, 3), emulate(chunks = arrayOf("$esc[38:2:1:2:3mX")).lines[0][0].style.fg)
    }

    @Test
    fun `sgr attributes underline italic inverse strike`() {
        val s = emulate(chunks = arrayOf("$esc[3;4;7;9mX")).lines[0][0].style
        assertTrue(s.italic && s.underline && s.inverse && s.strikethrough)
    }

    // --- Адресация курсора -------------------------------------------------

    @Test
    fun `cup positions cursor absolutely`() {
        // ESC[2;3H ставит курсор в строку 2, колонку 3 (1-based); печать туда.
        assertEquals("\n  X", emulate(chunks = arrayOf("$esc[2;3HX")).asText())
    }

    @Test
    fun `cursor up down forward back reposition`() {
        assertEquals("aXc", emulate(chunks = arrayOf("abc", "$esc[2D", "X")).asText())
    }

    @Test
    fun `vpa sets row absolutely`() {
        assertEquals("\n\nX", emulate(chunks = arrayOf("$esc[3dX")).asText())
    }

    // --- Стирание ----------------------------------------------------------

    @Test
    fun `erase to end of line clears from cursor`() {
        assertEquals("abc", emulate(chunks = arrayOf("abcdef", "$esc[3D", "$esc[0K")).asText())
    }

    @Test
    fun `erase display 2J clears the screen but keeps grid size`() {
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2", "$esc[2J"))
        assertEquals("", emu.asText())
        assertEquals(4, emu.lines.size)
    }

    @Test
    fun `erase display 3 also clears scrollback`() {
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("a\r\nb\r\nc\r\nd"))
        assertTrue(emu.lines.size > 2) // накопился scrollback
        emu.feed("$esc[3J".encodeToByteArray())
        assertEquals(2, emu.lines.size)
    }

    // --- Вставка / удаление ------------------------------------------------

    @Test
    fun `insert chars shifts the rest right`() {
        assertEquals("abc  def", emulate(chunks = arrayOf("abcdef", "$esc[1;4H", "$esc[2@")).asText())
    }

    @Test
    fun `delete chars pulls the rest left`() {
        assertEquals("abcf", emulate(chunks = arrayOf("abcdef", "$esc[1;4H", "$esc[2P")).asText())
    }

    @Test
    fun `erase chars blanks in place`() {
        assertEquals("abc  f", emulate(chunks = arrayOf("abcdef", "$esc[1;4H", "$esc[2X")).asText())
    }

    @Test
    fun `insert line pushes lines down`() {
        assertEquals("A\n\nB\nC", emulate(cols = 4, rows = 4, chunks = arrayOf("A\r\nB\r\nC\r\nD", "$esc[2;1H", "$esc[L")).asText())
    }

    @Test
    fun `delete line pulls lines up`() {
        assertEquals("A\nC\nD", emulate(cols = 4, rows = 4, chunks = arrayOf("A\r\nB\r\nC\r\nD", "$esc[2;1H", "$esc[M")).asText())
    }

    // --- Прокрутка / регион ------------------------------------------------

    @Test
    fun `scrolling off the top feeds scrollback`() {
        val emu = emulate(cols = 4, rows = 3, chunks = arrayOf("a\r\nb\r\nc\r\nd"))
        assertEquals("a\nb\nc\nd", emu.asText())
        assertEquals(4, emu.lines.size) // 1 в scrollback + 3 экранных
    }

    @Test
    fun `scroll region confines scrolling`() {
        val emu = emulate(
            cols = 4, rows = 4,
            chunks = arrayOf("L0\r\nL1\r\nL2\r\nL3", "$esc[1;3r", "$esc[3;1H", "\n"),
        )
        // Регион строк 0..2 прокрутился (L0 ушла в scrollback), L3 вне региона остался.
        assertEquals("L0\nL1\nL2\n\nL3", emu.asText())
    }

    @Test
    fun `reverse index at top of region scrolls the region down`() {
        val emu = emulate(
            cols = 4, rows = 4,
            chunks = arrayOf("L0\r\nL1\r\nL2\r\nL3", "$esc[1;3r", "${esc}M"),
        )
        // Регион 0..2 прокручен вниз: пустая строка сверху, L2 вытеснена, L3 (вне региона) на месте.
        assertEquals("\nL0\nL1\nL3", emu.asText())
    }

    @Test
    fun `absolute cursor move cancels pending wrap`() {
        // cols=3: "abc" взводит pending-wrap; CUP в (2,2) должен его снять, иначе X переедет.
        assertEquals("abc\n X", emulate(cols = 3, rows = 4, chunks = arrayOf("abc", "$esc[2;2H", "X")).asText())
    }

    @Test
    fun `clearing all tab stops sends tab to the last column`() {
        // ESC[3g снимает все табстопы → следующий TAB прыгает в последнюю колонку.
        assertEquals("a        b", emulate(cols = 10, rows = 2, chunks = arrayOf("a", "$esc[3g", "\t", "b")).asText())
    }

    // --- Курсор save/restore ----------------------------------------------

    @Test
    fun `save and restore cursor with esc 7 and esc 8`() {
        // ESC7 сохраняет позицию после "AB"; затем уходим вниз и возвращаемся ESC8.
        assertEquals("ABX\n\nCD", emulate(chunks = arrayOf("AB", "${esc}7", "\r\n\r\nCD", "${esc}8", "X")).asText())
    }

    // --- Alt-screen --------------------------------------------------------

    @Test
    fun `alt screen hides primary and restores it on exit`() {
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("main"))
        emu.feed("$esc[?1049h".encodeToByteArray())
        assertTrue(emu.altScreen)
        emu.feed("$esc[H".encodeToByteArray())
        emu.feed("ALT".encodeToByteArray())
        assertEquals("ALT", emu.asText())
        emu.feed("$esc[?1049l".encodeToByteArray())
        assertFalse(emu.altScreen)
        assertEquals("main", emu.asText())
    }

    // --- Ответы DSR/DA -----------------------------------------------------

    @Test
    fun `device status report returns cursor position`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[2;3H".encodeToByteArray()) // курсор в (2,3)
        emu.feed("$esc[6n".encodeToByteArray())   // запрос позиции
        assertEquals("$esc[2;3R", replies.single())
    }

    @Test
    fun `primary device attributes are answered`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[c".encodeToByteArray())
        assertEquals("$esc[?1;2c", replies.single())
    }

    // --- OSC / bell --------------------------------------------------------

    @Test
    fun `osc sets the window title`() {
        val emu = emulate(chunks = arrayOf("$esc]0;my title${bel}X"))
        assertEquals("my title", emu.title)
        assertEquals("X", emu.asText())
    }

    @Test
    fun `bell triggers the callback`() {
        var rang = false
        TerminalEmulator(onBell = { rang = true }).feed(bel.encodeToByteArray())
        assertTrue(rang)
    }

    // --- Приватные режимы --------------------------------------------------

    @Test
    fun `application cursor keys mode off by default`() {
        assertFalse(TerminalEmulator().applicationCursorKeys)
    }

    @Test
    fun `decckm set and reset toggles application cursor keys`() {
        val emu = emulate(chunks = arrayOf("$esc[?1h"))
        assertTrue(emu.applicationCursorKeys)
        emu.feed("$esc[?1l".encodeToByteArray())
        assertFalse(emu.applicationCursorKeys)
    }

    @Test
    fun `cursor visibility toggles with mode 25`() {
        val emu = emulate(chunks = arrayOf("$esc[?25l"))
        assertFalse(emu.cursorVisible)
        emu.feed("$esc[?25h".encodeToByteArray())
        assertTrue(emu.cursorVisible)
    }

    @Test
    fun `mouse tracking and sgr encoding modes are tracked`() {
        val emu = emulate(chunks = arrayOf("$esc[?1002h", "$esc[?1006h"))
        assertEquals(MouseTracking.ButtonEvent, emu.mouseTracking)
        assertTrue(emu.mouseSgr)
        emu.feed("$esc[?1002l".encodeToByteArray())
        assertEquals(MouseTracking.Off, emu.mouseTracking)
    }

    @Test
    fun `bracketed paste mode is tracked`() {
        assertTrue(emulate(chunks = arrayOf("$esc[?2004h")).bracketedPaste)
    }

    @Test
    fun `unrelated private mode does not arm application cursor keys`() {
        assertFalse(emulate(chunks = arrayOf("$esc[?1049h$esc[?2004h")).applicationCursorKeys)
    }

    // --- Resize ------------------------------------------------------------

    @Test
    fun `resize changes dimensions and preserves visible text`() {
        val emu = emulate(cols = 80, rows = 24, chunks = arrayOf("hello"))
        emu.resize(100, 30)
        assertEquals(100, emu.cols)
        assertEquals(30, emu.rows)
        assertEquals(30, emu.lines.size)
        assertTrue(emu.lines.all { it.size == 100 })
        assertEquals("hello", emu.asText())
    }

    // --- Курсор: абсолютные индексы ----------------------------------------

    @Test
    fun `cursor row is absolute including scrollback`() {
        val emu = emulate(cols = 4, rows = 2, chunks = arrayOf("a\r\nb\r\nc"))
        // 1 строка ушла в scrollback, курсор на нижней экранной строке => абсолютно row 2.
        assertEquals(2, emu.cursorRow)
        assertEquals(1, emu.cursorCol)
    }

    // --- UTF-8 -------------------------------------------------------------

    @Test
    fun `utf8 multibyte split across feeds decodes to one cell`() {
        val emu = TerminalEmulator()
        emu.feed(byteArrayOf(0xD0.toByte()))
        emu.feed(byteArrayOf(0x9F.toByte()))
        assertEquals("П", emu.asText())
        assertEquals('П', emu.lines[0][0].char)
    }
}
