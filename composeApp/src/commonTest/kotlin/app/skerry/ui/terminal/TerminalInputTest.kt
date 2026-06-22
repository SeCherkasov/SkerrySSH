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
    fun `modified arrows send CSI with xterm modifier parameter`() {
        // xterm: mod = 1 + shift(1) + alt(2) + ctrl(4). Shift+Up → ESC[1;2A (mc выделяет файлы),
        // Ctrl+Right → ESC[1;5C (переход по словам в readline), Alt+Left → ESC[1;3D.
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '2'.code, 'A'.code),
            codes(mapTerminalKey(Key.DirectionUp, ctrl = false, shift = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '2'.code, 'B'.code),
            codes(mapTerminalKey(Key.DirectionDown, ctrl = false, shift = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '5'.code, 'C'.code),
            codes(mapTerminalKey(Key.DirectionRight, ctrl = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '3'.code, 'D'.code),
            codes(mapTerminalKey(Key.DirectionLeft, ctrl = false, alt = true, codePoint = 0)))
        // Alt+Up = mod 3.
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '3'.code, 'A'.code),
            codes(mapTerminalKey(Key.DirectionUp, ctrl = false, alt = true, codePoint = 0)))
    }

    @Test
    fun `combined modifiers sum into the CSI parameter`() {
        // Ctrl+Shift = 1 + 1 + 4 = 6.
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '6'.code, 'A'.code),
            codes(mapTerminalKey(Key.DirectionUp, ctrl = true, shift = true, codePoint = 0)))
        // Ctrl+Alt+Shift = 1 + 1 + 2 + 4 = 8.
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '8'.code, 'C'.code),
            codes(mapTerminalKey(Key.DirectionRight, ctrl = true, alt = true, shift = true, codePoint = 0)))
    }

    @Test
    fun `modified home and end send CSI with modifier`() {
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '2'.code, 'H'.code),
            codes(mapTerminalKey(Key.MoveHome, ctrl = false, shift = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '5'.code, 'F'.code),
            codes(mapTerminalKey(Key.MoveEnd, ctrl = true, codePoint = 0)))
    }

    @Test
    fun `modified arrows force CSI even in application-cursor mode`() {
        // С модификатором стрелки всегда CSI 1;<mod> — SS3 не несёт параметр модификатора.
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '2'.code, 'A'.code),
            codes(mapTerminalKey(Key.DirectionUp, ctrl = false, shift = true, codePoint = 0, applicationCursor = true)))
    }

    @Test
    fun `modified tilde keys send CSI num semicolon mod tilde`() {
        // Ctrl+Delete → ESC[3;5~ (удалить слово вперёд), Shift+PageUp → ESC[5;2~.
        assertEquals(listOf(0x1b, '['.code, '3'.code, ';'.code, '5'.code, '~'.code),
            codes(mapTerminalKey(Key.Delete, ctrl = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '5'.code, ';'.code, '2'.code, '~'.code),
            codes(mapTerminalKey(Key.PageUp, ctrl = false, shift = true, codePoint = 0)))
        // F5 (CSI 15~) с Ctrl → ESC[15;5~, с Shift → ESC[15;2~.
        assertEquals(listOf(0x1b, '['.code, '1'.code, '5'.code, ';'.code, '5'.code, '~'.code),
            codes(mapTerminalKey(Key.F5, ctrl = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, '5'.code, ';'.code, '2'.code, '~'.code),
            codes(mapTerminalKey(Key.F5, ctrl = false, shift = true, codePoint = 0)))
    }

    @Test
    fun `modified F1 to F4 switch from SS3 to CSI with modifier`() {
        // F1 без модификатора = ESC O P; с Shift → ESC[1;2P.
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '2'.code, 'P'.code),
            codes(mapTerminalKey(Key.F1, ctrl = false, shift = true, codePoint = 0)))
        assertEquals(listOf(0x1b, '['.code, '1'.code, ';'.code, '5'.code, 'S'.code),
            codes(mapTerminalKey(Key.F4, ctrl = true, codePoint = 0)))
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
    fun `ctrl letter works when desktop delivers the control char as codePoint`() {
        // Реальный Compose Desktop: AWT отдаёт Ctrl+C с keyChar 0x03 → utf16CodePoint == 3.
        // Раньше это падало в null (3 не в 'A'..'Z'/'a'..'z'), и Ctrl-комбо не работали вживую.
        assertEquals(listOf(0x03), codes(mapTerminalKey(Key.C, ctrl = true, codePoint = 3)))
        assertEquals(listOf(0x1a), codes(mapTerminalKey(Key.Z, ctrl = true, codePoint = 26)))
    }

    @Test
    fun `ctrl letter derives the control byte from the physical key`() {
        // Когда AWT не отдаёт символ (CHAR_UNDEFINED 0xFFFF), опираемся на физическую клавишу.
        assertEquals(listOf(0x03), codes(mapTerminalKey(Key.C, ctrl = true, codePoint = 0xFFFF)))
        assertEquals(listOf(0x04), codes(mapTerminalKey(Key.D, ctrl = true, codePoint = 0xFFFF)))
    }

    @Test
    fun `ctrl bracket symbols send their control bytes`() {
        assertEquals(listOf(0x1b), codes(mapTerminalKey(Key.LeftBracket, ctrl = true, codePoint = 0x1b))) // Ctrl+[ = ESC
        assertEquals(listOf(0x1c), codes(mapTerminalKey(Key.Backslash, ctrl = true, codePoint = 0x1c)))   // Ctrl+\ = FS
        assertEquals(listOf(0x1d), codes(mapTerminalKey(Key.RightBracket, ctrl = true, codePoint = 0x1d)))// Ctrl+] = GS
    }

    @Test
    fun `bare alt produces nothing (no garbage glyph)`() {
        // AWT отдаёт одинокие модификаторы с keyChar CHAR_UNDEFINED (0xFFFF) — это НЕ текст.
        assertNull(mapTerminalKey(Key.AltLeft, ctrl = false, alt = true, codePoint = 0xFFFF))
        assertNull(mapTerminalKey(Key.AltRight, ctrl = false, alt = true, codePoint = 0xFFFF))
    }

    @Test
    fun `CHAR_UNDEFINED codepoint is never sent as a printable char`() {
        assertNull(mapTerminalKey(Key.Unknown, ctrl = false, codePoint = 0xFFFF))
    }

    @Test
    fun `alt letter sends meta even when desktop omits the codePoint`() {
        // Linux AWT отдаёт Alt+b с keyChar == CHAR_UNDEFINED; букву берём с физической клавиши.
        assertEquals(listOf(0x1b, 'b'.code), codes(mapTerminalKey(Key.B, ctrl = false, alt = true, codePoint = 0xFFFF)))
        assertEquals(listOf(0x1b, 'F'.code), codes(mapTerminalKey(Key.F, ctrl = false, alt = true, shift = true, codePoint = 0xFFFF)))
    }

    @Test
    fun `focus report sequences are CSI I and CSI O`() {
        // DECSET 1004: фокус окна → ESC[I, потеря фокуса → ESC[O.
        assertEquals(listOf(0x1b, '['.code, 'I'.code), codes(focusReportSequence(focused = true)))
        assertEquals(listOf(0x1b, '['.code, 'O'.code), codes(focusReportSequence(focused = false)))
    }

    @Test
    fun `bare modifier or unknown key is ignored`() {
        assertNull(mapTerminalKey(Key.CtrlLeft, ctrl = false, codePoint = 0))
        assertNull(mapTerminalKey(Key.ShiftLeft, ctrl = false, codePoint = 0))
        assertNull(mapTerminalKey(Key.Unknown, ctrl = false, codePoint = 0))
    }
}
