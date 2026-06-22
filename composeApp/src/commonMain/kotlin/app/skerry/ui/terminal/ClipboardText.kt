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
