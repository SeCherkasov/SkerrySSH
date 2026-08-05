package app.skerry.shared.terminal

/** Default cap on reported matches: a highlight pass walks them per repaint, so it stays bounded. */
const val DEFAULT_SEARCH_MATCH_LIMIT = 5000

/**
 * Character-access budget for one regex search over the whole buffer, bounding wall-clock time
 * rather than pattern shape: roughly 4 ms per million reads on a desktop JVM, so ~80 ms worst case.
 * A user pattern is not hostile input, but an everyday one — `(.*)*error`, `(.+)+@` — backtracks
 * quadratically over a long unwrapped row (a URL, a JWT, minified JSON), and without a ceiling that
 * is seconds of compute. Sized above a full linear scan of a deep scrollback, so ordinary searches
 * never reach it.
 *
 * Note on the guard's reach: it counts characters the regex engine reads, which works because
 * `java.util.regex` walks its input through `CharSequence.get` (verified on OpenJDK 21; Android's
 * libcore regex is derived from the same code but this has not been measured on a device).
 */
const val DEFAULT_REGEX_STEP_BUDGET = 20_000_000

/**
 * Per-row slice of [DEFAULT_REGEX_STEP_BUDGET]: 64 reads per character plus a floor, i.e. quadratic
 * backtracking over a normal-width row still completes, while a pathological row is abandoned. One
 * such row must not take the whole search down with it — the rest of the buffer still searches, and
 * the result is flagged [TerminalSearchError.PatternTooComplex].
 */
private fun rowStepBudget(length: Int): Int = 4096 + length * 64

/** Why a search produced nothing usable (only for regex mode; a substring search cannot fail). */
enum class TerminalSearchError {
    /** The pattern does not compile. */
    InvalidPattern,

    /**
     * The pattern blew its backtracking budget ([DEFAULT_REGEX_STEP_BUDGET]) on at least one row.
     * Matches found elsewhere are still reported — the buffer is only partly searched.
     */
    PatternTooComplex,
}

/**
 * One hit in the terminal buffer: [row] in the snapshot grid and the half-open column range
 * `[startCol, endCol)`. Columns are grid cells, not string offsets, so a wide (CJK) glyph covers its
 * continuation cell too and the highlight lines up with the text.
 */
data class TerminalMatch(val row: Int, val startCol: Int, val endCol: Int) {
    /** Whether cell [col] of this row is inside the match (for the highlight pass). */
    fun contains(col: Int): Boolean = col >= startCol && col < endCol
}

/**
 * Search outcome: [matches] top to bottom, [truncated] when the limit cut the list short, and
 * [error] when the pattern could not be used at all (then [matches] is empty).
 */
data class TerminalSearchResult(
    val matches: List<TerminalMatch> = emptyList(),
    val truncated: Boolean = false,
    val error: TerminalSearchError? = null,
)

/**
 * Finds [query] in the terminal buffer [screen] (scrollback + screen rows, as published by
 * [TerminalEmulator.lines]).
 *
 * Each row is searched on its own: rows are physical, so a hit spanning a soft wrap is not found.
 * The snapshot does carry the flag ([wrapsToNextRow]) — joining rows here is possible and simply out
 * of scope: the hit list is a row/column range the render highlights, and a match crossing a wrap
 * would have to become several of them.
 * Trailing blank cells are ignored, as when copying a selection: grid rows are padded to the
 * terminal width, and searching the padding would match spaces on every row.
 *
 * [fromRow]/[toRow] narrow the scan to a row window (reported rows stay absolute buffer indices):
 * the render highlights only what is on screen, and walking a 50 000-row scrollback per frame is
 * not affordable. Out-of-range bounds are clamped.
 *
 * [regex] switches from a literal substring to a regular expression; [caseSensitive] is off by
 * default. Matches never overlap: scanning resumes after the previous hit, and a zero-width regex
 * match (`x*`) is skipped rather than reported (and cannot loop the scan). [stepBudget] bounds the
 * regex engine's work (see [DEFAULT_REGEX_STEP_BUDGET]); [cancelled] lets a caller abandon a search
 * that has been superseded — it is polled per row, and the partial result is then discarded.
 */
