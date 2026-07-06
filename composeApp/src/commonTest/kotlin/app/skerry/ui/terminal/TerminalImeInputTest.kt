package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IME input on touch platforms: the hidden field always resets to the anchor [ANCHOR], so the
 * field's new value = anchor plus the user's edits. [imeDeltaToPty] diffs them and emits the
 * bytes sent to the PTY. Control codes (CR/DEL) are checked by numeric code, as in
 * [TerminalInputTest].
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
        // Command + Enter as a single change: text, then CR.
        assertEquals(listOf('l'.code, 's'.code, 0x0d), codes(delta(anchor + "ls\n")))
    }

    @Test
    fun `deleting the anchor tail sends DEL`() {
        // Backspace on an empty terminal eats the anchor, becoming Backspace for the shell.
        assertEquals(listOf(0x7f), codes(delta("")))
    }

    @Test
    fun `replacement past common prefix deletes then inserts`() {
        // Autocorrect/replacement: common prefix is the anchor, so X is just inserted after it.
        assertEquals("X", delta(anchor + "X"))
    }

    @Test
    fun `space is a normal character`() {
        assertEquals(" ", delta(anchor + " "))
    }
}
