package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardChordTest {

    private fun chord(
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
        insert: Boolean = false,
        key: Key = Key.Unknown,
    ) = clipboardChord(ctrl, shift, alt, insert, key)

    @Test
    fun `Ctrl plus Shift plus V pastes`() {
        assertEquals(ClipboardChord.Paste, chord(ctrl = true, shift = true, key = Key.V))
    }

    @Test
    fun `Shift plus Insert pastes`() {
        // The X11 convention, and what a keyboard's Insert key produces regardless of how Compose
        // names it — the caller resolves "is this the Insert key" per platform.
        assertEquals(ClipboardChord.Paste, chord(shift = true, insert = true))
    }

    @Test
    fun `Ctrl plus Shift plus C copies`() {
        assertEquals(ClipboardChord.Copy, chord(ctrl = true, shift = true, key = Key.C))
    }

    @Test
    fun `Ctrl plus Insert copies`() {
        assertEquals(ClipboardChord.Copy, chord(ctrl = true, insert = true))
    }

    @Test
    fun `plain Insert is left to the terminal`() {
        // Bare Insert is a real terminal key (CSI 2~) — the shell's overwrite toggle lives on it.
        assertNull(chord(insert = true))
    }

    @Test
    fun `Alt plus Insert is not a clipboard chord`() {
        assertNull(chord(shift = true, alt = true, insert = true))
        assertNull(chord(ctrl = true, alt = true, insert = true))
    }

    @Test
    fun `Ctrl plus Shift plus Insert prefers copy over paste`() {
        // Both conventions claim it; copying is the non-destructive reading.
        assertEquals(ClipboardChord.Copy, chord(ctrl = true, shift = true, insert = true))
    }

    @Test
    fun `unmodified letters are not clipboard chords`() {
        assertNull(chord(key = Key.V))
        assertNull(chord(ctrl = true, key = Key.V)) // Ctrl+V is a terminal control code
        assertNull(chord(ctrl = true, key = Key.C)) // Ctrl+C must stay SIGINT
        assertNull(chord(shift = true, key = Key.V))
    }
}
