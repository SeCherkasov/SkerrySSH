package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {

    // ESC/BEL by number — no invisible control bytes in the source.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    private fun emulate(cols: Int = 80, rows: Int = 24, vararg chunks: String): TerminalEmulator {
        val emu = TerminalEmulator(cols = cols, rows = rows)
        chunks.forEach { emu.feed(it.encodeToByteArray()) }
        return emu
    }

    /** Visible screen text: rows joined by \n, trailing spaces and empty rows trimmed. */
    private fun TerminalEmulator.asText(): String =
        lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }.trimEnd('\n')

    // Basic printing

    @Test
    fun `plain text fills one line`() {
        assertEquals("hello", emulate(chunks = arrayOf("hello")).asText())
    }

    @Test
    fun `CHT and CBT with an enormous count clamp instead of hanging`() {
        // ESC[2147483647I / ...Z without a cap = ~2 billion iterations in a non-cooperative loop
        // (session/UI hang from an untrusted server). With a cap the cursor stops at the boundary in finite time.
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

    // Autowrap (DECAWM)

    @Test
    fun `printing past the last column wraps to the next line`() {
        // cols=3: "abc" fills the row, "d" wraps to the next (pending-wrap).
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

    // Modern SGR: underline styles and color (4:x, 21, 58, 59)

    @Test
    fun `plain sgr 4 is a single underline`() {
        val s = emulate(chunks = arrayOf("$esc[4mX")).lines[0][0].style
        assertEquals(UnderlineStyle.Single, s.underlineStyle)
        assertTrue(s.underline, "single underline is visible via the compatibility boolean flag")
    }

    @Test
    fun `sgr 4 colon 3 is a curly underline`() {
        // 4:3 — curly/squiggly underline (compiler diagnostics, ls --hyperlink, etc.).
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
        // 58:2::r:g:b — ITU form with an empty colorspace field (double colon).
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
        // Underline color must not change when fg changes and vice versa.
        val s = emulate(chunks = arrayOf("$esc[4;58;5;9;31mX")).lines[0][0].style
        assertEquals(TermColor.Red, s.fg)
        assertEquals(TermColor.BrightRed, s.underlineColor)
    }

    // OSC 8 hyperlinks

    @Test
    fun `osc 8 attaches a hyperlink to printed cells`() {
        // OSC 8 ; params ; URI ST  ... text ...  OSC 8 ; ; ST (close).
        val emu = emulate(chunks = arrayOf("$esc]8;;https://skerry.app${esc}\\link$esc]8;;${esc}\\x"))
        assertEquals("https://skerry.app", emu.lines[0][0].hyperlink)
        assertEquals("https://skerry.app", emu.lines[0][3].hyperlink) // 'k'
        assertEquals(null, emu.lines[0][4].hyperlink, "no link after close")
    }

    @Test
    fun `osc 8 caps an oversized uri`() {
        // An untrusted server must not be able to attach a megabyte URI to every cell.
        val emu = emulate(chunks = arrayOf("$esc]8;;https://x.test/${"a".repeat(5000)}${esc}\\Z"))
        val link = emu.lines[0][0].hyperlink
        assertEquals(2048, link?.length, "URI should be capped to 2048 characters")
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

    // OSC 52 clipboard

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
        // Pd == '?' — a server request to read the clipboard. Never granted (threat model): callback stays silent.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;?$esc\\".encodeToByteArray())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `osc 52 read query with trailing data is still denied`() {
        // Defense in depth: any Pd starting with '?' is treated as a read request and the clipboard isn't returned.
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
        // Threat model: a server must not be able to push megabytes into the system clipboard. base64
        // "YWFh" decodes to "aaa" (3 bytes) — 25000 repeats = 75000 bytes > the 64 KiB limit → stay silent.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;${"YWFh".repeat(25000)}$esc\\".encodeToByteArray())
        assertTrue(copied.isEmpty(), "an oversized clipboard write should be dropped")
    }

    @Test
    fun `osc 52 clipboard write at a sane size still works`() {
        // 1000×"aaa" = 3000 bytes — within the limit, the write goes through.
        val copied = mutableListOf<String>()
        val emu = TerminalEmulator(onClipboardCopy = { copied += it })
        emu.feed("$esc]52;c;${"YWFh".repeat(1000)}$esc\\".encodeToByteArray())
        assertEquals(listOf("aaa".repeat(1000)), copied)
    }

    // OSC 4/104 dynamic palette

    @Test
    fun `osc 4 overrides a palette color with rgb spec`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;rgb:ff/00/00${esc}\\"))
        assertEquals(TermColor.Rgb(255, 0, 0), emu.paletteSnapshot()[1])
    }

    @Test
    fun `osc 4 supports hash color spec`() {
        val emu = emulate(chunks = arrayOf("$esc]4;2;#00ff00${bel}"))
        assertEquals(TermColor.Rgb(0, 255, 0), emu.paletteSnapshot()[2])
    }

    @Test
    fun `osc 4 scales 4-digit rgb channels to 8-bit`() {
        // rgb:ffff/0000/8080 — each component is 4 hex digits (X11), scaled to 0..255.
        val emu = emulate(chunks = arrayOf("$esc]4;5;rgb:ffff/0000/8080${esc}\\"))
        assertEquals(TermColor.Rgb(255, 0, 128), emu.paletteSnapshot()[5])
    }

    @Test
    fun `osc 4 short and long hash forms scale per channel`() {
        // #abc → each digit is doubled into a byte (like CSS): a→0xaa, b→0xbb, c→0xcc.
        assertEquals(TermColor.Rgb(0xAA, 0xBB, 0xCC), emulate(chunks = arrayOf("$esc]4;7;#abc${esc}\\")).paletteSnapshot()[7])
        // 12-digit #RRRRGGGGBBBB scaled to 8-bit: ffff→255, 0000→0, ffff→255.
        assertEquals(TermColor.Rgb(255, 0, 255), emulate(chunks = arrayOf("$esc]4;8;#ffff0000ffff${esc}\\")).paletteSnapshot()[8])
    }

    @Test
    fun `osc 4 sets multiple colors in one sequence`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;rgb:11/22/33;2;rgb:44/55/66${esc}\\"))
        assertEquals(TermColor.Rgb(0x11, 0x22, 0x33), emu.paletteSnapshot()[1])
        assertEquals(TermColor.Rgb(0x44, 0x55, 0x66), emu.paletteSnapshot()[2])
    }

    @Test
    fun `osc 4 query form does not crash and leaves color unset`() {
        val emu = emulate(chunks = arrayOf("$esc]4;3;?${esc}\\"))
        assertEquals(null, emu.paletteSnapshot()[3])
    }

    @Test
    fun `osc 104 resets a single palette color`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;rgb:ff/00/00${esc}\\", "$esc]104;1${esc}\\"))
        assertEquals(null, emu.paletteSnapshot()[1])
    }

    @Test
    fun `osc 104 with no args resets the whole palette`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;#ff0000;2;#00ff00${esc}\\", "$esc]104${esc}\\"))
        assertEquals(null, emu.paletteSnapshot()[1])
        assertEquals(null, emu.paletteSnapshot()[2])
    }

    @Test
    fun `ris clears palette overrides`() {
        val emu = emulate(chunks = arrayOf("$esc]4;1;#ff0000${esc}\\", "${esc}c"))
        assertEquals(null, emu.paletteSnapshot()[1])
    }

    // Cursor addressing

    @Test
    fun `cup positions cursor absolutely`() {
        // ESC[2;3H puts the cursor at row 2, column 3 (1-based); print there.
        assertEquals("\n  X", emulate(chunks = arrayOf("$esc[2;3HX")).asText())
    }

    @Test
    fun `resize clamps pathological dimensions to a sane maximum`() {
        // Guards against Int overflow in cols*rows (REP) and against an insane amount of resize work.
        val emu = emulate()
        emu.resize(100_000, 100_000)
        assertTrue(emu.cols <= 2000, "cols should be capped, was ${emu.cols}")
        assertTrue(emu.rows <= 2000, "rows should be capped, was ${emu.rows}")
    }

    @Test
    fun `overlong CSI parameter run does not break the parser`() {
        // An untrusted server pours an endless digit stream with no final byte (OOM guard — the params
        // buffer is capped). The parser must exit CSI on the final byte and keep printing.
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

    // REP (repeat preceding character)

    @Test
    fun `rep repeats the preceding character`() {
        // CSI Ps b — repeat the last printed character Ps times (nano 9.0 fills bars this way).
        assertEquals("Xaaaa", emulate(chunks = arrayOf("Xa", "$esc[3b")).asText())
    }

    @Test
    fun `rep with no preceding print is a no-op`() {
        assertEquals("", emulate(chunks = arrayOf("$esc[5b")).asText())
    }

    @Test
    fun `rep carries the current style including reverse`() {
        // nano case: reverse on, print a space, REP — the bar tail must stay inverse.
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("$esc[7m ", "$esc[5b"))
        assertTrue(emu.lines[0][0].style.inverse)
        assertTrue(emu.lines[0][5].style.inverse, "repeated spaces are inverse too")
    }

    // Erasing

    @Test
    fun `erase to end of line clears from cursor`() {
        assertEquals("abc", emulate(chunks = arrayOf("abcdef", "$esc[3D", "$esc[0K")).asText())
    }

    @Test
    fun `erase to end of line under reverse video fills cells with inverse`() {
        // BCE (background-color-erase): EL/ED fill with the current SGR background, including reverse-video.
        // ncurses (nano) refills the reverse title bar this way after a resize — without it the tail is
        // drawn with the normal background and the inversion breaks at the old screen's edge.
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("$esc[7mX", "$esc[0K"))
        assertTrue(emu.lines[0][0].style.inverse, "written cell under reverse")
        assertTrue(emu.lines[0][5].style.inverse, "erased row tail is inverse too")
    }

    @Test
    fun `erase to end of line carries the current background color`() {
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("$esc[41mX", "$esc[0K"))
        assertEquals(TermColor.Red, emu.lines[0][5].style.bg)
    }

    @Test
    fun `erase display 2J moves the screen into scrollback and clears the grid`() {
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2", "$esc[2J"))
        // The visible grid (bottom rows) is cleared, but prior output went to scrollback — it can be
        // scrolled up (gnome-terminal/VTE behavior), not lost.
        assertEquals(6, emu.lines.size) // 2 in scrollback + 4 blank on-screen
        assertEquals("line1", emu.lines[0].joinToString("") { it.text }.trimEnd())
        assertEquals("line2", emu.lines[1].joinToString("") { it.text }.trimEnd())
        assertTrue(emu.lines.takeLast(4).all { row -> row.all { it.text == " " } }, "screen cleared")
        // ED 2 doesn't move the cursor: cy stays at 1 → absolute scrollback.size(2) + 1.
        assertEquals(3, emu.cursorRow)
    }

    @Test
    fun `erase display 2J blanks in place on the alternate screen`() {
        // The alt screen has no scrollback — full-screen TUIs clear in place, no transfer to history.
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("$esc[?1049h", "line1\r\nline2", "$esc[2J"))
        assertEquals("", emu.asText())
        assertEquals(4, emu.lines.size)
    }

    @Test
    fun `erase display 3 keeps scrollback so clear preserves history`() {
        val emu = emulate(cols = 10, rows = 2, chunks = arrayOf("a\r\nb\r\nc\r\nd"))
        val before = emu.lines.size
        assertTrue(before > 2) // scrollback accumulated
        emu.feed("$esc[3J".encodeToByteArray())
        // ED 3 ("erase saved lines") does not wipe history — it stays scrollable.
        assertTrue(emu.lines.size >= before, "scrollback preserved")
    }

    @Test
    fun `clear sequence blanks the screen yet keeps prior output scrollable`() {
        // Exactly what the `clear` command sends: home, erase screen, erase saved lines.
        val emu = emulate(cols = 10, rows = 3, chunks = arrayOf("alpha\r\nbeta\r\ngamma", "$esc[H$esc[2J$esc[3J"))
        // Prior output is available in history...
        val text = emu.lines.joinToString("\n") { row -> row.joinToString("") { it.text }.trimEnd() }
        assertTrue("alpha" in text && "beta" in text && "gamma" in text, "history preserved")
        // ...while the visible grid (bottom 3 rows) is blank and the cursor is at its start (home).
        assertTrue(emu.lines.takeLast(3).all { row -> row.all { it.text == " " } }, "screen cleared")
        assertEquals(3, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `clear keeps a background-colored blank row in history`() {
        // A row of spaces with a colored background (BCE) is content, not emptiness: on clear it must go
        // to scrollback, not be trimmed as a trailing blank.
        val emu = emulate(cols = 4, rows = 3, chunks = arrayOf("ab\r\n", "$esc[41m    $esc[m"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray())
        assertEquals(5, emu.lines.size) // "ab" + the colored row in scrollback + 3 blank on-screen
        assertEquals(TermColor.Red, emu.lines[1][0].style.bg)
    }

    @Test
    fun `repeated clear does not flood scrollback with blank lines`() {
        val emu = emulate(cols = 10, rows = 3, chunks = arrayOf("x\r\ny"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray())
        val afterFirst = emu.lines.size
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray()) // clearing an already-empty screen
        assertEquals(afterFirst, emu.lines.size, "a repeated clear doesn't spawn empty rows in history")
    }

    @Test
    fun `resize after clear keeps the screen empty and history scrolled back`() {
        // After `clear`, prior output sits in scrollback and the visible screen is empty (a single fresh
        // prompt at the top). A resize — e.g. opening a split that narrows the terminal — must not pull
        // history back onto the visible screen: the empty space below the cursor is screen content, not
        // an "insignificant tail", and reflow must preserve it.
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2\r\nline3"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray()) // clear: history → scrollback, screen empty
        emu.feed("$ ".encodeToByteArray())                   // fresh prompt in the top screen row
        emu.resize(6, 4)                                     // narrow the terminal (like opening a split)

        val visible = emu.lines.takeLast(4)
        assertEquals("$", visible[0].joinToString("") { it.text }.trimEnd(), "prompt stays at the top of the screen")
        assertTrue(
            visible.drop(1).all { row -> row.all { it.text == " " } },
            "the screen below the prompt is empty — history didn't resurface",
        )
        val visibleText = visible.joinToString("\n") { row -> row.joinToString("") { it.text } }
        assertFalse("line1" in visibleText, "history didn't return to the visible screen after resize")
        // History is still available by scrolling.
        val allText = emu.lines.joinToString("\n") { row -> row.joinToString("") { it.text } }
        assertTrue("line1" in allText, "history preserved in scrollback")
    }

    @Test
    fun `resize after clear with height reduction pins the cursor on screen`() {
        // When a resize also reduces height, the nr-1 cap on the preserved space below the cursor must
        // keep the cursor within the new screen (not push it past the top boundary).
        val emu = emulate(cols = 10, rows = 4, chunks = arrayOf("line1\r\nline2\r\nline3"))
        emu.feed("$esc[H$esc[2J$esc[3J".encodeToByteArray()) // clear
        emu.feed("$ ".encodeToByteArray())                   // prompt at the top of the screen
        emu.resize(6, 2)                                     // narrower AND shorter

        assertTrue(emu.cursorRow < emu.lines.size, "cursor within the buffer")
        val visible = emu.lines.takeLast(2)
        assertEquals("$", visible[0].joinToString("") { it.text }.trimEnd(), "prompt stays on the visible screen")
        val visibleText = visible.joinToString("\n") { row -> row.joinToString("") { it.text } }
        assertFalse("line1" in visibleText, "history didn't resurface onto the shortened screen")
    }

    // Insert / delete

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

    // Scrolling / region

    @Test
    fun `scrolling off the top feeds scrollback`() {
        val emu = emulate(cols = 4, rows = 3, chunks = arrayOf("a\r\nb\r\nc\r\nd"))
        assertEquals("a\nb\nc\nd", emu.asText())
        assertEquals(4, emu.lines.size) // 1 in scrollback + 3 on-screen
    }

    @Test
    fun `scroll region confines scrolling`() {
        val emu = emulate(
            cols = 4, rows = 4,
            chunks = arrayOf("L0\r\nL1\r\nL2\r\nL3", "$esc[1;3r", "$esc[3;1H", "\n"),
        )
        // The row region 0..2 scrolled (L0 went to scrollback); L3 outside the region stayed.
        assertEquals("L0\nL1\nL2\n\nL3", emu.asText())
    }

    @Test
    fun `reverse index at top of region scrolls the region down`() {
        val emu = emulate(
            cols = 4, rows = 4,
            chunks = arrayOf("L0\r\nL1\r\nL2\r\nL3", "$esc[1;3r", "${esc}M"),
        )
        // Region 0..2 scrolled down: blank row on top, L2 pushed out, L3 (outside the region) stays.
        assertEquals("\nL0\nL1\nL3", emu.asText())
    }

    @Test
    fun `absolute cursor move cancels pending wrap`() {
        // cols=3: "abc" arms pending-wrap; CUP to (2,2) must clear it, else X would move.
        assertEquals("abc\n X", emulate(cols = 3, rows = 4, chunks = arrayOf("abc", "$esc[2;2H", "X")).asText())
    }

    @Test
    fun `clearing all tab stops sends tab to the last column`() {
        // ESC[3g clears all tab stops → the next TAB jumps to the last column.
        assertEquals("a        b", emulate(cols = 10, rows = 2, chunks = arrayOf("a", "$esc[3g", "\t", "b")).asText())
    }

    // Cursor save/restore

    @Test
    fun `save and restore cursor with esc 7 and esc 8`() {
        // ESC7 saves the position after "AB"; then go down and return with ESC8.
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

    // DSR/DA responses

    @Test
    fun `device status report returns cursor position`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[2;3H".encodeToByteArray()) // cursor at (2,3)
        emu.feed("$esc[6n".encodeToByteArray())   // position query
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
        // bracketed-paste is off by default → reset (2).
        emu.feed("$esc[?2004\$p".encodeToByteArray())
        assertEquals("$esc[?2004;2\$y", replies.last())
        // Enable → set (1).
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
        // DCS response: ESC P > | <name> ESC \
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
    fun `osc title decodes UTF-8 payload`() {
        val emu = emulate(chunks = arrayOf("$esc]2;Привет — сервер$bel"))
        assertEquals("Привет — сервер", emu.title)
    }

    @Test
    fun `osc title decodes UTF-8 split across feeds`() {
        val emu = TerminalEmulator(cols = 80, rows = 24)
        val bytes = "$esc]0;Ярус$bel".encodeToByteArray()
        // Split inside a two-byte character — OSC bytes accumulate until the terminator, decode must not break.
        emu.feed(bytes.copyOfRange(0, 5))
        emu.feed(bytes.copyOfRange(5, bytes.size))
        assertEquals("Ярус", emu.title)
    }

    @Test
    fun `osc 8 hyperlink URI decodes UTF-8`() {
        val emu = emulate(chunks = arrayOf("$esc]8;;https://пример.рф/путь${bel}X$esc]8;;$bel"))
        assertEquals("https://пример.рф/путь", emu.lines[0][0].hyperlink)
    }

    @Test
    fun `osc title strips embedded control characters`() {
        // A server must not be able to slip C0/DEL into the title (tab UI corruption, log injection risk).
        val c1 = 1.toChar(); val c31 = 31.toChar(); val del = 127.toChar()
        val emu = emulate(chunks = arrayOf("$esc]0;a${c1}b${c31}c$del$bel"))
        assertEquals("abc", emu.title)
    }

    @Test
    fun `CSI 22 t pushes and CSI 23 t pops the window title`() {
        // XTWINOPS title stack: vim/tmux save the title on entry (22;2 t) and restore it on exit
        // (23;2 t). Set A, push, change to B, pop -> A again.
        val emu = emulate(chunks = arrayOf("$esc]2;A$bel", "$esc[22;2t", "$esc]2;B$bel"))
        assertEquals("B", emu.title)
        emu.feed("$esc[23;2t".encodeToByteArray())
        assertEquals("A", emu.title)
    }

    @Test
    fun `title stack nests and second-param 0 also targets the title`() {
        // Ps=0 (icon+window) also pushes/pops the title; the stack is nested (LIFO).
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
        // Ps=1 — icon name only, we don't model the window title: 22;1/23;1 don't touch the stack.
        val emu = emulate(
            chunks = arrayOf("$esc]2;A$bel", "$esc[22;1t", "$esc]2;B$bel", "$esc[23;1t"),
        )
        assertEquals("B", emu.title) // icon-pop didn't restore A
    }

    @Test
    fun `RIS clears the title and its stack`() {
        // ESC c (RIS) resets the title to default and clears the stack: a subsequent pop is a no-op.
        val emu = emulate(chunks = arrayOf("$esc]2;A$bel", "$esc[22;2t", "${esc}c"))
        assertEquals("", emu.title)
        emu.feed("$esc[23;2t".encodeToByteArray())
        assertEquals("", emu.title)
    }

    // Combining marks (grapheme clusters)

    private val acute = "́" // combining acute accent (zero width)
    private val zwj = "‍"   // zero-width joiner

    @Test
    fun `combining accent attaches to the base cell without advancing`() {
        // "e" + U+0301 -> one cell, the cursor advanced by only 1.
        val emu = emulate(chunks = arrayOf("e$acute"))
        assertEquals("e$acute", emu.lines[0][0].text)
        assertEquals(" ", emu.lines[0][1].text) // the next cell is empty — the mark didn't take it
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `ZWJ joins into the previous cell`() {
        // ZWJ — zero width, must attach to the base rather than become its own cell.
        val emu = emulate(chunks = arrayOf("a$zwj"))
        assertEquals("a$zwj", emu.lines[0][0].text)
        assertEquals(1, emu.cursorCol)
    }

    @Test
    fun `combining mark attaches to a wide base not its continuation`() {
        // "中" (Wide) + U+0301: the mark goes into the Wide cell itself, the continuation is untouched; cursor at 2.
        val emu = emulate(chunks = arrayOf("中$acute"))
        assertEquals("中$acute", emu.lines[0][0].text)
        assertEquals(CellWidth.Wide, emu.lines[0][0].width)
        assertEquals(CellWidth.Continuation, emu.lines[0][1].width)
        assertEquals(2, emu.cursorCol)
    }

    @Test
    fun `leading combining mark on empty line does not crash`() {
        // No base to the left — the mark prints as a normal cell (fallback), the cursor advances by 1.
        val emu = emulate(chunks = arrayOf(acute))
        assertEquals(1, emu.cursorCol)
        emu.feed("X".encodeToByteArray())
        assertEquals("X", emu.lines[0][1].text)
    }

    // String sequences: DCS / APC / PM / SOS + XTGETTCAP

    @Test
    fun `DCS body is swallowed and does not leak to the screen`() {
        // Previously ESC P fell into Ground and the DCS body (sixel/DECRQSS) leaked as garbage text.
        // DCS q ... ST (a typical sixel envelope) must be swallowed whole; printing after is fine.
        val emu = emulate(chunks = arrayOf("A", "${esc}Pq#0;2;0;0;0~~$esc\\", "B"))
        assertEquals("AB", emu.asText())
    }

    @Test
    fun `APC kitty graphics envelope is swallowed`() {
        // Kitty graphics: APC G ... ST (ESC _ ... ESC \). Must not leak to the screen.
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
        // DCS + q <hex(name)> ; ... ST. Co=colors(256), TN=terminal name; unknown -> 0+r.
        val responses = mutableListOf<String>()
        val emu = TerminalEmulator(cols = 80, rows = 24, respond = { responses.add(it) })
        // "Co" = 436F, "ZZ" = 5A5A (unknown).
        emu.feed("${esc}P+q436F;5A5A$esc\\".encodeToByteArray())
        val joined = responses.joinToString("")
        // Valid Co reply: DCS 1 + r 436F = <hex("256")> ST ; hex("256") = 323536.
        assertTrue(joined.contains("1+r436F=323536"), "expected a valid Co=256, was: $joined")
        // Unknown ZZ -> DCS 0 + r 5A5A ST.
        assertTrue(joined.contains("0+r5A5A"), "expected a ZZ rejection, was: $joined")
    }

    @Test
    fun `XTGETTCAP caps the number of replied capabilities`() {
        // Amplification: one DCS with thousands of names must not spawn thousands of PTY responses.
        // 500 valid "Co" requests → no more responses than the limit (64).
        val responses = mutableListOf<String>()
        val emu = TerminalEmulator(cols = 80, rows = 24, respond = { responses.add(it) })
        val names = List(500) { "436F" }.joinToString(";")
        emu.feed("${esc}P+q$names$esc\\".encodeToByteArray())
        assertTrue(responses.size <= 64, "XTGETTCAP responses should be at most 64, was ${responses.size}")
    }

    @Test
    fun `bell triggers the callback`() {
        var rang = false
        TerminalEmulator(onBell = { rang = true }).feed(bel.encodeToByteArray())
        assertTrue(rang)
    }

    // Private modes

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
        // Setting = a steady bar; the app switches to a blinking block, then RIS (ESC c).
        val emu = TerminalEmulator(initialCursorShape = CursorShape.Bar, initialCursorBlink = false)
        emu.feed("$esc[1 q".encodeToByteArray()) // blinking block from the app
        assertEquals(CursorShape.Block, emu.cursorShape)
        emu.feed("${esc}c".encodeToByteArray())  // RIS
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
    }

    @Test
    fun `applyCursorDefault changes cursor live and survives RIS`() {
        val emu = TerminalEmulator() // start: blinking block
        emu.applyCursorDefault(CursorShape.Bar, blink = false)
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
        // The app overrides with its own DECSCUSR, then RIS returns to the new default, not the block.
        emu.feed("$esc[1 q".encodeToByteArray())
        assertEquals(CursorShape.Block, emu.cursorShape)
        emu.feed("${esc}c".encodeToByteArray())
        assertEquals(CursorShape.Bar, emu.cursorShape)
        assertFalse(emu.cursorBlink)
    }

    @Test
    fun `applyMaxScrollback shrinks history of an open session immediately`() {
        // Screen 2 rows, buffer 100; print 30 rows — most goes to scrollback.
        val emu = TerminalEmulator(cols = 10, rows = 2, maxScrollback = 100)
        val sb = StringBuilder()
        for (i in 0 until 30) sb.append("line$i\r\n")
        emu.feed(sb.toString().encodeToByteArray())
        assertTrue(emu.lines.size > 20, "history should accumulate in scrollback")

        // Shrink the buffer on the fly — excess old rows are trimmed immediately (scrollback=5 + 2 screen).
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
        // The new depth holds for subsequent output too: 3 scrollback + 2 screen.
        assertEquals(5, emu.lines.size)
    }

    @Test
    fun `decscusr selects steady block`() {
        // CSI 2 SP q — the space before 'q' is the DECSCUSR intermediate byte.
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
        // DECSET 1016: the app requests SGR-Pixels (pixel coordinates instead of cells).
        val emu = emulate(chunks = arrayOf("$esc[?1016h"))
        assertTrue(emu.mousePixels)
        emu.feed("$esc[?1016l".encodeToByteArray())
        assertFalse(emu.mousePixels)
    }

    @Test
    fun `DECRQM reports mouse pixel mode state`() {
        val replies = mutableListOf<String>()
        val emu = TerminalEmulator(respond = { replies += it })
        emu.feed("$esc[?1016\$p".encodeToByteArray())          // off by default → reset (2)
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
        // DECSET 1004: vim/tmux request window focus notifications.
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
        // cols=4: "ABCDEF" autowraps to "ABCD"+"EF" (soft wrap).
        val emu = emulate(cols = 4, rows = 6, chunks = arrayOf("ABCDEF"))
        assertEquals("ABCD\nEF", emu.asText())
        emu.resize(10, 6)
        // At width 10 both parts rejoin into one logical row.
        assertEquals("ABCDEF", emu.asText())
        assertTrue(emu.lines.all { it.size == 10 })
    }

    @Test
    fun `widening does not merge across a hard newline`() {
        val emu = emulate(cols = 10, rows = 6, chunks = arrayOf("AB\r\nCD"))
        emu.resize(40, 6)
        // An explicit newline is a logical-row boundary — no rejoin.
        assertEquals("AB\nCD", emu.asText())
    }

    @Test
    fun `narrowing reflows a long line onto the new width`() {
        // An 8-char line with no wrap (cols=10) is re-split when narrowed to 4.
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
        // "AB中C" at width 6: A B 中(wide, 2 cells) C, cursor past C (column 5).
        val emu = emulate(cols = 6, rows = 6, chunks = arrayOf("AB中C"))
        assertEquals(0, emu.cursorRow)
        assertEquals(5, emu.cursorCol)
        emu.resize(3, 6)
        // At width 3 the wide 中 doesn't fit after "AB" → wraps; cursor moves to the row after "中C".
        assertEquals("AB\n中C", emu.asText())
        assertEquals(2, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `widening keeps the cursor with its text`() {
        // cols=4 "ABCDEF": cursor after F — absolute row 1, column 2.
        val emu = emulate(cols = 4, rows = 6, chunks = arrayOf("ABCDEF"))
        assertEquals(1, emu.cursorRow)
        assertEquals(2, emu.cursorCol)
        emu.resize(10, 6)
        // After rejoin the cursor moves up one row, column 6 (after "ABCDEF").
        assertEquals(0, emu.cursorRow)
        assertEquals(6, emu.cursorCol)
    }

    // Cursor: absolute indices

    @Test
    fun `cursor row is absolute including scrollback`() {
        val emu = emulate(cols = 4, rows = 2, chunks = arrayOf("a\r\nb\r\nc"))
        // 1 row went to scrollback, cursor on the bottom screen row => absolute row 2.
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
        // ESC ( 0 switches G0 to DEC Special Graphics: q=─ x=│ l=┌ k=┐ j=┘ m=└ n=┼
        val emu = emulate(chunks = arrayOf("$esc(0lqk"))
        assertEquals("┌─┐", emu.asText())
    }

    @Test
    fun `ESC paren B restores ascii after line-drawing`() {
        // Draw a corner, then restore US-ASCII and print letters — they aren't translated.
        val emu = emulate(chunks = arrayOf("$esc(0qq", "${esc}(Bqq"))
        assertEquals("──qq", emu.asText())
    }

    @Test
    fun `shift-out invokes G1 line-drawing then shift-in restores G0`() {
        // ESC ) 0 puts line-drawing into G1; SO (0x0e) activates G1, SI (0x0f) restores G0(ASCII).
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
        // ESC 7 (DECSC) in ASCII; enable line-drawing and draw ─; ESC 8 (DECRC) must restore ASCII, so
        // the next q prints as a letter, not a glyph. \r returns the cursor to column 0.
        val emu = emulate(chunks = arrayOf("${esc}7$esc(0q", "${esc}8\rq"))
        assertEquals("q", emu.asText())
    }

    // Unicode width (double-width CJK/emoji + astral)

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
        // U+1F600 GRINNING FACE — astral (surrogate pair), previously turned into '�'.
        val emu = emulate(chunks = arrayOf("😀"))
        assertEquals("😀", emu.lines[0][0].text)
        assertEquals(CellWidth.Wide, emu.lines[0][0].width)
        assertEquals(2, emu.cursorCol)
    }

    @Test
    fun `wide char that does not fit the last column wraps to next line`() {
        // cols=2: the first 中 fills both columns (pending-wrap), the second wraps to the row below.
        val emu = emulate(cols = 2, rows = 3, chunks = arrayOf("中中"))
        assertEquals("中\n中", emu.asText())
        assertEquals("中", emu.lines[1][0].text)
    }

    @Test
    fun `wide char does not start in the last column with content after a narrow`() {
        // cols=3: a in col0, b in col1, 中 doesn't fit in col2 -> wraps to the next row.
        val emu = emulate(cols = 3, rows = 3, chunks = arrayOf("ab中"))
        assertEquals("ab\n中", emu.asText())
    }
}
