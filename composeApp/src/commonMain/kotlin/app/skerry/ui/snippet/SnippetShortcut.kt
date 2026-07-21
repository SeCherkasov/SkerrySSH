package app.skerry.ui.snippet

import androidx.compose.ui.input.key.Key

/**
 * Canonical string serialization of a snippet hotkey (`Ctrl+Shift+D`), used for editor capture,
 * global-handler matching, and storage in [app.skerry.shared.snippet.Snippet.shortcut].
 *
 * Requires at least one modifier (a bare letter would intercept normal input). Supported keys:
 * letters, digits, F1–F12 (others return `null`). Modifier order is fixed (Ctrl, Shift, Alt, Meta)
 * so the same chord always yields the same string.
 */
object SnippetShortcut {

    fun format(ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean, key: Key): String? {
        val name = keyName(key) ?: return null
        val mods = buildList {
            if (ctrl) add("Ctrl")
            if (shift) add("Shift")
            if (alt) add("Alt")
            if (meta) add("Meta")
        }
        if (mods.isEmpty()) return null
        return (mods + name).joinToString("+")
    }

    private val letters = (('A'..'Z')).zip(
        listOf(
            Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
            Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
        ),
    ).associate { (ch, k) -> k to ch.toString() }

    private val digits = (0..9).zip(
        listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine),
    ).associate { (n, k) -> k to n.toString() }

    private val functionKeys = (1..12).zip(
        listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9, Key.F10, Key.F11, Key.F12),
    ).associate { (n, k) -> k to "F$n" }

    private fun keyName(key: Key): String? =
        letters[key] ?: digits[key] ?: functionKeys[key]

    private val byName: Map<String, Key> by lazy {
        (letters + digits + functionKeys).entries.associate { (k, name) -> name to k }
    }

    /** The [Key] a [format]ted name stands for, or `null` if it isn't one Skerry can bind. */
    fun keyFor(name: String): Key? = byName[name]
}