fun searchTerminal(
    screen: List<List<TermCell>>,
    query: String,
    caseSensitive: Boolean = false,
    regex: Boolean = false,
    limit: Int = DEFAULT_SEARCH_MATCH_LIMIT,
    stepBudget: Int = DEFAULT_REGEX_STEP_BUDGET,
    fromRow: Int = 0,
    toRow: Int = screen.size,
    cancelled: () -> Boolean = { false },
): TerminalSearchResult {
    if (query.isEmpty() || limit <= 0) return TerminalSearchResult()
    val first = fromRow.coerceIn(0, screen.size)
    val lastExclusive = toRow.coerceIn(first, screen.size)
    val pattern = if (!regex) null else {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        try {
            Regex(query, options)
        } catch (_: IllegalArgumentException) {
            return TerminalSearchResult(error = TerminalSearchError.InvalidPattern)
        }
    }
    val budget = StepBudget(stepBudget)
    val matches = ArrayList<TerminalMatch>()
    var truncated = false
    var tooComplex = false
    for (rowIndex in first until lastExclusive) {
        // Cancellation is checked per row, not per match: a superseded search (the user typed
        // another character) should stop burning CPU rather than run to completion unheard.
        if (rowIndex and CANCEL_CHECK_MASK == 0 && cancelled()) break
        val row = screen[rowIndex]
        // Literal search first tries the allocation-free cell scan; it handles the plain
        // single-width rows that make up nearly all of a buffer, and the search re-runs on every
        // published snapshot, so building a string per row would cost real frames under output.
        if (pattern == null) {
            val fast = findSubstringInCells(row, rowIndex, query, caseSensitive, limit - matches.size)
            if (fast != null) {
                matches += fast
                if (matches.size >= limit) {
                    truncated = true
                    break
                }
                continue
            }
        }
        val text = rowText(row) ?: continue
        val found = if (pattern == null) {
            findSubstring(text, rowIndex, query, caseSensitive, limit - matches.size)
        } else {
            budget.startRow(rowStepBudget(text.text.length))
            try {
                findRegex(text, rowIndex, pattern, budget, limit - matches.size)
            } catch (_: RowBudgetExceeded) {
                // One row is pathological for this pattern; the rest of the buffer is still worth
                // searching, so skip it and flag the result.
                tooComplex = true
                emptyList()
            } catch (_: SearchBudgetExceeded) {
                // The whole search hit its ceiling: stop here, keep what was found, and say so.
                return TerminalSearchResult(matches, truncated, TerminalSearchError.PatternTooComplex)
            }
        }
        matches += found
        if (matches.size >= limit) {
            truncated = true
            break
        }
    }
    return TerminalSearchResult(
        matches = matches,
        truncated = truncated,
        error = if (tooComplex) TerminalSearchError.PatternTooComplex else null,
    )
}

/** How often [searchTerminal] asks whether it has been superseded (every 64th row). */
private const val CANCEL_CHECK_MASK = 63

/**
 * Index of the match to select for anchor row [row]: the last match at or above it (what the user
 * sees at the viewport bottom), else the first match below. `-1` when there is nothing to select.
 * [matches] must be ordered top to bottom, as [searchTerminal] returns them.
 */
fun matchNearestTo(matches: List<TerminalMatch>, row: Int): Int {
    if (matches.isEmpty()) return -1
    val above = matches.indexOfLast { it.row <= row }
    return if (above >= 0) above else 0
}

/**
 * Non-overlapping literal occurrences scanned straight over the cells, with no per-row string or
 * column table built. Returns `null` when the row holds anything but plain one-character
 * single-width cells (wide glyphs, combining sequences) — the caller then takes the general path.
 * Trailing blank cells are excluded exactly as in [rowText].
 */
private fun findSubstringInCells(
    row: List<TermCell>,
    rowIndex: Int,
    query: String,
    caseSensitive: Boolean,
    remaining: Int,
): List<TerminalMatch>? {
    var last = row.lastIndex
    while (last >= 0 && row[last].text.isBlank()) last--
    if (last < 0) return emptyList()
    for (col in 0..last) {
        val cell = row[col]
        if (cell.width != CellWidth.Single || cell.text.length != 1) return null
    }
    val width = last + 1
    if (query.length > width) return emptyList()
    var out: MutableList<TerminalMatch>? = null
    var col = 0
    while (col <= width - query.length) {
        var i = 0
        while (i < query.length && row[col + i].text[0].equals(query[i], ignoreCase = !caseSensitive)) i++
        if (i < query.length) {
            col++
            continue
        }
        val hits = out ?: ArrayList<TerminalMatch>(4).also { out = it }
        hits += TerminalMatch(rowIndex, col, col + query.length)
        if (hits.size >= remaining) break
        col += query.length // non-overlapping, like the string path
    }
    return out ?: emptyList()
}

/** Non-overlapping literal occurrences of [query] in one row. */
private fun findSubstring(
    text: RowText,
    rowIndex: Int,
    query: String,
    caseSensitive: Boolean,
    remaining: Int,
): List<TerminalMatch> {
    val out = ArrayList<TerminalMatch>()
    var from = 0
    while (out.size < remaining) {
        val at = text.text.indexOf(query, from, ignoreCase = !caseSensitive)
        if (at < 0) break
        val end = at + query.length
        out += text.match(rowIndex, at, end)
        from = end
    }
    return out
}

