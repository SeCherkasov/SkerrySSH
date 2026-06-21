package app.skerry.ui.terminal

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

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
