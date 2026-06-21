package app.skerry.ui.terminal

/**
 * Текст из PRIMARY-выделения (X11/Wayland): по конвенции средний клик в терминале вставляет именно
 * его — то, что выделено мышью в любом окне, — а не системный буфер обмена (CLIPBOARD). Платформы
 * без primary-selection (Android, Windows, macOS) возвращают `null`; вызывающий тогда откатывается
 * на обычный буфер обмена.
 */
internal expect fun readPrimarySelectionText(): String?
