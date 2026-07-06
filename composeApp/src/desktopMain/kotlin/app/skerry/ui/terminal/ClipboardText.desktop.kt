package app.skerry.ui.terminal

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** Wraps text in AWT [StringSelection] (the native [ClipEntry] carrier on desktop). */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))

/**
 * Reads exactly `stringFlavor` from the AWT [java.awt.datatransfer.Transferable], without trying
 * other formats. Any failure (unsupported format, IO, clipboard unavailable) is swallowed to
 * `null` so paste silently falls back. (The JDK itself may print a diagnostic stack trace for
 * foreign serialized flavors inside `getContents`/format enumeration; it is harmless and unrelated
 * to this flavor choice.)
 */
@OptIn(ExperimentalComposeUiApi::class)
internal actual fun ClipEntry.readPlainText(): String? = try {
    asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
} catch (_: Exception) {
    null
}

/** On Wayland, reads CLIPBOARD via `wl-paste` (bypassing AWT, no noisy JDK trace); otherwise null. */
internal actual fun readSystemClipboardDirect(): String? =
    if (WaylandClipboard.available) WaylandClipboard.paste(primary = false) else null

/** On Wayland, writes CLIPBOARD via `wl-copy` (paired with the read side); otherwise false, falling back to the regular Compose clipboard. */
internal actual fun writeSystemClipboardDirect(text: String): Boolean =
    WaylandClipboard.available && WaylandClipboard.copy(text, primary = false)

/** On Wayland, the direct path reads the whole clipboard (no AWT fallback, no JDK trace). */
internal actual fun systemClipboardDirectHandlesReads(): Boolean = WaylandClipboard.available
