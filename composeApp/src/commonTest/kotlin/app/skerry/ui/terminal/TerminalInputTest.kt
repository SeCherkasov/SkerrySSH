package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ожидаемые управляющие байты проверяются по ЧИСЛОВЫМ кодам символов, а не литералами — иначе
 * ESC/DEL были бы невидимы в Read/grep и легко разъезжались бы с реализацией.
 */
class TerminalInputTest {

    private fun codes(s: String?): List<Int>? = s?.map { it.code }

    @Test
    fun `printable character is sent as-is`() {
        assertEquals("a", mapTerminalKey(Key.A, ctrl = false, codePoint = 'a'.code))
        assertEquals("Z", mapTerminalKey(Key.Z, ctrl = false, codePoint = 'Z'.code))
        assertEquals("7", mapTerminalKey(Key.Seven, ctrl = false, codePoint = '7'.code))
    }

    @Test
    fun `enter sends carriage return`() {
        assertEquals(listOf(0x0d), codes(mapTerminalKey(Key.Enter, ctrl = false, codePoint = 13)))
        assertEquals(listOf(0x0d), codes(mapTerminalKey(Key.NumPadEnter, ctrl = false, codePoint = 0)))
    }

    @Test
    fun `backspace sends DEL 0x7f`() {
        assertEquals(listOf(0x7f), codes(mapTerminalKey(Key.Backspace, ctrl = false, codePoint = 8)))
    }

    @Test
    fun `tab and escape are forwarded`() {
        assertEquals(listOf(0x09), codes(mapTerminalKey(Key.Tab, ctrl = false, codePoint = 9)))
        assertEquals(listOf(0x1b), codes(mapTerminalKey(Key.Escape, ctrl = false, codePoint = 27)))
    }

