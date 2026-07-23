package app.skerry.ui.terminal

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Platform wrappers for CLIPBOARD <-> plain text over the Compose suspend clipboard
 * ([androidx.compose.ui.platform.Clipboard]), replacing the deprecated `ClipboardManager.getText/setText`.
 *
 * Builds a [ClipEntry] from plain text for writing to the system clipboard (selection copy, OSC 52).
 */
internal expect fun plainTextClipEntry(text: String): ClipEntry

/**
 * Extracts plain text from a system-clipboard [ClipEntry] (for paste), or `null` if there is none.
 * On desktop, only AWT's `stringFlavor` is requested (no scanning other formats); on Android, the
 * text of the first ClipData item is used.
 */
internal expect fun ClipEntry.readPlainText(): String?

/**
 * Direct CLIPBOARD read path bypassing Compose/AWT, needed on Wayland: reading via AWT with a foreign
 * serialized flavor (per IntelliJ) unconditionally prints a JDK stack trace to System.err. Reads via
 * `wl-paste` instead, touching no AWT, so nothing is logged. Returns `null` when no direct path exists
 * (X11/Windows/macOS/Android), so the caller falls back to the regular Compose clipboard.
 */
internal expect fun readSystemClipboardDirect(): String?

/**
 * Direct CLIPBOARD write path bypassing Compose/AWT (Wayland, `wl-copy`), paired with
 * [readSystemClipboardDirect] so Wayland reads and writes go through the same buffer (`wl-clipboard`)
 * instead of mixing with XWayland-AWT. Returns `true` if written via the direct path; `false` means no
 * direct path exists and the caller should write via the Compose clipboard.
 */
internal expect fun writeSystemClipboardDirect(text: String): Boolean

/**
 * Whether the direct path ([readSystemClipboardDirect]) owns CLIPBOARD reads entirely. When `true`
 * (Wayland with `wl-clipboard`), the caller must not fall back to Compose/AWT even on an empty result,
 * or a non-text clipboard would trigger the noisy JDK trace again (AWT `getContents`). The first call
 * may block (resolving utilities) — call off the UI thread.
 */
internal expect fun systemClipboardDirectHandlesReads(): Boolean

/**
 * System CLIPBOARD text (terminal paste, `${{clipboard}}` snippet variable). On Wayland the direct
 * path takes over reading (wl-paste, bypassing AWT) and never falls back to Compose even on an
 * empty result — a non-text clipboard would raise a noisy JDK trace. The subprocess and utility
 * resolution stay off the UI thread (Default); `getClipEntry` (suspend, waits on the UI thread)
 * runs on the caller context.
 */
internal suspend fun fetchSystemClipboardText(clipboard: Clipboard): String? =
    withContext(Dispatchers.Default) {
        if (systemClipboardDirectHandlesReads()) readSystemClipboardDirect() else null
    } ?: if (systemClipboardDirectHandlesReads()) null else clipboard.getClipEntry()?.readPlainText()
