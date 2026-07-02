package app.skerry.ui.terminal

/** Клавиша-стрелка клавишной панели терминала; [finalByte] — финальный символ её escape-кода. */
enum class ArrowKey(val finalByte: Char) { Up('A'), Down('B'), Right('C'), Left('D') }

/** ESC (0x1B) — задан кодом, чтобы не быть невидимым управляющим байтом в исходнике (Read/grep). */
private val ESC: String = 27.toChar().toString()

/**
 * Escape-последовательность клавиши-стрелки для PTY с учётом DECCKM (application-cursor-keys,
 * [app.skerry.ui.terminal.TerminalScreenState.applicationCursorKeys]). В нормальном режиме —
 * CSI (`ESC[A`); когда полноэкранная программа (vim/less) включила application-режим через `ESC[?1h` —
 * SS3 (`ESC O A`). Чистая и тестируемая: панель читает текущий режим сессии и зовёт эту функцию.
 */
fun arrowSequence(key: ArrowKey, applicationCursor: Boolean): String =
    (if (applicationCursor) ESC + "O" else ESC + "[") + key.finalByte
