package app.skerry.ui.vault

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop has no way to mark the system clipboard sensitive and no OS-level clipboard history, so the
 * password is placed directly on the clipboard. No auto-clear on desktop, to match user expectations
 * of stable clipboard contents.
 */
actual fun copyPasswordToClipboard(password: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(password), null)
}

/** Non-secret text uses the same clipboard path as the password (no difference on desktop). */
actual fun copyTextToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}
