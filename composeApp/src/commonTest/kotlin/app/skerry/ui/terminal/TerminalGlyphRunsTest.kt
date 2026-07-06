package app.skerry.ui.terminal

import app.skerry.shared.terminal.CellWidth
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Segmenting a row into glyph runs for rendering. Key requirement: consecutive ASCII cells
 * collapse into a single `drawText` call (monospace is guaranteed), while each non-ASCII glyph
 * (box-drawing `─│┌`, CJK, symbols) is drawn in its own single-column run — otherwise the fallback
 * font's non-cellWidth advance accumulates drift and the grid goes out of alignment.
 */
class TerminalGlyphRunsTest {

    private fun row(vararg cells: TermCell) = cells.toList()

    @Test
    fun `empty row yields no runs`() {
        assertEquals(emptyList(), glyphRuns(emptyList()))
    }

    @Test
    fun `ascii cells of one style merge into a single run`() {
        val runs = glyphRuns(row(TermCell('a'), TermCell('b'), TermCell('c')))
        assertEquals(1, runs.size)
        assertEquals(GlyphRun(0, "abc", 3, TermStyle()), runs[0])
    }

    @Test
    fun `spaces are ascii and stay in the run`() {
        val runs = glyphRuns(row(TermCell('a'), TermCell(' '), TermCell('b')))
        assertEquals(1, runs.size)
        assertEquals("a b", runs[0].text)
    }

    @Test
    fun `each box-drawing glyph is its own single-column run`() {
        // ─── (U+2500 x3): must not merge into one run, or the border line drifts.
        val dash = '─'
        val runs = glyphRuns(row(TermCell(dash), TermCell(dash), TermCell(dash)))
        assertEquals(3, runs.size)
        runs.forEachIndexed { i, r ->
            assertEquals(i, r.col)
            assertEquals(dash.toString(), r.text)
            assertEquals(1, r.span)
        }
    }

    @Test
    fun `box-drawing splits surrounding ascii runs`() {
        // "│ a": border vertical U+2502 followed by an ASCII tail; each its own column segment.
        val bar = '│'
        val runs = glyphRuns(row(TermCell(bar), TermCell(' '), TermCell('a')))
        assertEquals(2, runs.size)
        assertEquals(GlyphRun(0, bar.toString(), 1, TermStyle()), runs[0])
        assertEquals(GlyphRun(1, " a", 2, TermStyle()), runs[1])
    }

    @Test
    fun `style break splits the ascii run`() {
        val bold = TermStyle(bold = true)
        val runs = glyphRuns(row(TermCell('a'), TermCell('b', bold), TermCell('c', bold)))
        assertEquals(2, runs.size)
        assertEquals(GlyphRun(0, "a", 1, TermStyle()), runs[0])
        assertEquals(GlyphRun(1, "bc", 2, bold), runs[1])
    }

    @Test
    fun `wide cell is its own two-column run and continuation is skipped`() {
        val wide = TermCell("中", TermStyle(), CellWidth.Wide)
        val cont = TermCell("", TermStyle(), CellWidth.Continuation)
        val runs = glyphRuns(row(TermCell('a'), wide, cont, TermCell('b')))
        assertEquals(3, runs.size)
        assertEquals(GlyphRun(0, "a", 1, TermStyle()), runs[0])
        assertEquals(GlyphRun(1, "中", 2, TermStyle()), runs[1])
        assertEquals(GlyphRun(3, "b", 1, TermStyle()), runs[2])
    }
}
