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
 * A grid row flattened to plain [text], with [colOf] mapping each string index back to the column it
 * came from. The two diverge because a wide glyph occupies two columns (its continuation cell has
 * empty text) and a cell may hold a combining sequence — without the map, matches would land a
 * column or two off.
 */
internal class RowText(val text: String, private val colOf: IntArray) {

    /** Column the character at string index [index] was drawn in. */
    fun column(index: Int): Int = colOf[index]

    /** Converts a string-index span into the column span `[start, endExclusive)` it covers. */
    fun columns(start: Int, endExclusive: Int): IntRange = colOf[start]..colOf[endExclusive - 1]
}

/**
 * Flattens a grid row for text-level detectors, or `null` when the row holds no characters at all.
 * Callers do their own allocation-free "could this row match at all" prescan first — this builds a
 * StringBuilder, and on a draw pass most rows can't match anything.
 */
internal fun rowText(row: List<TermCell>): RowText? {
    val sb = StringBuilder(row.size)
    // IntArray with a counter rather than ArrayList<Int>: this runs for every visible row of every
    // snapshot, and boxing one Integer per character showed up as steady GC pressure while output
    // streams. A row can hold combining sequences, so the array is grown rather than sized once.
    var colOf = IntArray(row.size)
    var n = 0
    for (c in row.indices) {
        for (ch in row[c].text) {
            if (n == colOf.size) colOf = colOf.copyOf(n * 2 + 1)
            sb.append(ch)
            colOf[n++] = c
        }
    }
    if (n == 0) return null
    return RowText(sb.toString(), if (n == colOf.size) colOf else colOf.copyOf(n))
}

/**
 * Runs [detect] over a grid row's text and returns its spans in **column** coordinates (see
 * [RowText]), so a click lands on the match rather than beside it.
 */
internal fun rowTextSpans(row: List<TermCell>, detect: (String) -> List<TextLinkSpan>): List<TextLinkSpan> {
    val flat = rowText(row) ?: return emptyList()
    val found = detect(flat.text)
    if (found.isEmpty()) return emptyList()
    return found.map { s ->
        val cols = flat.columns(s.start, s.endExclusive)
        TextLinkSpan(cols.first, cols.last + 1, s.uri)
    }
}
