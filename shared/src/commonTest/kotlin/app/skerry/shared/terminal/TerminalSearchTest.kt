package app.skerry.shared.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalSearchTest {

    /** Grid from string rows: each character is a default-style cell. */
    private fun grid(vararg rows: String): List<List<TermCell>> =
        rows.map { row -> row.map { TermCell(it) } }

    @Test
    fun `finds a plain substring and reports its cell range`() {
        val result = searchTerminal(grid("error: disk full"), "disk")

        assertEquals(listOf(TerminalMatch(row = 0, startCol = 7, endCol = 11)), result.matches)
        assertEquals(null, result.error)
    }

    @Test
    fun `search is case insensitive by default and exact when asked`() {
        val screen = grid("Error", "error")

        assertEquals(listOf(0, 1), searchTerminal(screen, "error").matches.map { it.row })
        assertEquals(listOf(1), searchTerminal(screen, "error", caseSensitive = true).matches.map { it.row })
    }

    @Test
    fun `all occurrences on a row are reported in column order`() {
        val result = searchTerminal(grid("ab ab ab"), "ab")

        assertEquals(listOf(0, 3, 6), result.matches.map { it.startCol })
    }

    @Test
    fun `overlapping candidates advance past the previous match`() {
        // "aaaa" contains "aa" at 0,1,2 — non-overlapping scanning reports 0 and 2, never a loop.
        val result = searchTerminal(grid("aaaa"), "aa")

        assertEquals(listOf(0, 2), result.matches.map { it.startCol })
    }

    @Test
    fun `an empty query finds nothing`() {
        assertEquals(emptyList(), searchTerminal(grid("anything"), "").matches)
    }

    @Test
    fun `matches carry the row they were found on`() {
        val result = searchTerminal(grid("nothing here", "the needle", "nothing"), "needle")

        assertEquals(1, result.matches.single().row)
        assertEquals(4, result.matches.single().startCol)
    }

    @Test
    fun `trailing blank cells are not searchable`() {
        // Grid rows are padded to the terminal width; a query of spaces must not match that padding,
        // or every row would "contain" the query (the same trim copy/selection does).
        val result = searchTerminal(grid("ok        "), "  ")

        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `a wide cell spans two columns in the reported range`() {
        // CJK glyph occupies two columns: the match must cover the continuation cell too, or the
        // highlight would leave half the glyph unpainted.
        val row = listOf(
            TermCell("a"),
            TermCell("漢", width = CellWidth.Wide),
            TermCell("", width = CellWidth.Continuation),
            TermCell("b"),
        )
        val result = searchTerminal(listOf(row), "漢")

        assertEquals(TerminalMatch(row = 0, startCol = 1, endCol = 3), result.matches.single())
    }

    @Test
    fun `a match after a wide cell keeps grid columns, not string offsets`() {
        val row = listOf(
            TermCell("漢", width = CellWidth.Wide),
            TermCell("", width = CellWidth.Continuation),
            TermCell("o"),
            TermCell("k"),
        )
        val result = searchTerminal(listOf(row), "ok")

        assertEquals(TerminalMatch(row = 0, startCol = 2, endCol = 4), result.matches.single())
    }

    @Test
    fun `regex mode matches a pattern`() {
        val result = searchTerminal(grid("code 404 here", "code 200 here"), "4\\d\\d", regex = true)

        assertEquals(listOf(TerminalMatch(row = 0, startCol = 5, endCol = 8)), result.matches)
    }

    @Test
    fun `regex metacharacters are literal in substring mode`() {
        val result = searchTerminal(grid("a.c", "abc"), "a.c")

        assertEquals(listOf(0), result.matches.map { it.row })
    }

    @Test
    fun `an invalid regex reports an error instead of matches`() {
        val result = searchTerminal(grid("anything"), "a(", regex = true)

        assertEquals(TerminalSearchError.InvalidPattern, result.error)
        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `a regex that can match empty never loops and yields no zero-width matches`() {
        // "x*" matches empty at every position; a naive scan would either hang or report the whole row.
        val result = searchTerminal(grid("axxb"), "x*", regex = true)

        assertEquals(listOf(TerminalMatch(row = 0, startCol = 1, endCol = 3)), result.matches)
    }

    @Test
    fun `a regex that outruns the step budget is abandoned instead of hanging the UI`() {
        // A backtracking pattern must not block the caller indefinitely. The budget is asserted
        // directly (a tiny one here) rather than through a known-exponential pattern: how much
        // backtracking a given regex engine actually does is a JVM/ART implementation detail.
        val result = searchTerminal(grid("a".repeat(40) + "!"), "(a+)+b", regex = true, stepBudget = 8)

        assertEquals(TerminalSearchError.PatternTooComplex, result.error)
        assertEquals(emptyList(), result.matches)
    }

    @Test
    fun `one pathological row does not take the rest of the buffer down with it`() {
        // A single very long row (a URL, a JWT, minified JSON) can backtrack far past its share of
        // the budget. Skipping it is right; refusing to search the other 9 999 rows is not.
        val nasty = "x".repeat(6000) // no "error" in it: the pattern backtracks over the whole row
        val screen = grid(nasty, "boom: error here")

        val result = searchTerminal(screen, "(.*)*error", regex = true)

        assertEquals(listOf(1), result.matches.map { it.row })
        assertEquals(TerminalSearchError.PatternTooComplex, result.error) // partial, and says so
    }

    @Test
    fun `an ordinary regex over a wide row stays inside its per-row budget`() {
        // The per-row share must not turn normal searching into "too complex" on wide terminals.
        val screen = grid("2026-07-25 12:00:00 " + "payload=abc123 ".repeat(30) + "done in 42ms")

        val result = searchTerminal(screen, "in \\d+ms", regex = true)

        assertEquals(null, result.error)
        assertEquals(1, result.matches.size)
    }

    @Test
    fun `a cancelled search stops early`() {
        // The caller supersedes a search (another character typed): it must stop scanning rather
        // than run the whole buffer to completion.
        val screen = grid(*Array(5000) { "hit" })
        var rowsSeen = 0

        val result = searchTerminal(screen, "hit", cancelled = { rowsSeen++ > 0 })

        assertTrue(result.matches.size < screen.size, "expected an early stop, got ${result.matches.size} matches")
    }

    @Test
    fun `an ordinary regex over a deep scrollback stays within the default budget`() {
        // The guard must not fire on normal use: a full scrollback of typical rows.
        val rows = Array(10_000) { "2026-07-25 12:00:00 service[$it]: request handled in 12ms" }

        val result = searchTerminal(grid(*rows), "in \\d+ms", regex = true)

        assertEquals(null, result.error)
        assertEquals(5000, result.matches.size) // the default match limit, not a budget failure
        assertTrue(result.truncated)
    }

    @Test
    fun `the match list is capped and reports truncation`() {
        val rows = Array(50) { "hit hit hit hit" }
        val result = searchTerminal(grid(*rows), "hit", limit = 10)

        assertEquals(10, result.matches.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `an untruncated result reports no truncation`() {
        assertEquals(false, searchTerminal(grid("hit"), "hit").truncated)
    }

    @Test
    fun `matches are ordered top to bottom`() {
        val result = searchTerminal(grid("hit", "miss", "hit"), "hit")

        assertEquals(listOf(0, 2), result.matches.map { it.row })
    }

    @Test
    fun `a row window limits the scan and still reports absolute rows`() {
        // The render highlights only the visible rows; their indices must stay buffer indices, or
        // the wash would land on the wrong lines.
        val screen = grid("hit", "hit", "hit", "hit")

        val result = searchTerminal(screen, "hit", fromRow = 1, toRow = 3)

        assertEquals(listOf(1, 2), result.matches.map { it.row })
    }

    @Test
    fun `a row window past the buffer is clamped`() {
        val screen = grid("hit", "hit")

        assertEquals(listOf(1), searchTerminal(screen, "hit", fromRow = 1, toRow = 99).matches.map { it.row })
        assertEquals(emptyList(), searchTerminal(screen, "hit", fromRow = 5, toRow = 99).matches)
        assertEquals(emptyList(), searchTerminal(screen, "hit", fromRow = -3, toRow = -1).matches)
    }

    // --- currentMatchFor: which match to select when the query changes ---

    @Test
    fun `the first selected match is the last one at or above the viewport bottom`() {
        // Opening search on a live terminal should land on the newest match the user can see, the
        // way less/vim land near the cursor rather than at the top of a long scrollback.
        val matches = listOf(
            TerminalMatch(2, 0, 3),
            TerminalMatch(40, 0, 3),
            TerminalMatch(90, 0, 3),
        )

        assertEquals(1, matchNearestTo(matches, row = 50))
    }

    @Test
    fun `with every match below the anchor the first one is selected`() {
        val matches = listOf(TerminalMatch(70, 0, 3), TerminalMatch(90, 0, 3))

        assertEquals(0, matchNearestTo(matches, row = 10))
    }

    @Test
    fun `an empty match list has no selection`() {
        assertEquals(-1, matchNearestTo(emptyList(), row = 10))
    }
}
