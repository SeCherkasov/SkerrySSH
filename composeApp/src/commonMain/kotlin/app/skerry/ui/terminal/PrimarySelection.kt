package app.skerry.ui.terminal

/**
 * Текст из PRIMARY-выделения (X11/Wayland): по конвенции средний клик в терминале вставляет именно
 * его — то, что выделено мышью в любом окне, — а не системный буфер обмена (CLIPBOARD). Платформы
 * без primary-selection (Android, Windows, macOS) возвращают `null`; вызывающий тогда откатывается
 * на обычный буфер обмена.
 */
internal expect fun readPrimarySelectionText(): String?

/**
 * Записать текст в PRIMARY-выделение (X11): по конвенции выделение мышью в терминале сразу становится
 * PRIMARY, чтобы средний клик в этом и других окнах вставлял именно его. На платформах без PRIMARY
 * (Wayland/Android/Windows/macOS) — no-op; вызывающий держит собственный in-app фолбэк-буфер.
 */
internal expect fun writePrimarySelectionText(text: String)
