package app.skerry.ui.terminal

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Desktop-реализация: AWT `systemSelection` — это PRIMARY-буфер X11 (на Wayland/Windows/macOS он
 * отсутствует и `getSystemSelection()` вернёт null → отдаём null). Любые сбои доступа к буферу
 * (нет flavor, IO, headless) гасим в null, чтобы вставка просто откатилась на CLIPBOARD.
 */
internal actual fun readPrimarySelectionText(): String? = try {
    Toolkit.getDefaultToolkit().systemSelection?.getData(DataFlavor.stringFlavor) as? String
} catch (_: Exception) {
    null
}

/**
 * Публикуем выделение в PRIMARY (X11). На Wayland/Windows/macOS `systemSelection`==null → тихий no-op
 * (там работает in-app фолбэк-буфер). Сбои доступа гасим, чтобы выделение не падало из-за буфера.
 */
internal actual fun writePrimarySelectionText(text: String) {
    try {
        Toolkit.getDefaultToolkit().systemSelection?.setContents(StringSelection(text), null)
    } catch (_: Exception) {
        // PRIMARY недоступен/занят — игнорируем, in-app буфер всё равно сохранил выделение.
    }
}
