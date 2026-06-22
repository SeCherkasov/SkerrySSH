package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key

/**
 * AWT отдаёт keyChar = CHAR_UNDEFINED (0xFFFF) для «нажатий без символа»: одинокие модификаторы и
 * Alt+буква на Linux. Compose кладёт это значение прямо в `utf16CodePoint`, поэтому такой codePoint
 * мусорный и НЕ должен попадать в PTY как печатный символ.
 */
private const val CHAR_UNDEFINED = 0xffff

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
 * F-клавиши/Page/Insert/Delete — фиксированные CSI/SS3, на DECCKM не реагируют. С зажатым
 * Shift/Ctrl/Alt спецклавиши кодируются как modifyOtherKeys-CSI (`ESC[1;<mod>x` для стрелок/Home/
 * End/F1–F4, `ESC[<n>;<mod>~` для tilde-клавиш), где `mod = 1 + Shift + Alt·2 + Ctrl·4` — это даёт
 * Shift+стрелку для выделения в mc, Ctrl+стрелку для перехода по словам и т.п. [shift]+Tab даёт
 * back-tab (`ESC[Z`). [alt] = Meta: для печатных символов и C0-байтов (Ctrl/Backspace/Enter)
 * добавляется префикс ESC (readline word-ops, Alt+Backspace = удалить слово).
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
    // Навигация и F-клавиши идут ПЕРВЫМИ: при Ctrl/Alt/Shift они кодируют модификатор внутри CSI
    // (ESC[1;<mod>x), поэтому Ctrl+стрелка не должна провалиться в ctrl-блок ниже (там она дала бы null).
    navKeySequence(key, applicationCursor, shift, alt, ctrl)?.let { return it }
    if (ctrl) {
        // Ctrl+клавиша → C0-байт. Определяем по ФИЗИЧЕСКОЙ клавише, а не по codePoint: на desktop
        // AWT отдаёт Ctrl+C сразу как готовый control-байт (keyChar 0x03), а раскладочные/одинокие
        // комбо — как CHAR_UNDEFINED, поэтому опора на codePoint ломала Ctrl+букву вживую.
        // Alt добавляет meta-префикс ESC.
        val ctrlByte = controlByte(key, codePoint) ?: return null
        return meta(alt, ctrlByte.toChar().toString())
    }
    // C0-байтовые клавиши редактирования — honor Alt=Meta (Alt+Backspace = удалить слово).
    when (key) {
        Key.Enter, Key.NumPadEnter -> return meta(alt, "\r")
        Key.Backspace -> return meta(alt, DEL)
        Key.Escape -> return meta(alt, ESC)
        // Shift+Tab — back-tab (многобайтный CSI, без meta); иначе одиночный HT, honor Alt=Meta.
        Key.Tab -> return if (shift) "$ESC[Z" else meta(alt, "\t")
    }
    val ch = printableChar(key, codePoint, shift) ?: return null
    return meta(alt, ch.toString())
}

/** Meta-обёртка: при зажатом Alt добавляет префикс ESC (xterm metaSendsEscape). */
private fun meta(alt: Boolean, seq: String): String = if (alt) ESC + seq else seq

/**
 * Управляющий C0-байт для Ctrl+клавиша или `null`, если комбинация не управляющая.
 * Сначала по физической клавише (надёжно при любом keyChar от AWT: Ctrl+C приходит как 0x03,
 * иногда как CHAR_UNDEFINED), затем фолбэк на codePoint — если AWT уже отдал готовый C0-байт
 * (1..26) или, для совместимости с юнит-вызовами, букву.
 */
private fun controlByte(key: Key, codePoint: Int): Int? {
    letterIndex(key)?.let { return it + 1 } // Ctrl+A..Z → 0x01..0x1A
    return when (key) {
        Key.LeftBracket -> 0x1b   // Ctrl+[ = ESC
        Key.Backslash -> 0x1c     // Ctrl+\ = FS
        Key.RightBracket -> 0x1d  // Ctrl+] = GS
        Key.Spacebar -> 0x00      // Ctrl+Space = NUL
        else -> when (codePoint) {
            in 1..26 -> codePoint
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            else -> null
        }
    }
}

