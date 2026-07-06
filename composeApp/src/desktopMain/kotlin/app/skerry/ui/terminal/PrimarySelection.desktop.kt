package app.skerry.ui.terminal

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * AWT `systemSelection` is the X11 PRIMARY buffer. On Wayland it is absent
 * (`getSystemSelection()` == null), so PRIMARY is read via `wl-paste --primary`
 * ([WaylandClipboard]) instead, letting middle-click see selections from other windows. If
 * neither is available (Windows/macOS/headless/no wl-clipboard), returns null and paste falls
 * back to the in-app buffer/CLIPBOARD. Any access failure is swallowed to null.
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
 * Publishes the selection to PRIMARY: X11 via AWT `systemSelection`, Wayland via
 * `wl-copy --primary`, so a terminal selection becomes the system PRIMARY visible to other
 * windows. If neither path is available, a silent no-op (the in-app fallback buffer still works).
 * Failures are swallowed so selection doesn't break because of the clipboard.
 */
internal actual fun writePrimarySelectionText(text: String) {
    try {
        val selection = Toolkit.getDefaultToolkit().systemSelection
        if (selection != null) selection.setContents(StringSelection(text), null)
        else if (WaylandClipboard.available) WaylandClipboard.copy(text, primary = true)
    } catch (_: Exception) {
        // PRIMARY unavailable/busy: ignore, the in-app buffer still holds the selection.
    }
}
