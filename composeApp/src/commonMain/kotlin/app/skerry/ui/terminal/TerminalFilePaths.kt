package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell

/**
 * Detection of file paths printed in terminal output, so Ctrl+click (touch: the selection chip) can
 * reveal them in the SFTP panel. Separate from [TerminalLinks] because the rules differ: a path has
 * no scheme to anchor on, so the detector is deliberately narrow.
 *
 * v1 recognizes only paths that resolve without knowing the shell's working directory — absolute
 * (`/var/log/syslog`) and home-relative (`~/.ssh/config`). Relative ones (`./x`, `src/App.kt`) are
 * skipped: there is no cwd tracking (no OSC 7), so they would open the wrong directory as often as
 * the right one.
 */

/** Longest path we are willing to hand to the file browser — beyond this it isn't output, it's noise. */
private const val MAX_PATH_LENGTH = 4096

/** Characters a path may directly follow: a token boundary, a quote, or an opening bracket. */
private const val PATH_OPENERS = "\"'`([{"

/** Trailing chars that read as sentence punctuation rather than part of the name. */
private const val PATH_TRAILING_PUNCT = ".,;:!?]}>\"'`"

/**
 * Invisible characters a hostile server could hide in a path so the glyphs under the pointer read as
 * one directory while the string handed to SFTP is another: bidi overrides/isolates and zero-width
 * marks. A real path never needs them, so anything carrying one is not offered.
 */
private fun isInvisibleChar(ch: Char): Boolean =
    ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' || ch in '\u200B'..'\u200F' || ch == '\uFEFF'

/** First character of the first segment: rules out `//` and other slash runs that aren't paths. */
private fun isPathStartChar(ch: Char): Boolean =
    ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-'

/**
 * Cuts a `:line[:col]` suffix (grep -n, compiler diagnostics); the numbers themselves are dropped —
 * the file panel opens files, not lines. The digits must end the token or be followed by another
 * `:` (grep's `file:line:text`), otherwise the colon belongs to the name: `:` is legal in a POSIX
 * filename and shows up in timestamped ones like `2026-07-26T03:00:00.tar.gz`, which must survive
 * whole.
 */
private fun cutLineSuffix(token: String): String {
    var i = 0
    while (i < token.length) {
        if (token[i] != ':' || i + 1 >= token.length || !token[i + 1].isDigit()) { i++; continue }
        var end = i + 1
        while (end < token.length && token[end].isDigit()) end++
        // Optional `:col` right after the line number.
        if (end + 1 < token.length && token[end] == ':' && token[end + 1].isDigit()) {
            end++
            while (end < token.length && token[end].isDigit()) end++
        }
        if (end == token.length || token[end] == ':') return token.substring(0, i)
        // Not a line marker after all — keep looking past it (`/a:2026.log:15` still has a real one).
        i = end
    }
    return token
}

/**
 * Normalizes a whitespace-delimited [token] into a path worth opening, or `null` if it isn't one.
 * The result is always a prefix of [token] (only trailing parts are cut), so callers can map it back
 * onto the source columns by length.
 */
internal fun normalizeFilePath(token: String): String? {
    if (token.length > MAX_PATH_LENGTH) return null
    val path = trimTrailingPunct(cutLineSuffix(token), PATH_TRAILING_PUNCT)
    if (path.isEmpty()) return null
    // Control bytes would corrupt whatever consumes the path downstream; the output is untrusted.
    if (path.any { it.code < 0x20 || it.code == 0x7F || isInvisibleChar(it) }) return null
    return when (path[0]) {
        // `~` (home) or `~/…`. `~user/…` is not supported: only the session's own home is known.
        '~' -> path.takeIf { it.length == 1 || it[1] == '/' }
        '/' -> path.takeIf { it.length > 1 && isPathStartChar(it[1]) }
        else -> null
    }
}

/**
 * Finds file paths in a line of plain text, returned as string-index spans. A path starts only at a
 * token boundary (start of line, whitespace, quote, opening bracket), which keeps URL bodies
 * (`https://host/docs`), shell assignments (`HOST=/dev/null`) and `/etc/passwd`-style colon records
 * out of the results.
 */
internal fun detectFilePaths(text: String): List<TextLinkSpan> {
    var out: MutableList<TextLinkSpan>? = null
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch != '/' && ch != '~') { i++; continue }
        if (i > 0 && !text[i - 1].isWhitespace() && text[i - 1] !in PATH_OPENERS) { i++; continue }
        var end = i
        while (end < text.length && !text[end].isWhitespace()) end++
        normalizeFilePath(text.substring(i, end))?.let { path ->
            (out ?: ArrayList<TextLinkSpan>().also { out = it })
                .add(TextLinkSpan(i, i + path.length, path))
        }
        // Skip the whole token even when it wasn't a path: its inner slashes are not path starts.
        i = end
    }
    return out ?: emptyList()
}

/** Cheap allocation-free scan for a character that could start a path. */
private fun rowHasPathMarker(row: List<TermCell>): Boolean {
    for (cell in row) {
        for (ch in cell.text) if (ch == '/' || ch == '~') return true
    }
    return false
}

/**
 * Same detection as [detectFilePaths] over a grid row, returning spans in **column** coordinates
 * (see [rowTextSpans]). Spans touching a cell that already carries an OSC 8 hyperlink are dropped —
 * the hyperlink owns those cells.
 *
 * Unlike URLs ([linkSpansByRow]), a path is detected within one row only: the affordance is the
 * Ctrl+hover underline, which is painted on the pointed row, so joining a soft-wrap chain here would
 * underline part of a path and open the whole of it. A path cut by a wrap stays unclickable.
 */
internal fun rowFilePathSpans(row: List<TermCell>): List<TextLinkSpan> {
    // Runs per visible row on every Canvas draw — bail out with zero allocation on rows that cannot
    // contain a path before touching StringBuilder/detection.
    if (!rowHasPathMarker(row)) return emptyList()
    return rowTextSpans(row, ::detectFilePaths).filterNot { span ->
        (span.start until span.endExclusive).any { row.getOrNull(it)?.hyperlink != null }
    }
}

/** The file-path span under column [col] in [row], or `null`. Used for Ctrl+click hit-testing. */
internal fun filePathSpanAt(row: List<TermCell>, col: Int): TextLinkSpan? =
    rowFilePathSpans(row).firstOrNull { col >= it.start && col < it.endExclusive }

/**
 * The path a touch selection stands for, or `null`. Only a selection that is exactly one path
 * counts: a phone has no Ctrl+click, so the whole affordance is "long-press a path, tap Open" and
 * guessing which of several tokens was meant would open the wrong thing.
 */
internal fun filePathFromSelection(text: String?): String? {
    val trimmed = text?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
    if (trimmed[0] != '/' && trimmed[0] != '~') return null
    return normalizeFilePath(trimmed)
}
