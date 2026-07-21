package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent

/** A key chord that means copy or paste in the terminal. */
enum class ClipboardChord { Copy, Paste }

/**
 * Recognises the terminal's clipboard chords, or `null` when the keys belong to the shell.
 *
 * Two conventions are supported side by side: `Ctrl+Shift+C/V` (what modern terminals use, because
 * plain `Ctrl+C` is SIGINT and `Ctrl+V` is a control code) and `Shift+Insert` / `Ctrl+Insert` (the
 * X11 pair, still muscle memory for many). Bare `Insert` is deliberately left alone — it is a real
 * terminal key (CSI 2~, the shell's overwrite toggle).
 *
 * [insertKey] is passed in rather than compared here because "the Insert key" is not one constant:
 * see [isInsertKey].
 */
fun clipboardChord(ctrl: Boolean, shift: Boolean, alt: Boolean, insertKey: Boolean, key: Key): ClipboardChord? {
    if (alt) return null // Alt+… is Meta-prefixed input for the shell
    if (insertKey) {
        // Both claim Ctrl+Shift+Insert; copy is the non-destructive reading of an ambiguous chord.
        if (ctrl) return ClipboardChord.Copy
        if (shift) return ClipboardChord.Paste
        return null
    }
    if (!ctrl || !shift) return null
    return when (key) {
        Key.C -> ClipboardChord.Copy
        Key.V -> ClipboardChord.Paste
        else -> null
    }
}

/**
 * Whether this event is the Insert key.
 *
 * Not simply `key == Key.Insert`: on the JVM the key code comes from AWT, and depending on the
 * toolkit and layout Insert can also arrive as the numeric keypad's 0 (NumLock off). Missing those
 * is what makes Shift+Insert silently do nothing — the key falls through to [mapTerminalKey] and the
 * shell receives CSI 2~ instead of a paste.
 */
expect fun KeyEvent.isInsertKey(): Boolean
