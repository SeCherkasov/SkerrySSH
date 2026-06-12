package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key

/** ESC (0x1b) и DEL (0x7f) — единственное место с \u-эскейпом, дальше собираем шаблонами. */
private const val ESC = ""
private const val DEL = ""

/**
 * Перевод нажатия клавиши в байты для PTY — raw-режим интерактивного терминала: символы уходят
 * в shell посимвольно, эхо рисует сам shell. Возвращает строку для отправки в сессию или `null`,
 * если клавишу игнорируем (одинокий модификатор, неподдержанная комбинация).
 *
 * Спецклавиши кодируются xterm-совместимо. ANSI/VT-эмулятора у нас ещё нет, поэтому управляющие
 * последовательности отправляются «как есть» (`ESC[...`), а интерпретирует их удалённая сторона.
 * Параметры — примитивы (а не `KeyEvent`), чтобы функция была чистой и тестируемой.
 */
fun mapTerminalKey(key: Key, ctrl: Boolean, codePoint: Int): String? {
    if (ctrl) {
        // Ctrl+A..Z → 0x01..0x1A; регистр символа не важен.
        val base = when (codePoint) {
            in 'A'.code..'Z'.code -> codePoint - 'A'.code
            in 'a'.code..'z'.code -> codePoint - 'a'.code
            else -> return null
        }
        return (base + 1).toChar().toString()
    }
    when (key) {
        Key.Enter, Key.NumPadEnter -> return "\r"
        Key.Backspace -> return DEL
        Key.Tab -> return "\t"
        Key.Escape -> return ESC
        Key.DirectionUp -> return "$ESC[A"
        Key.DirectionDown -> return "$ESC[B"
        Key.DirectionRight -> return "$ESC[C"
        Key.DirectionLeft -> return "$ESC[D"
        Key.MoveHome -> return "$ESC[H"
        Key.MoveEnd -> return "$ESC[F"
        Key.Delete -> return "$ESC[3~"
    }
    if (codePoint != 0) {
        val ch = codePoint.toChar()
        if (!ch.isISOControl()) return ch.toString()
    }
    return null
}