    @Test
    fun `arrow keys send xterm sequences`() {
        assertEquals(listOf(0x1b, '['.code, 'A'.code), codes(mapTerminalKey(Key.DirectionUp, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, 'B'.code), codes(mapTerminalKey(Key.DirectionDown, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, 'C'.code), codes(mapTerminalKey(Key.DirectionRight, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, 'D'.code), codes(mapTerminalKey(Key.DirectionLeft, ctrl = false, codePoint = 0)))
    }

    @Test
    fun `home end delete send xterm sequences`() {
        assertEquals(listOf(0x1b, '['.code, 'H'.code), codes(mapTerminalKey(Key.MoveHome, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, 'F'.code), codes(mapTerminalKey(Key.MoveEnd, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '3'.code, '~'.code), codes(mapTerminalKey(Key.Delete, ctrl = false, codePoint = 0)))
    }

    @Test
    fun `application-cursor mode sends arrows as SS3`() {
        // DECCKM on (vim/less/htop): arrows switch from CSI (ESC[A) to SS3 (ESC O A).
        assertEquals(listOf(0x1b, 'O'.code, 'A'.code), codes(mapTerminalKey(Key.DirectionUp, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, 'O'.code, 'B'.code), codes(mapTerminalKey(Key.DirectionDown, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, 'O'.code, 'C'.code), codes(mapTerminalKey(Key.DirectionRight, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, 'O'.code, 'D'.code), codes(mapTerminalKey(Key.DirectionLeft, ctrl = false, codePoint = 0, applicationCursor = true)))
    }

    @Test
    fun `application-cursor mode sends home and end as SS3`() {
        assertEquals(listOf(0x1b, 'O'.code, 'H'.code), codes(mapTerminalKey(Key.MoveHome, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, 'O'.code, 'F'.code), codes(mapTerminalKey(Key.MoveEnd, ctrl = false, codePoint = 0, applicationCursor = true)))
    }

    @Test
    fun `function keys F1 to F4 send SS3 sequences`() {
        assertEquals(listOf(0x1b, 'O'.code, 'P'.code), codes(mapTerminalKey(Key.F1, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, 'O'.code, 'Q'.code), codes(mapTerminalKey(Key.F2, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, 'O'.code, 'R'.code), codes(mapTerminalKey(Key.F3, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, 'O'.code, 'S'.code), codes(mapTerminalKey(Key.F4, ctrl = false, codePoint = 0)))
    }

    @Test
    fun `function keys F5 to F12 send CSI tilde sequences`() {
        assertEquals(listOf(0x1b, '['.code, '1'.code, '5'.code, '~'.code), codes(mapTerminalKey(Key.F5, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, '7'.code, '~'.code), codes(mapTerminalKey(Key.F6, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, '8'.code, '~'.code), codes(mapTerminalKey(Key.F7, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, '9'.code, '~'.code), codes(mapTerminalKey(Key.F8, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '2'.code, '0'.code, '~'.code), codes(mapTerminalKey(Key.F9, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '2'.code, '1'.code, '~'.code), codes(mapTerminalKey(Key.F10, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '2'.code, '3'.code, '~'.code), codes(mapTerminalKey(Key.F11, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '2'.code, '4'.code, '~'.code), codes(mapTerminalKey(Key.F12, ctrl = false, codePoint = 0)))
    }

    @Test
    fun `page and insert keys send CSI tilde sequences`() {
        assertEquals(listOf(0x1b, '['.code, '5'.code, '~'.code), codes(mapTerminalKey(Key.PageUp, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '6'.code, '~'.code), codes(mapTerminalKey(Key.PageDown, ctrl = false, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '2'.code, '~'.code), codes(mapTerminalKey(Key.Insert, ctrl = false, codePoint = 0)))
    }

    @Test
    fun `page keys ignore application-cursor mode`() {
        // Only arrows + Home/End honor DECCKM; page/insert/delete/function keys are fixed CSI/SS3.
        assertEquals(listOf(0x1b, '['.code, '5'.code, '~'.code), codes(mapTerminalKey(Key.PageUp, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, '['.code, '2'.code, '~'.code), codes(mapTerminalKey(Key.Insert, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, '['.code, '3'.code, '~'.code), codes(mapTerminalKey(Key.Delete, ctrl = false, codePoint = 0, applicationCursor = true)))
        assertEquals(listOf(0x1b, 'O'.code, 'P'.code), codes(mapTerminalKey(Key.F1, ctrl = false, codePoint = 0, applicationCursor = true)))
    }

    @Test
    fun `shift tab sends back-tab CSI Z`() {
        assertEquals(listOf(0x1b, '['.code, 'Z'.code), codes(mapTerminalKey(Key.Tab, ctrl = false, shift = true, codePoint = 9)))
        // Plain Tab stays a literal tab.
        assertEquals(listOf(0x09), codes(mapTerminalKey(Key.Tab, ctrl = false, shift = false, codePoint = 9)))
    }

    @Test
    fun `alt prefixes ESC on printable characters (meta)`() {
        // Alt=Meta: Alt+b → ESC b (readline word ops). ESC is 0x1b.
        assertEquals(listOf(0x1b, 'b'.code), codes(mapTerminalKey(Key.B, ctrl = false, alt = true, codePoint = 'b'.code)))
        assertEquals(listOf(0x1b, 'f'.code), codes(mapTerminalKey(Key.F, ctrl = false, alt = true, codePoint = 'f'.code)))
    }

    @Test
    fun `alt backspace sends ESC DEL (delete previous word)`() {
        assertEquals(listOf(0x1b, 0x7f), codes(mapTerminalKey(Key.Backspace, ctrl = false, alt = true, codePoint = 8)))
    }

    @Test
    fun `alt prefixes ESC on single-byte editing keys`() {
        // Alt=Meta also applies to the other single C0-byte keys (Enter/Tab/Escape).
        assertEquals(listOf(0x1b, 0x0d), codes(mapTerminalKey(Key.Enter, ctrl = false, alt = true, codePoint = 13)))
        assertEquals(listOf(0x1b, 0x09), codes(mapTerminalKey(Key.Tab, ctrl = false, alt = true, codePoint = 9)))
        assertEquals(listOf(0x1b, 0x1b), codes(mapTerminalKey(Key.Escape, ctrl = false, alt = true, codePoint = 27)))
        // Alt+Shift+Tab stays raw back-tab (multi-byte CSI — no meta prefix).
        assertEquals(listOf(0x1b, '['.code, 'Z'.code), codes(mapTerminalKey(Key.Tab, ctrl = false, alt = true, shift = true, codePoint = 9)))
    }

    @Test
    fun `ctrl alt letter prefixes ESC on the control byte`() {
        assertEquals(listOf(0x1b, 0x02), codes(mapTerminalKey(Key.B, ctrl = true, alt = true, codePoint = 'b'.code))) // Alt+Ctrl+B
    }

    @Test
    fun `ctrl plus letter sends the control byte`() {
        assertEquals(listOf(0x03), codes(mapTerminalKey(Key.C, ctrl = true, codePoint = 'c'.code))) // Ctrl+C = ETX
        assertEquals(listOf(0x04), codes(mapTerminalKey(Key.D, ctrl = true, codePoint = 'd'.code))) // Ctrl+D = EOT
        assertEquals(listOf(0x1a), codes(mapTerminalKey(Key.Z, ctrl = true, codePoint = 'z'.code))) // Ctrl+Z = SUB
        // Регистр не важен — Ctrl+Shift+C тоже ETX
        assertEquals(listOf(0x03), codes(mapTerminalKey(Key.C, ctrl = true, codePoint = 'C'.code)))
    }

    @Test
    fun `ctrl plus non-letter is ignored`() {
        assertNull(mapTerminalKey(Key.One, ctrl = true, codePoint = '1'.code))
    }

    @Test
    fun `bare modifier or unknown key is ignored`() {
        assertNull(mapTerminalKey(Key.CtrlLeft, ctrl = false, codePoint = 0))
        assertNull(mapTerminalKey(Key.ShiftLeft, ctrl = false, codePoint = 0))
        assertNull(mapTerminalKey(Key.Unknown, ctrl = false, codePoint = 0))
    }
}
