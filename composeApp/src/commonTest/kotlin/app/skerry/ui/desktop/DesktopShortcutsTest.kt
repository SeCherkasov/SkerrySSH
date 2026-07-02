package app.skerry.ui.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopShortcutsTest {

    private fun match(ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false, meta: Boolean = false, key: Key) =
        matchDesktopShortcut(ctrl, shift, alt, meta, key)

    @Test
    fun `Alt plus digit selects the tab by zero-based index`() {
        assertEquals(DesktopShortcut.SelectTab(0), match(alt = true, key = Key.One))
        assertEquals(DesktopShortcut.SelectTab(2), match(alt = true, key = Key.Three))
        assertEquals(DesktopShortcut.SelectTab(8), match(alt = true, key = Key.Nine))
    }

    @Test
    fun `Alt plus zero is not a tab shortcut`() {
        assertNull(match(alt = true, key = Key.Zero))
    }

    @Test
    fun `AltGr (Ctrl plus Alt) plus digit is left to the terminal`() {
        assertNull(match(ctrl = true, alt = true, key = Key.One))
    }

    @Test
    fun `Ctrl plus Tab cycles tabs`() {
        assertEquals(DesktopShortcut.NextTab, match(ctrl = true, key = Key.Tab))
        assertEquals(DesktopShortcut.PrevTab, match(ctrl = true, shift = true, key = Key.Tab))
    }

    @Test
    fun `app modifier on macOS is Cmd alone`() {
        assertEquals(DesktopShortcut.NewConnection, match(meta = true, key = Key.N))
        assertEquals(DesktopShortcut.SplitTerminal, match(meta = true, key = Key.D))
        assertEquals(DesktopShortcut.OpenSftp, match(meta = true, key = Key.F))
        assertEquals(DesktopShortcut.Lock, match(meta = true, key = Key.L))
        assertEquals(DesktopShortcut.FocusAiBar, match(meta = true, key = Key.Slash))
    }

    @Test
    fun `app modifier off macOS is Ctrl plus Shift`() {
        assertEquals(DesktopShortcut.NewConnection, match(ctrl = true, shift = true, key = Key.N))
        assertEquals(DesktopShortcut.SplitTerminal, match(ctrl = true, shift = true, key = Key.D))
        assertEquals(DesktopShortcut.OpenSftp, match(ctrl = true, shift = true, key = Key.F))
        assertEquals(DesktopShortcut.Lock, match(ctrl = true, shift = true, key = Key.L))
        assertEquals(DesktopShortcut.FocusAiBar, match(ctrl = true, shift = true, key = Key.Slash))
    }

    @Test
    fun `plain Ctrl plus letter is left to the terminal`() {
        // Ctrl+L очистка экрана, Ctrl+D EOF, Ctrl+N — не должны перехватываться.
        assertNull(match(ctrl = true, key = Key.L))
        assertNull(match(ctrl = true, key = Key.D))
        assertNull(match(ctrl = true, key = Key.N))
    }

    @Test
    fun `plain Alt plus letter (terminal Meta prefix) is not an app shortcut`() {
        assertNull(match(alt = true, key = Key.N))
        assertNull(match(alt = true, key = Key.D))
    }

    @Test
    fun `unmodified letters and digits are ignored`() {
        assertNull(match(key = Key.N))
        assertNull(match(key = Key.One))
        assertNull(match(shift = true, key = Key.D))
    }
}
