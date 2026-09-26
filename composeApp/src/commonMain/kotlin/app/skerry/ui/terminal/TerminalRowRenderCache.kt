package app.skerry.ui.terminal

import androidx.compose.ui.text.TextLayoutResult
import app.skerry.shared.terminal.TermCell

/**
 * Glyph runs and their text layouts per grid row, kept across publishes. The emulator hands out
 * the same row instance for a row nothing wrote to, so an entry stays valid while its row and its
 * highlight are the ones being drawn: a status-line update re-segments and re-lays-out one row,
 * not the screen.
 *
 * Looked up by row index and validated by identity — common code has no identity hash, and a
 * structural key would cost an O(cols) hash per lookup and alias duplicate rows. An index that now
 * holds another row (history trimmed, a reflow) is simply a miss. Bounded by rows and by runs:
 * a row of alternating colors costs a run and a layout per cell. Draw phase only (UI thread).
 */
internal class RowRenderCache(private val capacity: Int, private val maxRuns: Int) {

    class Entry(val row: List<TermCell>, val highlight: RowHighlight?, val runs: List<GlyphRun>) {
        private val layouts = arrayOfNulls<TextLayoutResult>(runs.size)
        private var layoutsFor: Any? = null

        /**
         * The layout of run [index] if one was built in this [generation] — whatever layouts depend
         * on besides the text (base style, palette, theme, density); a new generation drops them all.
         */
        fun layout(index: Int, generation: Any): TextLayoutResult? {
            if (layoutsFor !== generation) {
                layouts.fill(null)
                layoutsFor = generation
            }
            return layouts[index]
        }

        fun storeLayout(index: Int, layout: TextLayoutResult) {
            layouts[index] = layout
        }
    }

    private val entries = HashMap<Int, Entry>()
    private var runs = 0

    private var runBudget = maxRuns

    /**
     * Makes room for a window of [cells]: a run per cell is the worst a row can cost, and a budget
     * below one window would clear the cache on every draw and relayout the whole screen.
     */
    fun fitWindow(cells: Int) {
        runBudget = maxOf(maxRuns, cells * 2)
    }

    fun entry(index: Int, row: List<TermCell>, highlight: RowHighlight?): Entry {
        val cached = entries[index]
        if (cached != null && cached.row === row && cached.highlight === highlight) return cached
        val fresh = Entry(row, highlight, glyphRuns(row, highlight))
        if (cached != null) runs -= cached.runs.size
        // Wholesale, like the previous per-publish map: paging through deep history in an idle
        // session must not retain runs for the whole scrollback.
        if (entries.size >= capacity || runs + fresh.runs.size > runBudget) {
            entries.clear()
            runs = 0
        }
        entries[index] = fresh
        runs += fresh.runs.size
        return fresh
    }
}
