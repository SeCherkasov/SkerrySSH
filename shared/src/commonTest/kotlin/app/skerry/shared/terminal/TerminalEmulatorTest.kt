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
        lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }.trimEnd('\n')

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

    // --- Modern SGR: стили и цвет подчёркивания (4:x, 21, 58, 59) ----------

    @Test
    fun `plain sgr 4 is a single underline`() {
        val s = emulate(chunks = arrayOf("$esc[4mX")).lines[0][0].style
        assertEquals(UnderlineStyle.Single, s.underlineStyle)
        assertTrue(s.underline, "одиночное подчёркивание видно через булев флаг совместимости")
    }

    @Test
    fun `sgr 4 colon 3 is a curly underline`() {
        // 4:3 — curly/squiggly underline (диагностика компиляторов, ls --hyperlink и т.п.).
        val s = emulate(chunks = arrayOf("$esc[4:3mX")).lines[0][0].style
        assertEquals(UnderlineStyle.Curly, s.underlineStyle)
        assertTrue(s.underline)
    }

    @Test
    fun `sgr 4 colon variants select double dotted dashed`() {
        assertEquals(UnderlineStyle.Double, emulate(chunks = arrayOf("$esc[4:2mX")).lines[0][0].style.underlineStyle)
        assertEquals(UnderlineStyle.Dotted, emulate(chunks = arrayOf("$esc[4:4mX")).lines[0][0].style.underlineStyle)
        assertEquals(UnderlineStyle.Dashed, emulate(chunks = arrayOf("$esc[4:5mX")).lines[0][0].style.underlineStyle)
    }

    @Test
    fun `sgr 4 colon 0 clears the underline`() {
        val s = emulate(chunks = arrayOf("$esc[4:3m", "$esc[4:0mX")).lines[0][0].style
        assertEquals(UnderlineStyle.None, s.underlineStyle)
        assertFalse(s.underline)
    }

    @Test
    fun `sgr 21 is a double underline`() {
        assertEquals(UnderlineStyle.Double, emulate(chunks = arrayOf("$esc[21mX")).lines[0][0].style.underlineStyle)
    }

    @Test
    fun `sgr 24 resets any underline style to none`() {
        val s = emulate(chunks = arrayOf("$esc[4:3m", "$esc[24mX")).lines[0][0].style
        assertEquals(UnderlineStyle.None, s.underlineStyle)
    }

    @Test
    fun `sgr 58 sets a 256-color underline color`() {
        val s = emulate(chunks = arrayOf("$esc[4;58;5;201mX")).lines[0][0].style
        assertEquals(TermColor.Indexed(201), s.underlineColor)
    }

    @Test
    fun `sgr 58 colon truecolor underline color with empty colorspace`() {
        // 58:2::r:g:b — ITU-форма с пустым полем colorspace (двойное двоеточие).
        val s = emulate(chunks = arrayOf("$esc[58:2::1:2:3mX")).lines[0][0].style
        assertEquals(TermColor.Rgb(1, 2, 3), s.underlineColor)
    }

    @Test
    fun `sgr 59 resets the underline color to default`() {
        val s = emulate(chunks = arrayOf("$esc[58;5;201m", "$esc[59mX")).lines[0][0].style
        assertEquals(TermColor.Default, s.underlineColor)
    }

    @Test
    fun `underline color survives independently of foreground`() {
        // Цвет подчёркивания не должен меняться при смене fg и наоборот.
        val s = emulate(chunks = arrayOf("$esc[4;58;5;9;31mX")).lines[0][0].style
        assertEquals(TermColor.Red, s.fg)
        assertEquals(TermColor.BrightRed, s.underlineColor)
    }

    // --- OSC 8 гиперссылки --------------------------------------------------

    @Test
    fun `osc 8 attaches a hyperlink to printed cells`() {
        // OSC 8 ; params ; URI ST  ... текст ...  OSC 8 ; ; ST (закрытие).
        val emu = emulate(chunks = arrayOf("$esc]8;;https://skerry.app${esc}\\link$esc]8;;${esc}\\x"))
        assertEquals("https://skerry.app", emu.lines[0][0].hyperlink)
        assertEquals("https://skerry.app", emu.lines[0][3].hyperlink) // 'k'
        assertEquals(null, emu.lines[0][4].hyperlink, "после закрытия ссылки нет")
    }

    @Test
    fun `osc 8 terminated by bel also works`() {
        val emu = emulate(chunks = arrayOf("$esc]8;;https://x.test${bel}A"))
        assertEquals("https://x.test", emu.lines[0][0].hyperlink)
    }

    @Test
    fun `osc 8 with id params captures only the uri`() {
        val emu = emulate(chunks = arrayOf("$esc]8;id=42;https://id.test${esc}\\Z"))
        assertEquals("https://id.test", emu.lines[0][0].hyperlink)
    }

    @Test
    fun `hyperlink persists across a newline until closed`() {
        val emu = emulate(chunks = arrayOf("$esc]8;;https://multi.line${esc}\\a\r\nb"))
        assertEquals("https://multi.line", emu.lines[0][0].hyperlink)
        assertEquals("https://multi.line", emu.lines[1][0].hyperlink)
    }

    @Test
    fun `ris clears the active hyperlink`() {
        val emu = emulate(chunks = arrayOf("$esc]8;;https://gone${esc}\\", "${esc}cX"))
        assertEquals(null, emu.lines[0][0].hyperlink)
    }

    // --- OSC 4/104 динамическая палитра ------------------------------------

    @Test
    fun `osc 4 overrides a palette color with rgb spec`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;rgb:ff/00/00${esc}\\"))
        assertEquals(TermColor.Rgb(255, 0, 0), emu.paletteOverride(1))
    }

    @Test
    fun `osc 4 supports hash color spec`() {
        val emu = emulate(chunks = arrayOf("$esc]4;2;#00ff00${bel}"))
        assertEquals(TermColor.Rgb(0, 255, 0), emu.paletteOverride(2))
    }

    @Test
    fun `osc 4 scales 4-digit rgb channels to 8-bit`() {
        // rgb:ffff/0000/8080 — каждая компонента 4 hex-цифры (X11), масштабируется в 0..255.
        val emu = emulate(chunks = arrayOf("$esc]4;5;rgb:ffff/0000/8080${esc}\\"))
        assertEquals(TermColor.Rgb(255, 0, 128), emu.paletteOverride(5))
    }

    @Test
    fun `osc 4 short and long hash forms scale per channel`() {
        // #abc → каждая цифра дублируется в байт (как CSS): a→0xaa, b→0xbb, c→0xcc.
        assertEquals(TermColor.Rgb(0xAA, 0xBB, 0xCC), emulate(chunks = arrayOf("$esc]4;7;#abc${esc}\\")).paletteOverride(7))
        // 12-значная #RRRRGGGGBBBB масштабируется в 8-бит: ffff→255, 0000→0, ffff→255.
        assertEquals(TermColor.Rgb(255, 0, 255), emulate(chunks = arrayOf("$esc]4;8;#ffff0000ffff${esc}\\")).paletteOverride(8))
    }

    @Test
    fun `osc 4 sets multiple colors in one sequence`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;rgb:11/22/33;2;rgb:44/55/66${esc}\\"))
        assertEquals(TermColor.Rgb(0x11, 0x22, 0x33), emu.paletteOverride(1))
        assertEquals(TermColor.Rgb(0x44, 0x55, 0x66), emu.paletteOverride(2))
    }

    @Test
    fun `osc 4 query form does not crash and leaves color unset`() {
        val emu = emulate(chunks = arrayOf("$esc]4;3;?${esc}\\"))
        assertEquals(null, emu.paletteOverride(3))
    }

    @Test
    fun `osc 104 resets a single palette color`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;rgb:ff/00/00${esc}\\", "$esc]104;1${esc}\\"))
        assertEquals(null, emu.paletteOverride(1))
    }

    @Test
    fun `osc 104 with no args resets the whole palette`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;#ff0000;2;#00ff00${esc}\\", "$esc]104${esc}\\"))
        assertEquals(null, emu.paletteOverride(1))
        assertEquals(null, emu.paletteOverride(2))
    }

    @Test
    fun `ris clears palette overrides`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;#ff0000${esc}\\", "${esc}c"))
        assertEquals(null, emu.paletteOverride(1))
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

    // --- REP (повтор предыдущего символа) ----------------------------------

    @Test
    fun `rep repeats the preceding character`() {
        // CSI Ps b — повторить последний печатный символ Ps раз (nano 9.0 так заполняет полосы).
        assertEquals("Xaaaa", emulate(chunks = arrayOf("Xa", "$esc[3b")).asText())
    }

    @Test
    fun `rep with no preceding print is a no-op`() {
        assertEquals("", emulate(chunks = arrayOf("$esc[5b")).asText())
    }

    @Test
    fun `rep carries the current style including reverse`() {
        // nano-кейс: reverse on, печать пробела, REP — хвост полосы должен остаться инверсным.
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("$esc[7m ", "$esc[5b"))
        assertTrue(emu.lines[0][0].style.inverse)
        assertTrue(emu.lines[0][5].style.inverse, "повторённые пробелы тоже инверсны")
    }

    // --- Стирание ----------------------------------------------------------

    @Test
    fun `erase to end of line clears from cursor`() {
        assertEquals("abc", emulate(chunks = arrayOf("abcdef", "$esc[3D", "$esc[0K")).asText())
    }

    @Test
    fun `erase to end of line under reverse video fills cells with inverse`() {
        // BCE (background-color-erase): EL/ED заливают current SGR-фоном ВКЛЮЧАЯ reverse-video.
        // ncurses (nano) так дозаполняет reverse title-бар после ресайза — без этого хвост рисуется
        // обычным фоном и инверсия обрывается на краю старого экрана.
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("$esc[7mX", "$esc[0K"))
        assertTrue(emu.lines[0][0].style.inverse, "написанная ячейка под reverse")
        assertTrue(emu.lines[0][5].style.inverse, "стёртый хвост строки тоже инверсный")
    }

    @Test
    fun `erase to end of line carries the current background color`() {
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("$esc[41mX", "$esc[0K"))
        assertEquals(TermColor.Red, emu.lines[0][5].style.bg)
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
    fun `cursor shape defaults to a blinking block`() {
        val emu = TerminalEmulator()
        assertEquals(CursorShape.Block, emu.cursorShape)
        assertTrue(emu.cursorBlink)
    }

    @Test
    fun `decscusr selects steady block`() {
        // CSI 2 SP q — пробел перед 'q' это intermediate-байт DECSCUSR.
        val emu = emulate(chunks = arrayOf("$esc[2 q"))
        assertEquals(CursorShape.Block, emu.cursorShape)
        assertFalse(emu.cursorBlink)
    }

    @Test
    fun `decscusr selects underline cursors`() {
        assertEquals(CursorShape.Underline, emulate(chunks = arrayOf("$esc[3 q")).cursorShape)
        assertTrue(emulate(chunks = arrayOf("$esc[3 q")).cursorBlink)
        assertEquals(CursorShape.Underline, emulate(chunks = arrayOf("$esc[4 q")).cursorShape)
        assertFalse(emulate(chunks = arrayOf("$esc[4 q")).cursorBlink)
    }

    @Test
    fun `decscusr selects bar cursors`() {
        assertEquals(CursorShape.Bar, emulate(chunks = arrayOf("$esc[5 q")).cursorShape)
        assertTrue(emulate(chunks = arrayOf("$esc[5 q")).cursorBlink)
        assertEquals(CursorShape.Bar, emulate(chunks = arrayOf("$esc[6 q")).cursorShape)
        assertFalse(emulate(chunks = arrayOf("$esc[6 q")).cursorBlink)
    }

    @Test
    fun `decscusr zero or empty resets to blinking block`() {
        val byZero = emulate(chunks = arrayOf("$esc[6 q", "$esc[0 q"))
        assertEquals(CursorShape.Block, byZero.cursorShape)
        assertTrue(byZero.cursorBlink)
        val byEmpty = emulate(chunks = arrayOf("$esc[4 q", "$esc[ q"))
        assertEquals(CursorShape.Block, byEmpty.cursorShape)
        assertTrue(byEmpty.cursorBlink)
    }

    @Test
    fun `ris resets cursor shape to blinking block`() {
        val emu = emulate(chunks = arrayOf("$esc[4 q", "${esc}c"))
        assertEquals(CursorShape.Block, emu.cursorShape)
        assertTrue(emu.cursorBlink)
    }

    @Test
    fun `soft reset restores cursor shape to blinking block`() {
        val emu = emulate(chunks = arrayOf("$esc[6 q", "$esc[!p"))
        assertEquals(CursorShape.Block, emu.cursorShape)
        assertTrue(emu.cursorBlink)
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
        assertEquals("П", emu.lines[0][0].text)
    }

    // --- DEC line-drawing charset (DEC Special Graphics) -------------------

    @Test
    fun `ESC paren 0 maps ascii qxlk to box-drawing glyphs`() {
        // ESC ( 0 переводит G0 в DEC Special Graphics: q=─ x=│ l=┌ k=┐ j=┘ m=└ n=┼
        val emu = emulate(chunks = arrayOf("$esc(0lqk"))
        assertEquals("┌─┐", emu.asText())
    }

    @Test
    fun `ESC paren B restores ascii after line-drawing`() {
        // Рисуем уголок, затем возвращаем US-ASCII и печатаем буквы — они не транслируются.
        val emu = emulate(chunks = arrayOf("$esc(0qq", "${esc}(Bqq"))
        assertEquals("──qq", emu.asText())
    }

    @Test
    fun `shift-out invokes G1 line-drawing then shift-in restores G0`() {
        // ESC ) 0 кладёт line-drawing в G1; SO (0x0e) активирует G1, SI (0x0f) возвращает G0(ASCII).
        val so = 14.toChar().toString()
        val si = 15.toChar().toString()
        val emu = emulate(chunks = arrayOf("$esc)0a${so}qx${si}b"))
        assertEquals("a─│b", emu.asText())
    }

    @Test
    fun `line-drawing maps full corner and tee set`() {
        // j=┘ k=┐ l=┌ m=└ n=┼ t=├ u=┤ v=┴ w=┬ x=│ q=─
        val emu = emulate(chunks = arrayOf("$esc(0jklmntuvwxq"))
        assertEquals("┘┐┌└┼├┤┴┬│─", emu.asText())
    }

    @Test
    fun `RIS resets charset back to ascii`() {
        val emu = emulate(chunks = arrayOf("$esc(0", "${esc}cqk"))
        assertEquals("qk", emu.asText())
    }

    @Test
    fun `DECSC and DECRC save and restore the active charset`() {
        // ESC 7 (DECSC) в ASCII; включаем line-drawing и рисуем ─; ESC 8 (DECRC) должен вернуть
        // ASCII, поэтому следующий q печатается буквой, а не глифом. \r возвращает курсор в колонку 0.
        val emu = emulate(chunks = arrayOf("${esc}7$esc(0q", "${esc}8\rq"))
        assertEquals("q", emu.asText())
    }

    // --- Unicode-ширина (CJK/emoji двойной ширины + астральные) ------------

    @Test
    fun `cjk char occupies two cells and advances cursor by two`() {
        val emu = emulate(chunks = arrayOf("中"))
        assertEquals("中", emu.lines[0][0].text)
        assertEquals(CellWidth.Wide, emu.lines[0][0].width)
        assertEquals("", emu.lines[0][1].text)
        assertEquals(CellWidth.Continuation, emu.lines[0][1].width)
        assertEquals(2, emu.cursorCol)
    }

    @Test
    fun `narrow ascii stays single width`() {
        val emu = emulate(chunks = arrayOf("a"))
        assertEquals(CellWidth.Single, emu.lines[0][0].width)
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `astral emoji decodes to one wide cell not replacement char`() {
        // U+1F600 GRINNING FACE — астральный (суррогатная пара), раньше превращался в '�'.
        val emu = emulate(chunks = arrayOf("😀"))
        assertEquals("😀", emu.lines[0][0].text)
        assertEquals(CellWidth.Wide, emu.lines[0][0].width)
        assertEquals(2, emu.cursorCol)
    }

    @Test
    fun `wide char that does not fit the last column wraps to next line`() {
        // cols=2: первый 中 заполняет обе колонки (pending-wrap), второй переносится на строку ниже.
        val emu = emulate(cols = 2, rows = 3, chunks = arrayOf("中中"))
        assertEquals("中\n中", emu.asText())
        assertEquals("中", emu.lines[1][0].text)
    }

    @Test
    fun `wide char does not start in the last column with content after a narrow`() {
        // cols=3: a в col0, b в col1, 中 не влезает в col2 -> перенос на следующую строку.
        val emu = emulate(cols = 3, rows = 3, chunks = arrayOf("ab中"))
        assertEquals("ab\n中", emu.asText())
    }
}
