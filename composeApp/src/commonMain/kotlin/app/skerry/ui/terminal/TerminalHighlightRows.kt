package app.skerry.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.highlight.CommandVocabulary
import app.skerry.shared.terminal.highlight.HighlightKind
import app.skerry.shared.terminal.highlight.MAX_OUTPUT_SCAN
import app.skerry.shared.terminal.highlight.highlightOutputLine
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.highlight.tokenizeCommandLine

/**
 * Highlight categories of one grid row, by column. Built once per snapshot and read in the draw
 * phase, so lookups must not allocate — hence a sorted array pair and a binary search rather than a
 * map.
 */
internal class RowHighlight private constructor(
    private val starts: IntArray,
    private val ends: IntArray,
    private val kinds: Array<HighlightKind>,
) {
    fun kindAt(col: Int): HighlightKind {
        var low = 0
        var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                col < starts[mid] -> high = mid - 1
                col >= ends[mid] -> low = mid + 1
                else -> return kinds[mid]
            }
        }
        return HighlightKind.None
    }

    companion object {
        val Empty = RowHighlight(IntArray(0), IntArray(0), emptyArray())

        /** Builds from column spans; [spans] must be sorted by start and non-overlapping. */
        fun of(spans: List<ColumnSpan>): RowHighlight {
            if (spans.isEmpty()) return Empty
            return RowHighlight(
                starts = IntArray(spans.size) { spans[it].startCol },
                ends = IntArray(spans.size) { spans[it].endColExclusive },
                kinds = Array(spans.size) { spans[it].kind },
            )
        }
    }
}

/** A highlight category over grid columns `[startCol, endColExclusive)` of one row. */
internal data class ColumnSpan(val startCol: Int, val endColExclusive: Int, val kind: HighlightKind)

/**
 * Whether this cell's color is the client's to choose. A cell the server already styled is left
 * alone: `ls --color`, `git`, compiler diagnostics and TUI chrome picked their colors deliberately,
 * and overpainting them would destroy information rather than add it.
 *
 * `inverse` is excluded because reverse-video makes the foreground paint the *background* (mc's
 * selected row), and `hidden` because a hidden cell is password echo that must stay invisible.
 *
 * This gate doubles as the damage limiter for the prompt heuristic: a colored prompt (oh-my-zsh,
 * powerline) fails it, so even a mis-cut prompt is never recolored.
 */
internal fun TermStyle.acceptsHighlight(): Boolean =
    fg == TermColor.Default && bg == TermColor.Default && !inverse && !hidden

/**
 * Highlight categories for the rows in [window], keyed by row index. Rows with nothing to highlight
 * are absent, and both features off yields an empty map — the renderer then walks its old path.
 *
 * The command line (rows around the cursor) and output (everything else) never overlap: the cursor's
 * own chain of rows is excluded from the output pass, so a typed `ERROR` isn't recolored as a log
 * level while it is still being typed.
 *
 * Composed of the two split passes so the equivalence stays pinned by one entry point; the
 * composition layer calls the passes separately to cache them under different keys.
 */
internal fun highlightRows(
    source: HighlightSource,
    window: IntRange,
    settings: TerminalHighlight,
    vocabulary: CommandVocabulary,
): Map<Int, RowHighlight> = overlayLiveCommand(
    base = backgroundHighlightRows(
        source = source.copy(cursor = null),
        window = window,
        settings = settings,
        vocabulary = vocabulary,
        liveChainStart = liveChainStart(source),
    ),
    source = source,
    settings = settings,
    vocabulary = vocabulary,
)

/** The first row of the cursor's soft-wrap chain, or null off-grid/on the alt screen. */
internal fun liveChainStart(source: HighlightSource): Int? {
    val cursor = source.cursor ?: return null
    if (source.altScreen || cursor.row !in source.screen.indices) return null
    return chainStart(source.screen, cursor.row)
}

// Test-only counters (single-threaded: written from composition or the sequential test JVM -
// revisit before enabling parallel test execution). They pin that cursor moves do not rescan the
// window and that content changes do re-run the overlay.
internal var backgroundHighlightPasses = 0
internal var liveOverlayPasses = 0

