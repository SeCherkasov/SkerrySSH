package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.wrapsToNextRow

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

/**
 * Cheap allocation-free scan for a `://` scheme separator across rows [rows] of [screen] — the
 * two-character lookbehind carries over row boundaries, because auto-wrap can cut the separator
 * itself in half (a row ending in `https:` continued by `//host/…`).
 */
private fun hasSchemeMarker(screen: List<List<TermCell>>, rows: IntRange): Boolean {
    val scan = SchemeMarkerScan()
    for (r in rows) if (scan.accept(screen[r])) return true
    return false
}

/** Two-character lookbehind for `://`, kept alive across rows and cells by [hasSchemeMarker]. */
private class SchemeMarkerScan {
    private var p2 = ' '
    private var p1 = ' '

    /** Feeds one row; true as soon as the separator completes (possibly from the previous row). */
    fun accept(row: List<TermCell>): Boolean {
        for (cell in row) for (ch in cell.text) {
            if (p2 == ':' && p1 == '/' && ch == '/') return true
            p2 = p1
            p1 = ch
        }
        return false
    }
}

/**
 * Rows joined at once out of a soft-wrapped logical line. Bounds the text work: a logical line can be
 * thousands of rows (a one-line JSON dump), and joining it whole would cost O(line) per frame. At any
 * sane terminal width a block still holds a URL several times over.
 */
private const val WRAP_BLOCK_ROWS = 9

/**
 * The block of a soft-wrap chain that row [r] belongs to. Blocks are counted from the line's real
 * start, so every row of a block answers with the same rows — the draw pass (which asks per visible
 * window) and the pointer (which asks per row) must not disagree about the same cell.
 *
 * [startClipped]/[endClipped] mean the block ended mid-line: the text beyond that edge is not in the
 * join, so a match touching it may be a fragment of a longer one.
 */
private class WrapChain(val rows: IntRange, val startClipped: Boolean, val endClipped: Boolean)

private fun wrapChain(screen: List<List<TermCell>>, r: Int, lineStart: Int): WrapChain {
    val first = lineStart + (r - lineStart) / WRAP_BLOCK_ROWS * WRAP_BLOCK_ROWS
    var last = first
    while (last < screen.lastIndex && last - first < WRAP_BLOCK_ROWS - 1 && screen[last].wrapsToNextRow()) last++
    val endClipped = last < screen.lastIndex && screen[last].wrapsToNextRow()
    return WrapChain(first..last, startClipped = first > lineStart, endClipped = endClipped)
}

/**
 * First row of the logical line row [r] belongs to. Reads wrap flags only (O(1) per row, no text), so
 * a very long line costs a pointer chase here rather than the joining that the block size bounds —
 * and callers walking several blocks of one line reuse the answer instead of re-walking per block.
 */
private fun lineStartOf(screen: List<List<TermCell>>, r: Int): Int {
    var start = r
    while (start > 0 && screen[start - 1].wrapsToNextRow()) start--
    return start
}

// Test-only counter, incremented by TerminalScreen's composition cache (NOT here - hover/click
// hit-testing also routes through linkSpansByRow and must not count). Single-threaded: written
// from composition or the sequential test JVM; revisit before enabling parallel test execution.
internal var linkScanPasses = 0

/**
 * URL spans per grid row for rows [window] of [screen], in column coordinates. A row that auto-wrap
 * cut out of a longer logical line is detected together with its neighbours, so a URL split at the
 * right margin is one link: underlined on every row it crosses, and opening the whole URL wherever it
 * is clicked. Rows with no URL are absent from the map.
 *
 * Each chain is flattened and matched once, not once per row it covers — the draw pass asks for the
 * whole visible window, and re-joining the same chain for every one of its rows was the cost this
 * grouping exists to avoid.
 *
 * A match touching either edge of a clipped block (see [WrapChain]) is dropped rather than reported:
 * past the edge the text is unknown, and a click must never open a truncated address. On a line
 * longer than [WRAP_BLOCK_ROWS] rows that also costs the rare URL that merely starts or ends flush
 * with a block edge without crossing it.
 */
internal fun linkSpansByRow(screen: List<List<TermCell>>, window: IntRange): Map<Int, List<TextLinkSpan>> {
    var out: MutableMap<Int, List<TextLinkSpan>>? = null
    var r = window.first.coerceAtLeast(0)
    val last = window.last.coerceAtMost(screen.lastIndex)
    // Carried across blocks: a clipped end means the same logical line continues into the next block,
    // so its start is already known and the walk back is not repeated.
    var lineStart = -1
    while (r <= last) {
        if (lineStart < 0) lineStart = lineStartOf(screen, r)
        val chain = wrapChain(screen, r, lineStart)
        for ((row, spans) in chainLinkSpans(screen, chain, r..minOf(chain.rows.last, last))) {
            (out ?: HashMap<Int, List<TextLinkSpan>>().also { out = it })[row] = spans
        }
        if (!chain.endClipped) lineStart = -1
        r = chain.rows.last + 1
    }
    return out ?: emptyMap()
}

/** Spans of one [chain], reported for rows [wanted] only (the rest of the chain is off-window). */
private fun chainLinkSpans(
    screen: List<List<TermCell>>,
    chain: WrapChain,
    wanted: IntRange,
): Map<Int, List<TextLinkSpan>> {
    if (!hasSchemeMarker(screen, chain.rows)) return emptyMap()
    val flat = rowsText(screen, chain.rows) ?: return emptyMap()
    val found = detectPlainTextLinks(flat.text).filterNot { span ->
        (chain.startClipped && span.start == 0) || (chain.endClipped && span.endExclusive == flat.text.length)
    }
    if (found.isEmpty()) return emptyMap()
    var out: MutableMap<Int, List<TextLinkSpan>>? = null
    for (row in wanted) {
        val spans = flat.spansOn(row, found)
        if (spans.isNotEmpty()) (out ?: HashMap<Int, List<TextLinkSpan>>().also { out = it })[row] = spans
    }
    return out ?: emptyMap()
}

/** URL spans on row [r] alone — hit-testing a single pointer position, not the draw pass. */
internal fun rowLinkSpans(screen: List<List<TermCell>>, r: Int): List<TextLinkSpan> =
    linkSpansByRow(screen, r..r)[r] ?: emptyList()

/** The plain-text URL under row [r], column [col] of [screen], or `null`. Ctrl+click hit-testing. */
internal fun linkAt(screen: List<List<TermCell>>, r: Int, col: Int): String? =
    rowLinkSpans(screen, r).firstOrNull { col >= it.start && col < it.endExclusive }?.uri
