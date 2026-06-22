package app.skerry.ui.terminal

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Desktop-реализация: AWT `systemSelection` — это PRIMARY-буфер X11. На Wayland он отсутствует
 * (`getSystemSelection()`==null), поэтому там читаем настоящий PRIMARY через `wl-paste --primary`
 * ([WaylandClipboard]) — так средний клик видит и выделение из других окон. Если и этого нет
 * (Windows/macOS/headless/нет wl-clipboard) → null, вставка откатится на in-app буфер/CLIPBOARD.
 * Любые сбои доступа гасим в null.
 */
internal actual fun readPrimarySelectionText(): String? = try {
    val selection = Toolkit.getDefaultToolkit().systemSelection
    if (selection != null) selection.getData(DataFlavor.stringFlavor) as? String
    else if (WaylandClipboard.available) WaylandClipboard.paste(primary = true)
    else null
} catch (_: Exception) {
    null
}

/**
 * Публикуем выделение в PRIMARY: X11 — через AWT `systemSelection`; Wayland — через `wl-copy --primary`,
 * чтобы выделение в терминале стало системным PRIMARY (его увидят другие окна). Без обоих путей —
 * тихий no-op (работает in-app фолбэк-буфер). Сбои гасим, чтобы выделение не падало из-за буфера.
 */
internal actual fun writePrimarySelectionText(text: String) {
    try {
        val selection = Toolkit.getDefaultToolkit().systemSelection
        if (selection != null) selection.setContents(StringSelection(text), null)
        else if (WaylandClipboard.available) WaylandClipboard.copy(text, primary = true)
    } catch (_: Exception) {
        // PRIMARY недоступен/занят — игнорируем, in-app буфер всё равно сохранил выделение.
    }
}