/**
 * The near-cursor-independent passes — executed commands and output — over the whole [window].
 * This is the expensive scan (every visible row); its only cursor input is [liveChainStart] (the
 * row, not the column), which changes when the cursor crosses a chain boundary — line editing and
 * history recall keep it stable, so the composition layer caches this pass across those. The
 * source's own cursor is ignored ([HighlightSource.cursor] may be anything; callers pass null).
 */
internal fun backgroundHighlightRows(
    source: HighlightSource,
    window: IntRange,
    settings: TerminalHighlight,
    vocabulary: CommandVocabulary,
    liveChainStart: Int?,
): Map<Int, RowHighlight> {
    if (!settings.commandLine && !settings.output) return emptyMap()
    backgroundHighlightPasses++
    val screen = source.screen
    val out = HashMap<Int, RowHighlight>()
    val commandRows = HashSet<Int>()
    if (settings.commandLine) {
        // Commands already run keep their colors: the cursor has moved to the next prompt, and
        // without this pass a command would go plain the moment it is executed. The live chain's
        // start row is excluded here unconditionally - the overlay cannot always reclaim it (a
        // cursor inside a prompt parses no live command line), and the old single pass never let
        // the cursor's own row read as executed.
        for (r in window) {
            if (r in commandRows) continue
            commandRows += tokenizeSlices(source, executedCommandSlices(source, r, liveChainStart), vocabulary, out)
        }
    }
    if (settings.output && !source.altScreen) {
        for (r in window) {
            if (r in commandRows || r !in screen.indices) continue
            outputHighlight(screen[r])?.let { out[r] = it }
        }
    }
    return out
}

/**
 * The cursor-dependent half: tokenizes the live command chain (≤ a handful of rows) and lays it
 * over [base], removing whatever the background painted on those rows first — a typed `ERROR`
 * must read as an argument, not as a log level. Cheap per cursor move: one map copy of at most a
 * window's worth of entries plus the small live chain.
 */
internal fun overlayLiveCommand(
    base: Map<Int, RowHighlight>,
    source: HighlightSource,
    settings: TerminalHighlight,
    vocabulary: CommandVocabulary,
): Map<Int, RowHighlight> {
    if (!settings.commandLine) return base
    liveOverlayPasses++
    val slices = commandLineSlices(source)
    if (slices.isEmpty()) return base
    val live = HashMap<Int, RowHighlight>()
    val claimed = tokenizeSlices(source, slices, vocabulary, live)
    if (claimed.isEmpty()) return base
    val out = HashMap<Int, RowHighlight>(base.size + live.size)
    out.putAll(base)
    claimed.forEach { out.remove(it) }
    out.putAll(live)
    return out
}

/**
 * Tokenizes one command line ([slices], from either the cursor or a line already run) and fills
 * [out] with its rows. Returns the rows it claimed, so the output pass can skip them — a typed
 * `ERROR` must read as an argument, not as a log level.
 */
private fun tokenizeSlices(
    source: HighlightSource,
    slices: List<CommandLineSlice>,
    vocabulary: CommandVocabulary,
    out: MutableMap<Int, RowHighlight>,
): Set<Int> {
    val screen = source.screen
    if (slices.isEmpty()) return emptySet()
    val flat = commandLineText(screen, slices)
    if (flat.text.isBlank()) return slices.mapTo(HashSet()) { it.row }

    val perRow = HashMap<Int, MutableList<ColumnSpan>>()
    for (span in tokenizeCommandLine(flat.text, vocabulary)) {
        for (i in span.start until span.endExclusive) {
            val row = flat.rowAt(i)
            val col = flat.colAt(i)
            if (screen[row].getOrNull(col)?.style?.acceptsHighlight() != true) continue
            perRow.getOrPut(row) { ArrayList() }.appendColumn(col, span.kind)
        }
    }
    perRow.forEach { (row, spans) -> out[row] = RowHighlight.of(spans) }
    return slices.mapTo(HashSet()) { it.row }
}

