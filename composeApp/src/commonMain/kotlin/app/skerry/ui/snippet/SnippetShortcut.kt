package app.skerry.ui.snippet

import androidx.compose.ui.input.key.Key

/**
 * Каноничная сериализация горячей клавиши сниппета в строку (`Ctrl+Shift+D`) — единый формат и для
 * захвата в редакторе, и для матча в глобальном обработчике, и для хранения в [app.skerry.shared.snippet.Snippet.shortcut].
 *
 * Требуем хотя бы один модификатор: одиночная буква перехватила бы обычный ввод. Поддержанные клавиши
 * — буквы, цифры и F1–F12 (остальные → `null`, хоткей не назначается). Порядок модификаторов
 * фиксирован (Ctrl, Shift, Alt, Meta), чтобы один и тот же аккорд всегда давал одну строку.
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
}
