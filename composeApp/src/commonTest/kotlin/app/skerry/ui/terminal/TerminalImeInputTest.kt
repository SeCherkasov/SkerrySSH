package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IME-ввод на тач-платформах: скрытое поле всегда сбрасывается к якорю [ANCHOR], поэтому
 * новое значение поля = якорь ± правки пользователя. [imeDeltaToPty] диффит их и отдаёт байты
 * в PTY. Управляющие коды (CR/DEL) проверяются по числам — иначе были бы невидимы (как ESC/DEL
 * в [TerminalInputTest]).
 */
class TerminalImeInputTest {

    private val anchor = ANCHOR
    private fun codes(s: String): List<Int> = s.map { it.code }
    private fun delta(value: String) = imeDeltaToPty(anchor, value)

    @Test
    fun `no change yields empty`() {
        assertEquals("", delta(anchor))
    }

    @Test
    fun `single typed character is sent as-is`() {
        assertEquals("a", delta(anchor + "a"))
    }

    @Test
    fun `multiple typed characters are sent in order`() {
        assertEquals("ls -la", delta(anchor + "ls -la"))
    }

    @Test
    fun `newline from the keyboard becomes carriage return`() {
        assertEquals(listOf(0x0d), codes(delta(anchor + "\n")))
        // Команда + Enter одним изменением: текст, затем CR.
        assertEquals(listOf('l'.code, 's'.code, 0x0d), codes(delta(anchor + "ls\n")))
    }

    @Test
    fun `deleting the anchor tail sends DEL`() {
        // Backspace на пустом терминале «съедает» якорь — это Backspace для shell.
        assertEquals(listOf(0x7f), codes(delta("")))
    }

    @Test
    fun `replacement past common prefix deletes then inserts`() {
        // Автокоррекция/замена: якорь, дальше вставка X — общий префикс якорь, остаток вставляется.
        assertEquals("X", delta(anchor + "X"))
    }

    @Test
    fun `space is a normal character`() {
        assertEquals(" ", delta(anchor + " "))
    }
}
