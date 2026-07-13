package app.skerry.ui.vault

/**
 * Copies a password to the system clipboard with minimal leakage. On Android the clip is marked
 * sensitive (API 33+: `ClipDescription.EXTRA_IS_SENSITIVE` hides content from clipboard history and
 * preview notifications) and auto-cleared after [CLIPBOARD_CLEAR_SECONDS] if it still holds our
 * password. Public material (public key, certificate) is not sensitive and uses plain
 * `LocalClipboardManager` instead.
 *
 * Desktop mirrors this best-effort: the clip is auto-cleared after [CLIPBOARD_CLEAR_SECONDS] if it
 * still holds our password, and carries a KDE password-manager hint so Klipper keeps it out of
 * clipboard history.
 */
expect fun copyPasswordToClipboard(password: String)

/**
 * Copies non-secret text (public key, certificate, fingerprint) to the system clipboard, without
 * the sensitive flag or auto-clear.
 */
expect fun copyTextToClipboard(text: String)

/** Seconds after Copy Password before the clipboard is auto-cleared (Android). */
const val CLIPBOARD_CLEAR_SECONDS: Int = 30
