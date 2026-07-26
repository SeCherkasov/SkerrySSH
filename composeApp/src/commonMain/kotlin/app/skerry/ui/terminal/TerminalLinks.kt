package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell

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

// Explicit web schemes only (no bare `www.`), so a match already carries an authority. The body runs
// to the next whitespace; trailing sentence punctuation is trimmed afterwards.
private val PLAIN_LINK_REGEX = Regex("(?i)(?:https?|ftp)://[^\\s]+")

// Trailing chars that are usually sentence punctuation, not part of the URL. ')' is handled by
// [trimTrailingPunct], which keeps a ')' closing a '(' inside the URL (e.g. Wikipedia's `Foo_(bar)`).
private const val LINK_TRAILING_PUNCT = ".,;:!?]}>\"'"

/**
 * Detect bare http(s)/ftp URLs in a line of plain text (URLs the server printed without OSC 8
 * markup — MOTD banners, `curl -I` output). Returns spans as string indices; each URI is validated
 * through [isSafeLinkUri], so degenerate/dangerous forms never surface as clickable.
 */
internal fun detectPlainTextLinks(text: String): List<TextLinkSpan> {
    var out: MutableList<TextLinkSpan>? = null
    for (m in PLAIN_LINK_REGEX.findAll(text)) {
        val uri = trimTrailingPunct(m.value, LINK_TRAILING_PUNCT)
        if (uri.isEmpty() || !isSafeLinkUri(uri)) continue
        (out ?: ArrayList<TextLinkSpan>().also { out = it })
            .add(TextLinkSpan(m.range.first, m.range.first + uri.length, uri))
    }
    return out ?: emptyList()
}

/** Cheap allocation-free scan for a `://` scheme separator anywhere in the row (across cell borders). */
private fun rowHasSchemeMarker(row: List<TermCell>): Boolean {
    var p2 = ' '
    var p1 = ' '
    for (cell in row) {
        for (ch in cell.text) {
            if (p2 == ':' && p1 == '/' && ch == '/') return true
            p2 = p1
            p1 = ch
        }
    }
    return false
}

/**
 * Same detection as [detectPlainTextLinks], but over a grid row, returning spans in **column**
 * coordinates (see [rowTextSpans]).
 */
internal fun rowLinkSpans(row: List<TermCell>): List<TextLinkSpan> {
    // Runs per visible row on every Canvas draw (scroll, cursor blink, PTY output) — so bail out with
    // zero allocation on the overwhelmingly common URL-free row before touching StringBuilder/regex.
    if (!rowHasSchemeMarker(row)) return emptyList()
    return rowTextSpans(row, ::detectPlainTextLinks)
}

/** The plain-text URL under column [col] in [row], or `null`. Used for Ctrl+click hit-testing. */
internal fun linkAt(row: List<TermCell>, col: Int): String? =
    rowLinkSpans(row).firstOrNull { col >= it.start && col < it.endExclusive }?.uri