/**
 * Печатный символ нажатия или `null`. [codePoint] используется, только если это реальный символ
 * (не 0, не [CHAR_UNDEFINED], не ISO-control). Когда AWT символ не отдал — типично для Alt+буква на
 * Linux и одиноких модификаторов (keyChar == CHAR_UNDEFINED) — берём букву с физической клавиши,
 * чтобы Alt=Meta работал, а одинокий Alt НЕ слал мусорный глиф.
 */
private fun printableChar(key: Key, codePoint: Int, shift: Boolean): Char? {
    if (codePoint != 0 && codePoint != CHAR_UNDEFINED) {
        val ch = codePoint.toChar()
        if (!ch.isISOControl()) return ch
    }
    return letterIndex(key)?.let { idx ->
        val c = 'a' + idx
        if (shift) c.uppercaseChar() else c
    }
}

/** Индекс буквенной клавиши A..Z → 0..25, или `null` для не-буквы. */
private fun letterIndex(key: Key): Int? = when (key) {
    Key.A -> 0; Key.B -> 1; Key.C -> 2; Key.D -> 3; Key.E -> 4; Key.F -> 5; Key.G -> 6
    Key.H -> 7; Key.I -> 8; Key.J -> 9; Key.K -> 10; Key.L -> 11; Key.M -> 12; Key.N -> 13
    Key.O -> 14; Key.P -> 15; Key.Q -> 16; Key.R -> 17; Key.S -> 18; Key.T -> 19; Key.U -> 20
    Key.V -> 21; Key.W -> 22; Key.X -> 23; Key.Y -> 24; Key.Z -> 25
    else -> null
}

/**
 * xterm-последовательность навигационной/функциональной клавиши или `null`, если [key] не из этого
 * набора.
 *
 * Без модификаторов: стрелки и Home/End учитывают DECCKM ([applicationCursor]) — в application-режиме
 * вводный код SS3 (`ESC O`), иначе CSI (`ESC[`); Page/Insert/Delete как CSI `ESC[<n>~`, F1–F4 как SS3
 * `ESC O P..S`, F5–F12 как CSI `ESC[<n>~`.
 *
 * С зажатым [shift]/[alt]/[ctrl] клавиша кодируется в modifyOtherKeys-форме: «буквенные» (стрелки,
 * Home/End, F1–F4) → `ESC[1;<mod><letter>` (всегда CSI, SS3/DECCKM игнорируются — SS3 не несёт
 * параметр), «tilde» (Page/Insert/Delete, F5–F12) → `ESC[<n>;<mod>~`. `mod = 1 + Shift + Alt·2 + Ctrl·4`.
 */
private fun navKeySequence(key: Key, applicationCursor: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean): String? {
    val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    // «Буквенные» клавиши: финальный байт + (для стрелок/Home/End) учёт DECCKM в немодифицированном виде.
    val letter: Char? = when (key) {
        Key.DirectionUp -> 'A'
        Key.DirectionDown -> 'B'
        Key.DirectionRight -> 'C'
        Key.DirectionLeft -> 'D'
        Key.MoveHome -> 'H'
        Key.MoveEnd -> 'F'
        Key.F1 -> 'P'
        Key.F2 -> 'Q'
        Key.F3 -> 'R'
        Key.F4 -> 'S'
        else -> null
    }
    if (letter != null) {
        if (mod != 1) return "$ESC[1;$mod$letter"
        // F1–F4 без модификатора — SS3 независимо от DECCKM; стрелки/Home/End — SS3 только в application.
        val ss3 = key == Key.F1 || key == Key.F2 || key == Key.F3 || key == Key.F4 || applicationCursor
        return if (ss3) "${ESC}O$letter" else "$ESC[$letter"
    }
    // «Tilde»-клавиши: CSI <n> [; <mod>] ~.
    val num: Int = when (key) {
        Key.Insert -> 2
        Key.Delete -> 3
        Key.PageUp -> 5
        Key.PageDown -> 6
        Key.F5 -> 15
        Key.F6 -> 17
        Key.F7 -> 18
        Key.F8 -> 19
        Key.F9 -> 20
        Key.F10 -> 21
        Key.F11 -> 23
        Key.F12 -> 24
        else -> return null
    }
    return if (mod != 1) "$ESC[$num;$mod~" else "$ESC[$num~"
}
