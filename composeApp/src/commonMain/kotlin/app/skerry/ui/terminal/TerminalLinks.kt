package app.skerry.ui.terminal

/**
 * Whether an OSC 8 hyperlink is safe to open on Ctrl+click. The URI comes from an untrusted ssh
 * server, so the gate is strict: rejects any control bytes (C0/DEL), and allows only web schemes with
 * authority (`http(s)://`, `ftp://`) or `mailto:` — file:, javascript:, data:, and degenerate forms
 * like `http:` (no `://`) are rejected. Pure function, kept out of the Composable for unit testing.
 */
internal fun isSafeLinkUri(uri: String): Boolean {
    if (uri.any { it.code < 0x20 || it.code == 0x7F }) return false
    return uri.startsWith("https://", ignoreCase = true) ||
        uri.startsWith("http://", ignoreCase = true) ||
        uri.startsWith("ftp://", ignoreCase = true) ||
        uri.startsWith("mailto:", ignoreCase = true)
}
