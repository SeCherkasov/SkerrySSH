package app.skerry.ui.terminal

import androidx.compose.ui.platform.ClipEntry

/**
 * Платформенные обёртки CLIPBOARD ↔ простой текст поверх нового suspend-буфера Compose
 * ([androidx.compose.ui.platform.Clipboard]). Заменяют устаревший `ClipboardManager.getText/setText`.
 *
 * Создать [ClipEntry] из простого текста для записи в системный буфер (копирование выделения, OSC 52).
 */
internal expect fun plainTextClipEntry(text: String): ClipEntry

/**
 * Извлечь простой текст из [ClipEntry] системного буфера (для вставки) или `null`, если текста нет.
 * На desktop запрашиваем у AWT только `stringFlavor` (не перебирая прочие форматы), на Android берём
 * текст первого элемента ClipData.
 */
internal expect fun ClipEntry.readPlainText(): String?

/**
 * Прямой путь чтения CLIPBOARD в обход Compose/AWT — нужен на Wayland: чтение буфера через AWT при
 * чужом сериализованном flavor'е (скопировано из IntelliJ) безусловно печатает стек JDK в System.err.
 * Здесь читаем через `wl-paste`, AWT не трогаем — лога нет. Возвращает `null`, когда прямого пути нет
 * (X11/Windows/macOS/Android) — вызывающий тогда читает штатным Compose-буфером.
 */
internal expect fun readSystemClipboardDirect(): String?

/**
 * Прямой путь записи CLIPBOARD в обход Compose/AWT (Wayland, `wl-copy`). Парный к [readSystemClipboardDirect]:
 * чтобы на Wayland чтение и запись шли через один буфер (`wl-clipboard`), а не смешивались с XWayland-AWT.
 * `true` — записали прямым путём; `false` — прямого пути нет, вызывающий пишет через Compose-буфер.
 */
internal expect fun writeSystemClipboardDirect(text: String): Boolean

/**
 * Берёт ли прямой путь ([readSystemClipboardDirect]) чтение CLIPBOARD на себя ЦЕЛИКОМ. Когда `true`
 * (Wayland с `wl-clipboard`), вызывающий НЕ должен откатываться на Compose/AWT даже при пустом
 * результате — иначе при не-текстовом буфере снова всплывёт шумная JDK-трасса (AWT `getContents`).
 * Первый вызов может быть блокирующим (резолв утилит) — звать вне UI-потока.
 */
internal expect fun systemClipboardDirectHandlesReads(): Boolean
