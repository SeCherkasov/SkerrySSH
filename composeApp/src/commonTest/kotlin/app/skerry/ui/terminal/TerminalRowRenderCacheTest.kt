package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TerminalRowRenderCacheTest {

    private val palette = listOf(TermColor.Red, TermColor.Green)

    // Every cell a style of its own: one glyph run per cell, the worst case a row can cost.
    private fun striped(width: Int) = List(width) { TermCell("x", TermStyle(fg = palette[it % 2])) }

    @Test
    fun `an unchanged row keeps its entry`() {
        val cache = RowRenderCache(capacity = 8, maxRuns = 100)
        val row = striped(4)
        assertSame(cache.entry(0, row, null), cache.entry(0, row, null))
    }

    @Test
    fun `rows full of runs are dropped before the row count is reached`() {
        val cache = RowRenderCache(capacity = 8, maxRuns = 10)
        val first = striped(4)
        val kept = cache.entry(0, first, null)
        cache.entry(1, striped(4), null)
        cache.entry(2, striped(4), null) // 12 runs > 10
        assertNotSame(kept, cache.entry(0, first, null))
    }

    @Test
    fun `the run budget grows to hold the visible window`() {
        val cache = RowRenderCache(capacity = 8, maxRuns = 10)
        cache.fitWindow(cells = 12) // a window of 3 rows x 4 columns, one run per cell at worst
        val first = striped(4)
        val kept = cache.entry(0, first, null)
        cache.entry(1, striped(4), null)
        cache.entry(2, striped(4), null)
        assertSame(kept, cache.entry(0, first, null), "the window alone must not overflow the cache")
    }

    @Test
    fun `replacing a row does not count its old runs twice`() {
        val cache = RowRenderCache(capacity = 8, maxRuns = 10)
        val kept = striped(4)
        val entry = cache.entry(0, kept, null)
        repeat(5) { cache.entry(1, striped(4), null) } // always 8 runs in the cache
        assertSame(entry, cache.entry(0, kept, null))
    }
}
