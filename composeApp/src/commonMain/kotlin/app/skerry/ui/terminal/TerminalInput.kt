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
 * Спецклавиши кодируются xterm-совместимо: курсорные и Home/End учитывают DECCKM
 * ([applicationCursor]) — в application-режиме шлются как SS3 (`ESC O x`), иначе CSI (`ESC[x`);
 * F-клавиши/Page/Insert/Delete — фиксированные CSI/SS3, на DECCKM не реагируют. [shift]+Tab даёт
 * back-tab (`ESC[Z`). [alt] = Meta: для печатных символов и C0-байтов (Ctrl/Backspace/Enter)
 * добавляется префикс ESC (readline word-ops, Alt+Backspace = удалить слово); на многобайтные
 * CSI/SS3-последовательности meta НЕ навешиваем, чтобы не слать некорректные модифицированные коды.
 *
 * Параметры — примитивы (а не `KeyEvent`), чтобы функция была чистой и тестируемой.
 */
fun mapTerminalKey(
    key: Key,
    ctrl: Boolean,
    codePoint: Int,
    alt: Boolean = false,
    shift: Boolean = false,
    applicationCursor: Boolean = false,
): String? {
    if (ctrl) {
        // Ctrl+A..Z → 0x01..0x1A; регистр символа не важен. Alt добавляет meta-префикс ESC.
        val base = when (codePoint) {
            in 'A'.code..'Z'.code -> codePoint - 'A'.code
            in 'a'.code..'z'.code -> codePoint - 'a'.code
            else -> return null
        }
        return meta(alt, (base + 1).toChar().toString())
    }
    // C0-байтовые клавиши редактирования — honor Alt=Meta (Alt+Backspace = удалить слово).
    when (key) {
        Key.Enter, Key.NumPadEnter -> return meta(alt, "\r")
        Key.Backspace -> return meta(alt, DEL)
        Key.Escape -> return meta(alt, ESC)
        // Shift+Tab — back-tab (многобайтный CSI, без meta); иначе одиночный HT, honor Alt=Meta.
        Key.Tab -> return if (shift) "$ESC[Z" else meta(alt, "\t")
    }
    // Навигация и F-клавиши: CSI/SS3-последовательности, meta к ним не добавляем.
    navKeySequence(key, applicationCursor)?.let { return it }
    if (codePoint != 0) {
        val ch = codePoint.toChar()
        if (!ch.isISOControl()) return meta(alt, ch.toString())
    }
    return null
}

/** Meta-обёртка: при зажатом Alt добавляет префикс ESC (xterm metaSendsEscape). */
private fun meta(alt: Boolean, seq: String): String = if (alt) ESC + seq else seq

/**
 * xterm-последовательность навигационной/функциональной клавиши или `null`, если [key] не из этого
 * набора. Стрелки и Home/End учитывают DECCKM: в application-режиме вводный код SS3 (`ESC O`),
 * иначе CSI (`ESC[`). Page/Insert/Delete и F-клавиши — фиксированы (на DECCKM не реагируют):
 * F1–F4 как SS3 `ESC O P..S`, F5–F12 как CSI `ESC[<n>~`, vt220-клавиши как CSI `ESC[<n>~`.
 */
private fun navKeySequence(key: Key, applicationCursor: Boolean): String? {
    val cursor = if (applicationCursor) "${ESC}O" else "$ESC["
    return when (key) {
        Key.DirectionUp -> "${cursor}A"
        Key.DirectionDown -> "${cursor}B"
        Key.DirectionRight -> "${cursor}C"
        Key.DirectionLeft -> "${cursor}D"
        Key.MoveHome -> "${cursor}H"
        Key.MoveEnd -> "${cursor}F"
        Key.Insert -> "$ESC[2~"
        Key.Delete -> "$ESC[3~"
        Key.PageUp -> "$ESC[5~"
        Key.PageDown -> "$ESC[6~"
        Key.F1 -> "${ESC}OP"
        Key.F2 -> "${ESC}OQ"
        Key.F3 -> "${ESC}OR"
        Key.F4 -> "${ESC}OS"
        Key.F5 -> "$ESC[15~"
        Key.F6 -> "$ESC[17~"
        Key.F7 -> "$ESC[18~"
        Key.F8 -> "$ESC[19~"
        Key.F9 -> "$ESC[20~"
        Key.F10 -> "$ESC[21~"
        Key.F11 -> "$ESC[23~"
        Key.F12 -> "$ESC[24~"
        else -> null
    }
}
