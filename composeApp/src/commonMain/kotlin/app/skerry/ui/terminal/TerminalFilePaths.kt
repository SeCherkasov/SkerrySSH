package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalSelection

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
internal const val MAX_PATH_LENGTH = 4096

/** Characters a path may directly follow: a token boundary, a quote, or an opening bracket. */
private const val PATH_OPENERS = "\"'`([{"

/** Trailing chars that read as sentence punctuation rather than part of the name. */
private const val PATH_TRAILING_PUNCT = ".,;:!?]}>\"'`"

/**
 * Invisible characters a hostile server could hide in a path so the glyphs under the pointer read as
 * one directory while the string handed to SFTP is another: format characters (bidi overrides and
 * isolates, zero-width marks, the soft hyphen), C1 controls, and the marks that render as nothing on
 * their own (variation selectors, Mongolian free variation selectors, the grapheme joiner). A real
 * path never needs them, so anything carrying one is not offered.
 */
private fun isInvisibleChar(ch: Char): Boolean =
    ch.category == CharCategory.FORMAT || ch in '\u0080'..'\u009F' ||
        ch in '\uFE00'..'\uFE0F' || ch in '\u180B'..'\u180F' || ch == '\u034F'

/**
 * Whether [s] carries a control byte or an invisible character, walked by code point: a format
 * character outside the BMP (Unicode tags, U+E0001 and U+E0020..E007F) arrives as a surrogate
 * pair, which [Char.category] reports as two surrogates.
 */
private fun hasHiddenChar(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            val cp = 0x10000 + ((ch.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
            if (isInvisibleSupplementary(cp)) return true
            i += 2
            continue
        }
        if (ch.code < 0x20 || ch.code == 0x7F || isInvisibleChar(ch)) return true
        i++
    }
    return false
}

/** The format characters outside the BMP, plus the variation selectors beside the tags: all render as nothing. */
private fun isInvisibleSupplementary(cp: Int): Boolean =
    cp in 0x13430..0x1343F || cp in 0x1BCA0..0x1BCA3 || cp in 0x1D173..0x1D17A || cp in 0xE0000..0xE0FFF

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
    if (hasHiddenChar(path)) return null
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
    // A span touching a hyperlink-owned cell is dropped (the hyperlink owns it); one touching a
    // concealed cell (SGR 8) is dropped whole too - a path with a hidden segment would hand SFTP
    // a location the user never saw, clicks on its visible prefix included.
    return rowTextSpans(row, ::detectFilePaths).filterNot { span ->
        (span.start until span.endExclusive).any {
            row.getOrNull(it)?.let { cell -> cell.hyperlink != null || cell.style.hidden } == true
        }
    }
}

/** The file-path span under column [col] in [row], or `null`. Used for Ctrl+click hit-testing. */
internal fun filePathSpanAt(row: List<TermCell>, col: Int): TextLinkSpan? {
    // A concealed cell (SGR 8) is not a click target: the user cannot read what would open.
    if (row.getOrNull(col)?.style?.hidden == true) return null
    return fileHyperlinkSpanAt(row, col)
        ?: rowFilePathSpans(row).firstOrNull { col >= it.start && col < it.endExclusive }
}

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

private const val FILE_SCHEME = "file://"

/**
 * The path a `file://` OSC 8 link names (`ls --hyperlink` writes `file://<hostname>/<path>`), or
 * `null`. The host is not compared: it is the server's own hostname, which rarely matches the address
 * the session dialled, and the path only ever reaches this session's file panel, which lists it -
 * nothing is opened locally. The decoded path passes the same checks as a detected one.
 */
internal fun fileUriPath(uri: String): String? {
    if (!uri.startsWith(FILE_SCHEME, ignoreCase = true)) return null
    val slash = uri.indexOf('/', FILE_SCHEME.length)
    if (slash < 0) return null
    val raw = uri.substring(slash)
    // `?` and `#` would start a query or fragment; `ls` escapes them in names, so a raw one is not a path.
    if (raw.any { it == '?' || it == '#' }) return null
    val path = percentDecodeUtf8(raw) ?: return null
    if (path.length > MAX_PATH_LENGTH) return null
    if (hasHiddenChar(path)) return null
    return path
}

/** `%XX` escapes decoded as UTF-8; `null` on a broken escape or on bytes that are not UTF-8. */
private fun percentDecodeUtf8(s: String): String? {
    if ('%' !in s) return s
    val bytes = ByteArray(s.length * 3)
    var n = 0
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '%') {
            if (i + 2 >= s.length) return null
            val hi = s[i + 1].digitToIntOrNull(16) ?: return null
            val lo = s[i + 2].digitToIntOrNull(16) ?: return null
            bytes[n++] = (hi * 16 + lo).toByte()
            i += 3
        } else {
            // A literal run is encoded whole: one Char at a time would split a surrogate pair.
            var runEnd = s.indexOf('%', i)
            if (runEnd < 0) runEnd = s.length
            for (b in s.substring(i, runEnd).encodeToByteArray()) bytes[n++] = b
            i = runEnd
        }
    }
    return try {
        bytes.decodeToString(0, n, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * The span of the `file://` hyperlink under column [col] - the run of cells carrying the same URI -
 * with the path it names, or `null`. A concealed cell is not a target, as with detected paths.
 */
private fun fileHyperlinkSpanAt(row: List<TermCell>, col: Int): TextLinkSpan? {
    val cell = row.getOrNull(col) ?: return null
    val uri = cell.hyperlink ?: return null
    val path = fileUriPath(uri) ?: return null
    var start = col
    while (start > 0 && row[start - 1].hyperlink == uri) start--
    var end = col + 1
    while (end < row.size && row[end].hyperlink == uri) end++
    return TextLinkSpan(start, end, path)
}

/**
 * The path a touch selection stands for when it lies inside one `file://` hyperlink - long-press on
 * a name in `ls --hyperlink` output selects the name, while the link carries the full path.
 */
internal fun fileLinkPathOfSelection(screen: List<List<TermCell>>, selection: TerminalSelection): String? {
    if (selection.isEmpty) return null
    val start = selection.start
    val end = selection.end
    if (start.row != end.row) return null
    val row = screen.getOrNull(start.row) ?: return null
    val span = fileHyperlinkSpanAt(row, start.col) ?: return null
    if (end.col > span.endExclusive) return null
    if ((start.col until end.col).any { row[it].style.hidden }) return null
    return span.uri
}
