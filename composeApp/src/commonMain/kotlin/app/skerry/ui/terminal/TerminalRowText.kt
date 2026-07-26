package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell

/**
 * Pieces shared by the two detectors that read clickable things out of terminal output —
 * [TerminalLinks] (URLs) and [TerminalFilePaths] (file paths). They differ in what they match but
 * agree on the mechanics: trim trailing punctuation off a match, and map string-index spans of a
 * grid row onto column coordinates.
 */

/** A match found in plain row text. [start]/[endExclusive] are string indices or grid columns. */
internal data class TextLinkSpan(val start: Int, val endExclusive: Int, val uri: String)

/**
 * Right-trims characters of [punct] off [token], keeping a ')' that closes a '(' inside it (so
 * `…/Foo_(bar)` survives while `(see …/foo)` loses its paren). The result is always a prefix of
 * [token], which is what lets callers map it back onto the source columns by length.
 */
internal fun trimTrailingPunct(token: String, punct: String): String {
    val opens = token.count { it == '(' }
    var closes = token.count { it == ')' }
    var end = token.length
    while (end > 0) {
        val ch = token[end - 1]
        when {
            // A ')' is part of the match only while there's still an unmatched '(' to its left.
            ch == ')' -> if (closes > opens) { closes--; end-- } else break
            ch in punct -> end--
            else -> break
        }
    }
    return if (end == token.length) token else token.substring(0, end)
}

/**
 * Runs [detect] over a grid row's text and returns its spans in **column** coordinates: a wide glyph
 * occupies two columns (its continuation cell has empty text), so string index and column diverge,
 * and clicks would otherwise miss the match. Callers do their own allocation-free "could this row
 * match at all" prescan first — this builds a StringBuilder, and on a draw pass most rows can't
 * match anything.
 */
internal fun rowTextSpans(row: List<TermCell>, detect: (String) -> List<TextLinkSpan>): List<TextLinkSpan> {
    val sb = StringBuilder(row.size)
    val colOf = ArrayList<Int>(row.size)
    for (c in row.indices) {
        for (ch in row[c].text) { sb.append(ch); colOf.add(c) }
    }
    if (colOf.isEmpty()) return emptyList()
    val found = detect(sb.toString())
    if (found.isEmpty()) return emptyList()
    return found.map { s -> TextLinkSpan(colOf[s.start], colOf[s.endExclusive - 1] + 1, s.uri) }
}
