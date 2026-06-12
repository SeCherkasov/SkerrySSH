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
