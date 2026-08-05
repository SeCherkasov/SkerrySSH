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
 * Grid rows flattened to plain [text], with every character mapped back to the grid row and column it
 * was drawn at. String indices and columns diverge because a wide glyph occupies two columns (its
 * continuation cell has empty text) and a cell may hold a combining sequence — without the map,
 * matches would land a column or two off.
 *
 * More than one row at a time because auto-wrap cuts a logical line at the right margin: a URL split
 * there is still one URL, so it must be detected over the joined text and painted per row.
 */
internal class RowText(val text: String, private val rowOf: IntArray?, private val colOf: IntArray) {

    /** Column the character at string index [index] was drawn in (single-row callers). */
    fun column(index: Int): Int = colOf[index]

    /**
     * The part of the string span `[start, endExclusive)` that landed on grid [row], as a column
     * range, or `null` when the span does not touch that row. Columns are contiguous within a row —
     * the span is a substring of one logical line.
     */
    fun columnsOn(row: Int, start: Int, endExclusive: Int): IntRange? {
        // A single-row flatten keeps no row map — every character came from the one row it was built from.
        if (rowOf == null) return if (row != 0) null else colOf[start]..colOf[endExclusive - 1]
        var from = -1
        var to = -1
        for (i in start until endExclusive) {
            if (rowOf[i] != row) continue
            if (from < 0) from = colOf[i]
            to = colOf[i]
        }
        return if (from < 0) null else from..to
    }
}

/**
 * Flattens a single grid row for text-level detectors, or `null` when it holds no characters at all.
 * Its only row is numbered 0. Callers do their own allocation-free "could this row match at all"
 * prescan first — this builds a StringBuilder, and on a draw pass most rows can't match anything.
 */
internal fun rowText(row: List<TermCell>): RowText? {
    val builder = RowTextBuilder(row.size, trackRows = false)
    builder.append(row, 0)
    return builder.build()
}

/**
 * Flattens grid rows [rows] of [screen] (a soft-wrap chain) into one text, or `null` when they hold
 * no characters at all. Rows are joined with nothing between them: a soft wrap is not a character, so
 * the logical line reads exactly as the server printed it.
 */
internal fun rowsText(screen: List<List<TermCell>>, rows: IntRange): RowText? {
    var capacity = 0
    for (r in rows) capacity += screen[r].size
    val builder = RowTextBuilder(capacity, trackRows = true)
    for (r in rows) builder.append(screen[r], r)
    return builder.build()
}

/**
 * Accumulator behind [rowText] and [rowsText]: text plus the index maps, grown together. IntArray with a
 * counter rather than ArrayList<Int> — this runs for every visible row of every snapshot, and boxing
 * one Integer per character showed up as steady GC pressure while output streams. A row can hold
 * combining sequences, so the arrays are grown rather than sized once.
 */
private class RowTextBuilder(capacity: Int, trackRows: Boolean) {
    private val text = StringBuilder(capacity)
    // Only a multi-row flatten needs the row map; single-row callers (highlighting, prompt scan) run
    // per visible row per frame, and a second array of the same size there is pure waste.
    private var rowOf = if (trackRows) IntArray(capacity) else null
    private var colOf = IntArray(capacity)
    private var n = 0

    fun append(row: List<TermCell>, r: Int) {
        for (c in row.indices) for (ch in row[c].text) add(ch, r, c)
    }

    fun build(): RowText? = if (n == 0) null else RowText(text.toString(), rowOf, colOf)

    private fun add(ch: Char, r: Int, c: Int) {
        if (n == colOf.size) {
            colOf = colOf.copyOf(n * 2 + 1)
            rowOf = rowOf?.copyOf(n * 2 + 1)
        }
        text.append(ch)
        rowOf?.set(n, r)
        colOf[n] = c
        n++
    }
}

/**
 * Runs [detect] over a grid row's text and returns its spans in **column** coordinates (see
 * [RowText]), so a click lands on the match rather than beside it.
 */
internal fun rowTextSpans(row: List<TermCell>, detect: (String) -> List<TextLinkSpan>): List<TextLinkSpan> {
    val flat = rowText(row) ?: return emptyList()
    return flat.spansOn(0, detect(flat.text))
}

/**
 * Maps string-index spans onto the columns they cover on grid [row], dropping those that miss it.
 * The URI travels unchanged, so a match split across rows opens whole from any of its parts.
 */
internal fun RowText.spansOn(row: Int, found: List<TextLinkSpan>): List<TextLinkSpan> {
    if (found.isEmpty()) return emptyList()
    return found.mapNotNull { s ->
        columnsOn(row, s.start, s.endExclusive)?.let { TextLinkSpan(it.first, it.last + 1, s.uri) }
    }
}