/** Log levels, addresses and timestamps in one output row, or `null` when it holds none. */
private fun outputHighlight(row: List<TermCell>): RowHighlight? {
    if (!rowHasOutputMarker(row)) return null
    val flat = rowText(row) ?: return null
    val spans = highlightOutputLine(flat.text)
    if (spans.isEmpty()) return null
    val columns = ArrayList<ColumnSpan>(spans.size)
    for (span in spans) {
        for (i in span.start until span.endExclusive) {
            val col = flat.column(i)
            if (row.getOrNull(col)?.style?.acceptsHighlight() != true) continue
            columns.appendColumn(col, span.kind)
        }
    }
    return if (columns.isEmpty()) null else RowHighlight.of(columns)
}

/**
 * Appends column [col] to the last span when it continues it, otherwise starts a new one. Building
 * per column (rather than per token) is what lets a span be cut around cells the server colored.
 */
private fun MutableList<ColumnSpan>.appendColumn(col: Int, kind: HighlightKind) {
    val last = lastOrNull()
    if (last != null && last.kind == kind && last.endColExclusive == col) {
        this[lastIndex] = last.copy(endColExclusive = col + 1)
    } else {
        add(ColumnSpan(col, col + 1, kind))
    }
}

/**
 * Allocation-free prescan: every rule needs a digit (addresses, timestamps), an uppercase letter (a
 * bare `ERROR`) or a colon (`error:`, `10:11:12`). Output rows are scanned per snapshot, and prose
 * — the bulk of them — carries none of the three.
 */
private fun rowHasOutputMarker(row: List<TermCell>): Boolean {
    var scanned = 0
    for (cell in row) {
        for (ch in cell.text) {
            if (ch.isDigit() || ch.isUpperCase() || ch == ':') return true
            if (++scanned >= MAX_OUTPUT_SCAN) return false
        }
    }
    return false
}

/**
 * Highlight categories for [window], recomputed only when something they depend on changes.
 *
 * Deliberately in composition rather than in the draw phase: the terminal Canvas repaints on every
 * cursor blink and on every pixel of a selection drag, and tokenizing there would redo identical
 * work dozens of times a second.
 */
@Composable
internal fun rememberRowHighlights(
    state: TerminalScreenState,
    screen: List<List<TermCell>>,
    window: IntRange,
): Map<Int, RowHighlight> {
    val settings = LocalTerminalHighlight.current
    // Two caches under different keys: the background pass scans the whole window and depends
    // only on content — a cursor move (arrows, history) must not rescan it; the live-command
    // overlay is the only cursor-keyed piece and touches at most a handful of rows.
    // `state` in the keys: the content version is per-emulator, and an in-place session switch
    // could coincide on the counter — see the same note at TerminalScreen's searchWindow.
    val liveSource = HighlightSource(
        screen = screen,
        cursor = TerminalPos(state.cursorRow, state.cursorCol),
        altScreen = state.altScreen,
        executedCommands = state.executedCommands,
    )
    // The chain-start ROW (not the column): stable across line editing and history recall, so the
    // background cache survives them; it moves only when the cursor crosses a chain boundary.
    val chainStart = liveChainStart(liveSource)
    val background = remember(
        state, state.screenContentVersion, window, settings, state.vocabulary, state.executedCommands,
        state.altScreen, chainStart,
    ) {
        backgroundHighlightRows(
            source = liveSource.copy(cursor = null),
            window = window,
            settings = settings,
            vocabulary = state.vocabulary,
            liveChainStart = chainStart,
        )
    }
    // screenContentVersion in the keys, not just `background`: remember compares with equals(),
    // and two content-equal background maps (both empty, say) would keep a stale overlay while
    // the live line's text changed under a stationary cursor (Delete, same-length history recall).
    return remember(
        state, state.screenContentVersion, background, settings, state.vocabulary,
        state.cursorRow, state.cursorCol, state.altScreen,
    ) {
        overlayLiveCommand(
            base = background,
            source = liveSource,
            settings = settings,
            vocabulary = state.vocabulary,
        )
    }
}