/** Non-overlapping regex matches in one row; zero-width matches are stepped over, not reported. */
private fun findRegex(
    text: RowText,
    rowIndex: Int,
    pattern: Regex,
    budget: StepBudget,
    remaining: Int,
): List<TerminalMatch> {
    val out = ArrayList<TerminalMatch>()
    val guarded = BudgetedCharSequence(text.text, budget)
    var from = 0
    while (out.size < remaining && from <= text.text.length) {
        val match = pattern.find(guarded, from) ?: break
        val range = match.range
        if (range.isEmpty()) {
            from = match.range.first + 1 // zero-width: nothing to highlight, move on
            continue
        }
        out += text.match(rowIndex, range.first, range.last + 1)
        from = range.last + 1
    }
    return out
}

/**
 * A row's searchable text plus the grid column each character came from. Cells can hold more than
 * one character (astral glyphs, combining sequences) and a wide glyph owns two columns, so string
 * offsets and columns are not interchangeable — except on a plain single-width ASCII/BMP row, where
 * the mapping is the identity and the tables are left out ([startCol] == null). That fast path is
 * the common case, and the search re-runs on every published snapshot while the panel is open, so
 * skipping two per-row int tables there is what keeps a full-scrollback pass affordable.
 */
private class RowText(
    val text: CharSequence,
    private val startCol: IntArray?,
    private val endCol: IntArray?,
) {
    /** Converts a `[from, to)` string range into a match over grid columns. */
    fun match(row: Int, from: Int, to: Int): TerminalMatch =
        if (startCol == null || endCol == null) {
            TerminalMatch(row, from, to)
        } else {
            TerminalMatch(row, startCol[from], endCol[to - 1])
        }
}

/** Builds [RowText] for a row, ignoring its trailing blank padding. `null` for a blank row. */
private fun rowText(row: List<TermCell>): RowText? {
    var last = row.lastIndex
    while (last >= 0 && row[last].text.isBlank()) last--
    if (last < 0) return null
    val text = StringBuilder(last + 1)
    // One char per cell and no wide glyph so far: while that holds, column == character index.
    var simple = true
    var startCol: IntArray? = null
    var endCol: IntArray? = null
    for (col in 0..last) {
        val cell = row[col]
        val chars = cell.text.length
        val span = if (cell.width == CellWidth.Wide) 2 else 1
        if (simple && (chars != 1 || span != 1)) {
            // First irregular cell (wide glyph, its empty continuation, or a multi-char cell):
            // materialize the tables over the identity-mapped prefix, then keep filling them below.
            simple = false
            val size = maxOf(row.size + chars, 16)
            startCol = IntArray(size) { it }
            endCol = IntArray(size) { it + 1 }
        }
        if (chars == 0) continue // continuation cell of a wide glyph — carries no text
        if (!simple) {
            val from = text.length
            startCol = startCol.grownTo(from + chars)
            endCol = endCol.grownTo(from + chars)
            for (i in 0 until chars) {
                startCol!![from + i] = col
                endCol!![from + i] = col + span
            }
        }
        text.append(cell.text)
    }
    if (text.isEmpty()) return null
    return if (simple) RowText(text, null, null) else RowText(text, startCol, endCol)
}

/** Grows an index table to hold at least [size] entries (doubling), keeping what is already in it. */
private fun IntArray?.grownTo(size: Int): IntArray {
    val current = this ?: IntArray(size.coerceAtLeast(16))
    if (current.size >= size) return current
    return current.copyOf(maxOf(size, current.size * 2))
}

/**
 * Remaining character reads a regex search may perform: [left] for the whole buffer, [rowLeft] for
 * the row being scanned (never more than what is left overall). Running out of the row's share only
 * costs that row; running out overall ends the search.
 */
private class StepBudget(var left: Int) {
    var rowLeft: Int = 0
        private set

    fun startRow(limit: Int) {
        rowLeft = minOf(limit, left)
    }

    /** Charges one character read, throwing when either budget is spent. */
    fun spend() {
        if (--left < 0) throw SearchBudgetExceeded()
        if (--rowLeft < 0) throw RowBudgetExceeded()
    }
}

/** Thrown when the whole-buffer budget runs out mid-match; caught in [searchTerminal]. */
private class SearchBudgetExceeded : Exception()

/** Thrown when one row's share of the budget runs out; that row is skipped. */
private class RowBudgetExceeded : Exception()

/**
 * Row text that charges every character read to a shared [StepBudget]. The regex engine walks its
 * input through `get`, so a heavily backtracking pattern burns the budget instead of the caller's
 * thread. [subSequence] is deliberately not charged — it is only reached when a match's text is
 * materialized, which this file never does (only `MatchResult.range` is read). Keep it that way:
 * reading `match.value`/`groupValues` would go through the uncharged delegate and slip past the
 * budget accounting.
 */
private class BudgetedCharSequence(
    private val delegate: CharSequence,
    private val budget: StepBudget,
) : CharSequence {
    override val length: Int get() = delegate.length

    override fun get(index: Int): Char {
        budget.spend()
        return delegate[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        delegate.subSequence(startIndex, endIndex)
}
