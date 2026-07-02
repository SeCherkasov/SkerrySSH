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

    // Базовая печать

    @Test
    fun `plain text fills one line`() {
        assertEquals("hello", emulate(chunks = arrayOf("hello")).asText())
    }

    @Test
    fun `CHT and CBT with an enormous count clamp instead of hanging`() {
        // ESC[2147483647I / ...Z без капа = ~2 млрд итераций в некооперативном цикле (зависание
        // сессии/UI с недоверенного сервера). С капом курсор за конечное время упирается в границу.
        val fwd = emulate(cols = 80, rows = 24, chunks = arrayOf("${esc}[2147483647I"))
        assertTrue(fwd.cursorCol in 0..79)
        val back = emulate(cols = 80, rows = 24, chunks = arrayOf("col$esc[2147483647Z"))
        assertTrue(back.cursorCol in 0..79)
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

    // Автоперенос (DECAWM)

    @Test
    fun `printing past the last column wraps to the next line`() {
        // cols=3: "abc" заполняет строку, "d" переносится на следующую (pending-wrap).
        assertEquals("abc\nd", emulate(cols = 3, rows = 4, chunks = arrayOf("abcd")).asText())
    }

    @Test
    fun `autowrap off keeps overwriting the last column`() {
        assertEquals("abd", emulate(cols = 3, rows = 4, chunks = arrayOf("$esc[?7l", "abcd")).asText())
    }

    // SGR

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

    // Modern SGR: стили и цвет подчёркивания (4:x, 21, 58, 59)

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

    // OSC 8 гиперссылки

    @Test
    fun `osc 8 attaches a hyperlink to printed cells`() {
        // OSC 8 ; params ; URI ST  ... текст ...  OSC 8 ; ; ST (закрытие).
        val emu = emulate(chunks = arrayOf("$esc]8;;https://skerry.app${esc}\\link$esc]8;;${esc}\\x"))
        assertEquals("https://skerry.app", emu.lines[0][0].hyperlink)
        assertEquals("https://skerry.app", emu.lines[0][3].hyperlink) // 'k'
        assertEquals(null, emu.lines[0][4].hyperlink, "после закрытия ссылки нет")
    }

    @Test
    fun `osc 8 caps an oversized uri`() {
        // Недоверённый сервер не должен мочь повесить мегабайтный URI на каждую клетку.
        val emu = emulate(chunks = arrayOf("$esc]8;;https://x.test/${"a".repeat(5000)}${esc}\\Z"))
        val link = emu.lines[0][0].hyperlink
        assertEquals(2048, link?.length, "URI должен капаться до 2048 символов")
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

    // OSC 52 буфер обмена

    @Test
    fun `osc 52 write decodes base64 and reports a clipboard copy`() {
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        // OSC 52 ; c ; <base64 "hello"> ST
        emu.feed("$esc]52;c;aGVsbG8=$esc\\".encodeToByteArray())
        assertEquals(listOf("hello"), copied)
    }

    @Test
    fun `osc 52 accepts an empty selection field`() {
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;;aGVsbG8=$esc\\".encodeToByteArray())
        assertEquals(listOf("hello"), copied)
    }

    @Test
    fun `osc 52 read request is ignored to avoid leaking the clipboard`() {
        // Pd == '?' — запрос чтения буфера сервером. Никогда не отдаём (модель угроз): колбэк молчит.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;?$esc\\".encodeToByteArray())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `osc 52 read query with trailing data is still denied`() {
        // Защита в глубину: любой Pd, начинающийся с '?', трактуем как запрос чтения и не отдаём буфер.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;?aGVsbG8=$esc\\".encodeToByteArray())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `osc 52 with invalid base64 is ignored`() {
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;@@@notbase64$esc\\".encodeToByteArray())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `osc 52 oversized clipboard write is dropped`() {
        // Модель угроз: сервер не должен мочь заливать в системный буфер мегабайты. base64 "YWFh"
        // декодируется в "aaa" (3 байта) — 25000 повторов = 75000 байт > лимита 64 KiB → молчим.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;${"YWFh".repeat(25000)}$esc\\".encodeToByteArray())
        assertTrue(copied.isEmpty(), "переразмерная запись буфера должна отбрасываться")
    }

    @Test
    fun `osc 52 clipboard write at a sane size still works`() {
        // 1000×"aaa" = 3000 байт — в пределах лимита, запись проходит.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;${"YWFh".repeat(1000)}$esc\\".encodeToByteArray())
        assertEquals(listOf("aaa".repeat(1000)), copied)
    }

    // OSC 4/104 динамическая палитра

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

    // Адресация курсора

    @Test
    fun `cup positions cursor absolutely`() {
        // ESC[2;3H ставит курсор в строку 2, колонку 3 (1-based); печать туда.
        assertEquals("\n  X", emulate(chunks = arrayOf("$esc[2;3HX")).asText())
    }

    @Test
    fun `resize clamps pathological dimensions to a sane maximum`() {
        // Защита от переполнения Int в cols*rows (REP) и от безумного объёма работы на ресайз.
        val emu = emulate()
        emu.resize(100_000, 100_000)
        assertTrue(emu.cols <= 2000, "cols должен капаться, было ${emu.cols}")
        assertTrue(emu.rows <= 2000, "rows должен капаться, было ${emu.rows}")
    }

    @Test
    fun `overlong CSI parameter run does not break the parser`() {
        // Недоверенный сервер льёт бесконечный поток цифр без финального байта (защита от OOM —
        // буфер params капается). Парсер обязан выйти из CSI на финальном байте и продолжить печать.
        val emu = emulate(chunks = arrayOf("$esc[${"9".repeat(5000)}mhi"))
        assertEquals("hi", emu.asText())
    }

    @Test
    fun `cursor up down forward back reposition`() {
        assertEquals("aXc", emulate(chunks = arrayOf("abc", "$esc[2D", "X")).asText())
    }

    @Test
    fun `vpa sets row absolutely`() {
        assertEquals("\n\nX", emulate(chunks = arrayOf("$esc[3dX")).asText())
    }

    // REP (повтор предыдущего символа)

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

    // Стирание

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
    fun `erase display 2J moves the screen into scrollback and clears the grid`() {
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2", "$esc[2J"))
        // Видимая сетка (нижние rows строк) очищена, но прежний вывод ушёл в scrollback — его
        // можно прокрутить вверх (поведение gnome-terminal/VTE), а не потерян.
        assertEquals(6, emu.lines.size) // 2 в scrollback + 4 пустых экранных
        assertEquals("line1", emu.lines[0].joinToString("") { it.text }.trimEnd())
        assertEquals("line2", emu.lines[1].joinToString("") { it.text }.trimEnd())
        assertTrue(emu.lines.takeLast(4).all { row -> row.all { it.text == " " } }, "экран очищен")
        // ED 2 курсор не двигает: cy остаётся на 1 → абсолютно scrollback.size(2) + 1.
        assertEquals(3, emu.cursorRow)
    }

    @Test
    fun `erase display 2J blanks in place on the alternate screen`() {
        // На альт-экране scrollback'а нет — полноэкранные TUI очищают на месте, без переноса в историю.
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("$esc[?1049h", "line1\r\nline2", "$esc[2J"))
        assertEquals("", emu.asText())
        assertEquals(4, emu.lines.size)
    }

    @Test
    fun `erase display 3 keeps scrollback so clear preserves history`() {
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("a\r\nb\r\nc\r\nd"))
        val before = emu.lines.size
        assertTrue(before > 2) // накопился scrollback
        emu.feed("$esc[3J".encodeToByteArray())
        // ED 3 («erase saved lines») историю НЕ вытирает — она остаётся прокручиваемой.
        assertTrue(emu.lines.size >= before, "scrollback сохранён")
    }

    @Test
    fun `clear sequence blanks the screen yet keeps prior output scrollable`() {
        // Ровно то, что шлёт команда `clear`: домой, очистить экран, очистить saved lines.
        val emu = emulate(cols = 10, rows = 3, chunks = arrayOf("alpha\r\nbeta\r\ngamma", "$esc[H$esc[2J$esc[3J"))
        // Прежний вывод доступен в истории...
        val text = emu.lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }
        assertTrue("alpha" in text && "beta" in text && "gamma" in text, "история сохранена")
        // ...а видимая сетка (нижние 3 строки) пуста, курсор — в её начале (домой).
        assertTrue(emu.lines.takeLast(3).all { row -> row.all { it.text == " " } }, "экран очищен")
        assertEquals(3, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `clear keeps a background-colored blank row in history`() {
        // Строка из пробелов с цветным фоном (BCE) — это содержимое, не пустота: при clear она
        // должна уйти в scrollback, а не обрезаться как хвостовая пустая.
        val emu = emulate(cols = 4, rows = 3, chunks = arrayOf("ab\r\n", "$esc[41m    $esc[m"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray())
        assertEquals(5, emu.lines.size) // "ab" + цветная строка в scrollback + 3 пустых экранных
        assertEquals(TermColor.Red, emu.lines[1][0].style.bg)
    }

    @Test
    fun `repeated clear does not flood scrollback with blank lines`() {
        val emu = emulate(cols = 10, rows = 3, chunks = arrayOf("x\r\ny"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray())
        val afterFirst = emu.lines.size
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray()) // очистка уже пустого экрана
        assertEquals(afterFirst, emu.lines.size, "повторный clear не плодит пустые строки в истории")
    }

    @Test
    fun `resize after clear keeps the screen empty and history scrolled back`() {
        // После `clear` прежний вывод лежит в scrollback, а видимый экран пуст (один свежий prompt
        // вверху). Ресайз — например при открытии split, сужающего терминал — не должен втягивать
        // историю обратно на видимый экран: пустое пространство под курсором это содержимое экрана,
        // а не «незначимый хвост», и reflow обязан его сохранить.
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2\r\nline3"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray()) // clear: история → scrollback, экран пуст
        emu.feed("$ ".encodeToByteArray())                   // свежий prompt в верхней строке экрана
        emu.resize(6, 4)                                     // сужаем терминал (как открытие split)

        val visible = emu.lines.takeLast(4)
        assertEquals("$", visible[0].joinToString("") { it.text }.trimEnd(), "prompt остаётся вверху экрана")
        assertTrue(
            visible.drop(1).all { row -> row.all { it.text == " " } },
            "под prompt'ом экран пуст — история не всплыла обратно",
        )
        val visibleText = visible.joinToString("\n") { row -> row.joinToString("") { it.text } }
        assertFalse("line1" in visibleText, "история не вернулась на видимый экран после ресайза")
        // История по-прежнему доступна прокруткой.
        val allText = emu.lines.joinToString("\n") { row -> row.joinToString("") { it.text } }
        assertTrue("line1" in allText, "история сохранена в scrollback")
    }

    @Test
    fun `resize after clear with height reduction pins the cursor on screen`() {
        // Когда ресайз заодно уменьшает высоту, кап nr-1 на сохранённом пространстве под курсором
        // обязан удержать курсор в пределах нового экрана (а не увести его за верхнюю границу).
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2\r\nline3"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray()) // clear
        emu.feed("$ ".encodeToByteArray())                   // prompt вверху экрана
        emu.resize(6, 2)                                     // уже и НИЖЕ

        assertTrue(emu.cursorRow < emu.lines.size, "курсор в пределах буфера")
        val visible = emu.lines.takeLast(2)
        assertEquals("$", visible[0].joinToString("") { it.text }.trimEnd(), "prompt остаётся на видимом экране")
        val visibleText = visible.joinToString("\n") { row -> row.joinToString("") { it.text } }
        assertFalse("line1" in visibleText, "история не всплыла на укоротившийся экран")
    }

    // Вставка / удаление

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

    // Прокрутка / регион

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

    // Курсор save/restore

    @Test
    fun `save and restore cursor with esc 7 and esc 8`() {
        // ESC7 сохраняет позицию после "AB"; затем уходим вниз и возвращаемся ESC8.
        assertEquals("ABX\n\nCD", emulate(chunks = arrayOf("AB", "${esc}7", "\r\n\r\nCD", "${esc}8", "X")).asText())
    }

    // Alt-screen

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

    // Ответы DSR/DA

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

    @Test
    fun `DECRQM reports private mode state`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        // bracketed-paste выключен по умолчанию → reset (2).
        emu.feed("$esc[?2004\$p".encodeToByteArray())
        assertEquals("$esc[?2004;2\$y", replies.last())
        // Включаем → set (1).
        emu.feed("$esc[?2004h".encodeToByteArray())
        emu.feed("$esc[?2004\$p".encodeToByteArray())
        assertEquals("$esc[?2004;1\$y", replies.last())
    }

    @Test
    fun `DECRQM reports not-recognized for unknown mode`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[?9999\$p".encodeToByteArray())
        assertEquals("$esc[?9999;0\$y", replies.single())
    }

    @Test
    fun `DECRQM reports ANSI insert mode state`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[4h".encodeToByteArray())        // IRM on
        emu.feed("$esc[4\$p".encodeToByteArray())
        assertEquals("$esc[4;1\$y", replies.last())
    }

    @Test
    fun `XTVERSION reports the terminal name`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[>q".encodeToByteArray())
        // Ответ DCS: ESC P > | <name> ESC \
        assertEquals("${esc}P>|Skerry(0.1)$esc\\", replies.single())
    }

    // OSC / bell

    @Test
    fun `osc sets the window title`() {
        val emu = emulate(chunks = arrayOf("$esc]0;my title${bel}X"))
        assertEquals("my title", emu.title)
        assertEquals("X", emu.asText())
    }

    @Test
    fun `osc title strips embedded control characters`() {
        // Сервер не должен мочь протащить C0/DEL в заголовок (искажение UI вкладки, риск лог-инъекции).
        val c1 = 1.toChar(); val c31 = 31.toChar(); val del = 127.toChar()
        val emu = emulate(chunks = arrayOf("$esc]0;a${c1}b${c31}c$del$bel"))
        assertEquals("abc", emu.title)
    }

    @Test
    fun `CSI 22 t pushes and CSI 23 t pops the window title`() {
        // XTWINOPS title stack: vim/tmux сохраняют заголовок при входе (22;2 t) и восстанавливают
        // при выходе (23;2 t). Ставим A, push, меняем на B, pop -> снова A.
        val emu = emulate(chunks = arrayOf("$esc]2;A$bel", "$esc[22;2t", "$esc]2;B$bel"))
        assertEquals("B", emu.title)
        emu.feed("$esc[23;2t".encodeToByteArray())
        assertEquals("A", emu.title)
    }

    @Test
    fun `title stack nests and second-param 0 also targets the title`() {
        // Ps=0 (icon+window) тоже толкает/снимает заголовок; стек вложенный (LIFO).
        val emu = emulate(chunks = arrayOf("$esc]2;A$bel", "$esc[22;0t", "$esc]2;B$bel", "$esc[22t", "$esc]2;C$bel"))
        assertEquals("C", emu.title)
        emu.feed("$esc[23t".encodeToByteArray())   // pop -> B
        assertEquals("B", emu.title)
        emu.feed("$esc[23;0t".encodeToByteArray())  // pop -> A
        assertEquals("A", emu.title)
    }

    @Test
    fun `popping the title with empty stack leaves it unchanged`() {
        val emu = emulate(chunks = arrayOf("$esc]2;A$bel", "$esc[23;2t"))
        assertEquals("A", emu.title)
    }

    @Test
    fun `icon-only title ops are ignored and do not unbalance the stack`() {
        // Ps=1 — только icon name, заголовок окна не моделируем: 22;1/23;1 не трогают стек.
        val emu = emulate(
            chunks = arrayOf("$esc]2;A$bel", "$esc[22;1t", "$esc]2;B$bel", "$esc[23;1t"),
        )
        assertEquals("B", emu.title) // icon-pop не вернул A
    }

    @Test
    fun `RIS clears the title and its stack`() {
        // ESC c (RIS) возвращает заголовок к дефолту и очищает стек: последующий pop — no-op.
        val emu = emulate(chunks = arrayOf("$esc]2;A$bel", "$esc[22;2t", "${esc}c"))
        assertEquals("", emu.title)
        emu.feed("$esc[23;2t".encodeToByteArray())
        assertEquals("", emu.title)
    }

    // Комбинируемые знаки (grapheme-кластеры)

    private val acute = "́" // combining acute accent (нулевая ширина)
    private val zwj = "‍"   // zero-width joiner

    @Test
    fun `combining accent attaches to the base cell without advancing`() {
        // "e" + U+0301 -> одна клетка, курсор сдвинулся лишь на 1.
        val emu = emulate(chunks = arrayOf("e$acute"))
        assertEquals("e$acute", emu.lines[0][0].text)
        assertEquals(" ", emu.lines[0][1].text) // следующая клетка пуста — знак её не занял
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `ZWJ joins into the previous cell`() {
        // ZWJ — нулевой ширины, должен прицепиться к базе, а не стать своей клеткой.
        val emu = emulate(chunks = arrayOf("a$zwj"))
        assertEquals("a$zwj", emu.lines[0][0].text)
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `combining mark attaches to a wide base not its continuation`() {
        // "中" (Wide) + U+0301: знак идёт в саму Wide-клетку, континуация не трогается; курсор на 2.
        val emu = emulate(chunks = arrayOf("中$acute"))
        assertEquals("中$acute", emu.lines[0][0].text)
        assertEquals(CellWidth.Wide, emu.lines[0][0].width)
        assertEquals(CellWidth.Continuation, emu.lines[0][1].width)
        assertEquals(2, emu.cursorCol)
    }

    @Test
    fun `leading combining mark on empty line does not crash`() {
        // База слева отсутствует — знак печатается как обычная клетка (фолбэк), курсор едет на 1.
        val emu = emulate(chunks = arrayOf(acute))
        assertEquals(1, emu.cursorCol)
        emu.feed("X".encodeToByteArray())
        assertEquals("X", emu.lines[0][1].text)
    }

    // Строковые последовательности: DCS / APC / PM / SOS + XTGETTCAP

    @Test
    fun `DCS body is swallowed and does not leak to the screen`() {
        // Раньше ESC P падал в Ground и тело DCS (sixel/DECRQSS) текло как мусорный текст.
        // DCS q ... ST (типичный sixel-конверт) должен поглотиться целиком; печать после — норм.
        val emu = emulate(chunks = arrayOf("A", "${esc}Pq#0;2;0;0;0~~$esc\\", "B"))
        assertEquals("AB", emu.asText())
    }

    @Test
    fun `APC kitty graphics envelope is swallowed`() {
        // Kitty graphics: APC G ... ST (ESC _ ... ESC \). Не должно протечь на экран.
        val emu = emulate(chunks = arrayOf("X", "${esc}_Ga=T,f=24;payload$esc\\", "Y"))
        assertEquals("XY", emu.asText())
    }

    @Test
    fun `string sequence terminated by BEL is also swallowed`() {
        val emu = emulate(chunks = arrayOf("${esc}^privmsg${bel}Z"))
        assertEquals("Z", emu.asText())
    }

    @Test
    fun `XTGETTCAP replies with known capabilities and rejects unknown`() {
        // DCS + q <hex(name)> ; ... ST. Co=colors(256), TN=имя терминала; неизвестное -> 0+r.
        val responses = mutableListOf<String>()
        val emu = TerminalEmulator(cols = 80, rows = 24, respond = { responses.add(it) })
        // "Co" = 436F, "ZZ" = 5A5A (неизвестная).
        emu.feed("${esc}P+q436F;5A5A$esc\\".encodeToByteArray())
        val joined = responses.joinToString("")
        // Валидный ответ на Co: DCS 1 + r 436F = <hex("256")> ST ; hex("256") = 323536.
        assertTrue(joined.contains("1+r436F=323536"), "ожидался валидный Co=256, было: $joined")
        // Неизвестная ZZ -> DCS 0 + r 5A5A ST.
        assertTrue(joined.contains("0+r5A5A"), "ожидался отказ по ZZ, было: $joined")
    }

    @Test
    fun `XTGETTCAP caps the number of replied capabilities`() {
        // Амплификация: один DCS с тысячами имён не должен порождать тысячи ответов в PTY.
        // 500 валидных запросов "Co" → ответов не больше предела (64).
        val responses = mutableListOf<String>()
        val emu = TerminalEmulator(cols = 80, rows = 24, respond = { responses.add(it) })
        val names = List(500) { "436F" }.joinToString(";")
        emu.feed("${esc}P+q$names$esc\\".encodeToByteArray())
        assertTrue(responses.size <= 64, "ответов XTGETTCAP должно быть не больше 64, было ${responses.size}")
    }

    @Test
    fun `bell triggers the callback`() {
        var rang = false
        TerminalEmulator(onBell = { rang = true }).feed(bel.encodeToByteArray())
        assertTrue(rang)
    }

    // Приватные режимы

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
    fun `cursor shape and blink seed from constructor defaults`() {
        val emu = TerminalEmulator(initialCursorShape = CursorShape.Bar, initialCursorBlink = false)
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
    }

    @Test
    fun `RIS restores configured cursor default not hardcoded block`() {
        // Настройка = стабильная черта; приложение переключает на мигающий блок, затем RIS (ESC c).
        val emu = TerminalEmulator(initialCursorShape = CursorShape.Bar, initialCursorBlink = false)
        emu.feed("$esc[1 q".encodeToByteArray()) // мигающий блок из приложения
        assertEquals(CursorShape.Block, emu.cursorShape)
        emu.feed("${esc}c".encodeToByteArray())  // RIS
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
    }

    @Test
    fun `applyCursorDefault changes cursor live and survives RIS`() {
        val emu = TerminalEmulator() // старт: мигающий блок
        emu.applyCursorDefault(CursorShape.Bar, blink = false)
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
        // Приложение перебивает своим DECSCUSR, затем RIS возвращает к НОВОМУ дефолту, не к блоку.
        emu.feed("$esc[1 q".encodeToByteArray())
        assertEquals(CursorShape.Block, emu.cursorShape)
        emu.feed("${esc}c".encodeToByteArray())
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
    }

    @Test
    fun `applyMaxScrollback shrinks history of an open session immediately`() {
        // Экран 2 строки, буфер 100; печатаем 30 строк — большая часть уходит в scrollback.
        val emu = TerminalEmulator(cols = 10, rows = 2, maxScrollback = 100)
        val sb = StringBuilder()
        for (i in 0 until 30) sb.append("line$i\r\n")
        emu.feed(sb.toString().encodeToByteArray())
        assertTrue(emu.lines.size > 20, "история должна накопиться в scrollback")

        // Уменьшаем буфер на лету — лишние старые строки обрезаются сразу (scrollback=5 + 2 экрана).
        emu.applyMaxScrollback(5)
        assertEquals(7, emu.lines.size)
    }

    @Test
    fun `applyMaxScrollback keeps trimming as new lines arrive`() {
        val emu = TerminalEmulator(cols = 10, rows = 2, maxScrollback = 100)
        emu.applyMaxScrollback(3)
        val sb = StringBuilder()
        for (i in 0 until 20) sb.append("x$i\r\n")
        emu.feed(sb.toString().encodeToByteArray())
        // Новая глубина держится и для последующего вывода: 3 scrollback + 2 экрана.
        assertEquals(5, emu.lines.size)
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
    fun `mouse pixel mode 1016 is tracked`() {
        // DECSET 1016: приложение просит SGR-Pixels (координаты в пикселях вместо клеток).
        val emu = emulate(chunks = arrayOf("$esc[?1016h"))
        assertTrue(emu.mousePixels)
        emu.feed("$esc[?1016l".encodeToByteArray())
        assertFalse(emu.mousePixels)
    }

    @Test
    fun `DECRQM reports mouse pixel mode state`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[?1016\$p".encodeToByteArray())          // выключен по умолчанию → reset (2)
        assertEquals("$esc[?1016;2\$y", replies.last())
        emu.feed("$esc[?1016h".encodeToByteArray())
        emu.feed("$esc[?1016\$p".encodeToByteArray())
        assertEquals("$esc[?1016;1\$y", replies.last())
    }

    @Test
    fun `bracketed paste mode is tracked`() {
        assertTrue(emulate(chunks = arrayOf("$esc[?2004h")).bracketedPaste)
    }

    @Test
    fun `application keypad mode is tracked via DECKPAM and DECKPNM`() {
        assertTrue(emulate(chunks = arrayOf("$esc=")).applicationKeypad)   // DECKPAM
        val emu = emulate(chunks = arrayOf("$esc="))
        emu.feed("$esc>".encodeToByteArray())                              // DECKPNM
        assertFalse(emu.applicationKeypad)
    }

    @Test
    fun `focus reporting mode is tracked`() {
        // DECSET 1004: vim/tmux просят уведомления о фокусе окна.
        assertTrue(emulate(chunks = arrayOf("$esc[?1004h")).focusReporting)
        val emu = emulate(chunks = arrayOf("$esc[?1004h"))
        emu.feed("$esc[?1004l".encodeToByteArray())
        assertFalse(emu.focusReporting)
    }

    @Test
    fun `unrelated private mode does not arm application cursor keys`() {
        assertFalse(emulate(chunks = arrayOf("$esc[?1049h$esc[?2004h")).applicationCursorKeys)
    }

    // Resize

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

    @Test
    fun `widening rejoins a soft-wrapped line`() {
        // cols=4: "ABCDEF" автопереносится на "ABCD"+"EF" (мягкий перенос).
        val emu = emulate(cols = 4, rows = 6, chunks = arrayOf("ABCDEF"))
        assertEquals("ABCD\nEF", emu.asText())
        emu.resize(10, 6)
        // На ширине 10 обе части склеиваются обратно в одну логическую строку.
        assertEquals("ABCDEF", emu.asText())
        assertTrue(emu.lines.all { it.size == 10 })
    }

    @Test
    fun `widening does not merge across a hard newline`() {
        val emu = emulate(cols = 10, rows = 6, chunks = arrayOf("AB\r\nCD"))
        emu.resize(40, 6)
        // Явный перевод строки — граница логических строк, склейки нет.
        assertEquals("AB\nCD", emu.asText())
    }

    @Test
    fun `narrowing reflows a long line onto the new width`() {
        // Honest-строка длиной 8 без переноса (cols=10) при сужении до 4 переразбивается.
        val emu = emulate(cols = 10, rows = 6, chunks = arrayOf("ABCDEFGH"))
        emu.resize(4, 6)
        assertEquals("ABCD\nEFGH", emu.asText())
        assertTrue(emu.lines.all { it.size == 4 })
    }

    @Test
    fun `reflow round-trips a soft-wrapped line through narrow and back`() {
        val emu = emulate(cols = 10, rows = 6, chunks = arrayOf("ABCDEFGHIJKLM"))
        emu.resize(4, 6)
        assertEquals("ABCD\nEFGH\nIJKL\nM", emu.asText())
        emu.resize(20, 6)
        assertEquals("ABCDEFGHIJKLM", emu.asText())
    }

    @Test
    fun `narrowing reflows wide chars and tracks the cursor correctly`() {
        // "AB中C" на ширине 6: A B 中(wide,2кл) C, курсор за C (колонка 5).
        val emu = emulate(cols = 6, rows = 6, chunks = arrayOf("AB中C"))
        assertEquals(0, emu.cursorRow)
        assertEquals(5, emu.cursorCol)
        emu.resize(3, 6)
        // На ширине 3 широкий 中 не влезает после "AB" → переносится; курсор едет на строку за "中C".
        assertEquals("AB\n中C", emu.asText())
        assertEquals(2, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `widening keeps the cursor with its text`() {
        // cols=4 "ABCDEF": курсор после F — абсолютно строка 1, колонка 2.
        val emu = emulate(cols = 4, rows = 6, chunks = arrayOf("ABCDEF"))
        assertEquals(1, emu.cursorRow)
        assertEquals(2, emu.cursorCol)
        emu.resize(10, 6)
        // После склейки курсор переезжает на одну строку, колонка 6 (после "ABCDEF").
        assertEquals(0, emu.cursorRow)
        assertEquals(6, emu.cursorCol)
    }

    // Курсор: абсолютные индексы

    @Test
    fun `cursor row is absolute including scrollback`() {
        val emu = emulate(cols = 4, rows = 2, chunks = arrayOf("a\r\nb\r\nc"))
        // 1 строка ушла в scrollback, курсор на нижней экранной строке => абсолютно row 2.
        assertEquals(2, emu.cursorRow)
        assertEquals(1, emu.cursorCol)
    }

    // UTF-8

    @Test
    fun `utf8 multibyte split across feeds decodes to one cell`() {
        val emu = TerminalEmulator()
        emu.feed(byteArrayOf(0xD0.toByte()))
        emu.feed(byteArrayOf(0x9F.toByte()))
        assertEquals("П", emu.asText())
        assertEquals("П", emu.lines[0][0].text)
    }

    // DEC line-drawing charset (DEC Special Graphics)

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

    // Unicode-ширина (CJK/emoji двойной ширины + астральные)

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
