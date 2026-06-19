package app.skerry.ui.terminal

/**
 * Якорь скрытого IME-поля. Софт-клавиатура (Android/iOS) не шлёт key-события в [mapTerminalKey],
 * поэтому ввод снимается через невидимый `BasicTextField`, который после каждого изменения
 * сбрасывается к этому значению. Якорь непустой, чтобы Backspace на пустом терминале порождал
 * изменение (удаление якоря) — иначе IME нечего удалять и onValueChange не срабатывает.
 * Zero-width space (U+200B) невидим, не печатается shell и не ломает выравнивание.
 * Задан числом, а не литералом, чтобы не быть невидимым в исходнике (Read/grep).
 */
val ANCHOR: String = Char(0x200b).toString()

/** DEL — Backspace для shell; CR — Enter. Заданы числами, чтобы не быть невидимыми в Read/grep. */
private val DEL: Char = Char(0x7f)
private val CR: Char = Char(0x0d)

/**
 * Превращает изменение значения скрытого IME-поля в байты для PTY. Поле всегда сбрасывается к
 * [anchor], поэтому [value] = якорь ± правки пользователя. Диффим по общему префиксу:
 *  - всё, что удалено относительно якоря (хвост короче) → по [DEL] за символ (Backspace);
 *  - всё, что добавлено после общего префикса → уходит как есть, перевод строки `\n` → [CR] (Enter).
 *
 * Чистая и тестируемая; UI лишь скармливает (anchor, value) и возвращает поле к [anchor].
 */
fun imeDeltaToPty(anchor: String, value: String): String {
    var common = 0
    val max = minOf(anchor.length, value.length)
    while (common < max && anchor[common] == value[common]) common++

    val deletions = anchor.length - common
    val added = value.substring(common)

    return buildString {
        repeat(deletions) { append(DEL) }
        for (ch in added) append(if (ch == '\n') CR else ch)
    }
}
