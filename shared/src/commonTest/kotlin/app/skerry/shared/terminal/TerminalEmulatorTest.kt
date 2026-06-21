package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalEmulatorTest {

    // ESC/BEL задаём числом — никаких невидимых управляющих байтов в исходнике.
    private val esc = 27.toChar().toString()
    private val bel = 7.toChar().toString()

    private fun emulate(vararg chunks: String): TerminalEmulator {
        val emu = TerminalEmulator()
        chunks.forEach { emu.feed(it.encodeToByteArray()) }
        return emu
    }

    /** Текст экрана: строки через \n, хвостовые пробелы обрезаны. */
    private fun TerminalEmulator.asText(): String =
        lines.joinToString("\n") { row -> row.joinToString("") { it.char.toString() }.trimEnd() }

    @Test
    fun `plain text fills one line`() {
        assertEquals("hello", emulate("hello").asText())
    }

    @Test
    fun `crlf starts a new line at column zero`() {
        // PTY транслирует LF→CRLF; LF опускает строку, CR возвращает колонку.
        assertEquals("ab\ncd", emulate("ab\r\ncd").asText())
    }

    @Test
    fun `bare lf keeps the column (staircase)`() {
        // Без CR колонка сохраняется — классический «лестничный» эффект VT.
        assertEquals("ab\n  cd", emulate("ab\ncd").asText())
    }

    @Test
    fun `carriage return moves cursor to column zero and overwrites`() {
        assertEquals("Xbc", emulate("abc\rX").asText())
    }

    @Test
    fun `backspace moves cursor left and next char overwrites`() {
        assertEquals("abX", emulate("abc\bX").asText())
    }

    @Test
    fun `tab advances to next multiple of eight`() {
        assertEquals("a       b", emulate("a\tb").asText())
    }

    @Test
    fun `sgr sets foreground color until reset`() {
        val emu = emulate("${esc}[31mR${esc}[0mG")
        assertEquals(TermColor.Red, emu.lines[0][0].style.fg)
        assertEquals(TermColor.Default, emu.lines[0][1].style.fg)
        assertEquals("RG", emu.asText())
    }

    @Test
    fun `sgr bold flag is tracked`() {
        val emu = emulate("${esc}[1mB${esc}[22mn")
        assertTrue(emu.lines[0][0].style.bold)
        assertTrue(!emu.lines[0][1].style.bold)
    }

    @Test
    fun `sgr bright foreground 91 maps to bright red`() {
        val emu = emulate("${esc}[91mR")
        assertEquals(TermColor.BrightRed, emu.lines[0][0].style.fg)
    }

    @Test
    fun `erase to end of line clears from cursor`() {
        assertEquals("abc", emulate("abcdef", "${esc}[3D", "${esc}[0K").asText())
    }

    @Test
    fun `cursor forward and back reposition within the line`() {
        assertEquals("aXc", emulate("abc", "${esc}[2D", "X").asText())
    }

    @Test
    fun `erase screen 2J clears all lines`() {
        val emu = emulate("line1\nline2", "${esc}[2J")
        assertEquals("", emu.asText())
        assertEquals(0, emu.cursorRow)
        assertEquals(0, emu.cursorCol)
    }

    @Test
    fun `osc title sequence is consumed`() {
        assertEquals("X", emulate("${esc}]0;my title${bel}X").asText())
    }

    @Test
    fun `unknown csi device-status query is ignored`() {
        assertEquals("ok", emulate("${esc}[6nok").asText())
    }

    @Test
    fun `private mode set show-cursor is ignored`() {
        assertEquals("p", emulate("${esc}[?25hp").asText())
    }

    @Test
    fun `application cursor keys mode off by default`() {
        assertTrue(!TerminalEmulator().applicationCursorKeys)
    }

    @Test
    fun `decckm set and reset toggles application cursor keys`() {
        // ESC[?1h включает application-cursor-keys (vim/less), ESC[?1l возвращает в нормальный режим.
        val emu = emulate("${esc}[?1h")
        assertTrue(emu.applicationCursorKeys)
        emu.feed("${esc}[?1l".encodeToByteArray())
        assertTrue(!emu.applicationCursorKeys)
    }

    @Test
    fun `decckm does not affect printed text`() {
        assertEquals("ok", emulate("${esc}[?1hok").asText())
    }

    @Test
    fun `unrelated private mode does not arm application cursor keys`() {
        // 1049 (alt-screen) и 2004 (bracketed paste) не должны трогать DECCKM.
        val emu = emulate("${esc}[?1049h${esc}[?2004h")
        assertTrue(!emu.applicationCursorKeys)
    }

    @Test
    fun `utf8 multibyte split across feeds decodes to one cell`() {
        val emu = TerminalEmulator()
        emu.feed(byteArrayOf(0xD0.toByte()))
        emu.feed(byteArrayOf(0x9F.toByte()))
        assertEquals("П", emu.asText())
        assertEquals(1, emu.lines[0].size)
    }
}
