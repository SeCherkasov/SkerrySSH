package app.skerry.ui.snippet

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetShortcutTest {

    @Test
    fun `formats modifiers in a fixed order`() {
        assertEquals(
            "Ctrl+Shift+D",
            SnippetShortcut.format(ctrl = true, shift = true, alt = false, meta = false, key = Key.D),
        )
    }

    @Test
    fun `formats a single modifier with a digit`() {
        assertEquals(
            "Alt+1",
            SnippetShortcut.format(ctrl = false, shift = false, alt = true, meta = false, key = Key.One),
        )
    }

    @Test
    fun `supports function keys`() {
        assertEquals(
            "Ctrl+F5",
            SnippetShortcut.format(ctrl = true, shift = false, alt = false, meta = false, key = Key.F5),
        )
    }

    @Test
    fun `requires at least one modifier`() {
        assertNull(SnippetShortcut.format(ctrl = false, shift = false, alt = false, meta = false, key = Key.D))
    }

    @Test
    fun `rejects unsupported keys`() {
        assertNull(SnippetShortcut.format(ctrl = true, shift = false, alt = false, meta = false, key = Key.Spacebar))
    }
}
