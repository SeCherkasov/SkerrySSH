package app.skerry.ui.terminal

import app.skerry.shared.guard.ProductionGuard
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalPos

/** Longest chain of soft-wrapped rows treated as one command line. Beyond this it isn't a command. */
private const val MAX_COMMAND_ROWS = 8

/**
 * The part of the typed command line living on grid row [row]: columns `[startCol, endColExclusive)`.
 */
internal data class CommandLineSlice(val row: Int, val startCol: Int, val endColExclusive: Int)

/**
 * The screen state highlighting is derived from: the grid, where the cursor is, whether a
 * full-screen app owns the screen, and what this session has already run. Travels together because
 * no rule reads one without the others.
 *
 * [executedCommands] is what keeps a command colored after Enter: the cursor has moved on to the
 * next prompt, so the line above is no longer "the command line", and matching it against commands
 * this session actually ran is the one signal that cannot mistake output for input.
 */
internal data class HighlightSource(
    val screen: List<List<TermCell>>,
    val cursor: TerminalPos,
    val altScreen: Boolean,
    val executedCommands: Set<String> = emptySet(),
)

/**
 * The typed command line as one string, with each character mapped back to the grid cell it was read
 * from — a wide glyph spans two columns and a continuation cell carries no character at all, so
 * string index and column diverge and spans would land beside their text.
 */
internal class CommandLineText(val text: String, private val rows: IntArray, private val cols: IntArray) {
    fun rowAt(index: Int): Int = rows[index]
    fun colAt(index: Int): Int = cols[index]
}

/**
 * Finds the command line the user is typing, as grid slices with the prompt cut off, or an empty
 * list when nothing on screen reads as one.
 *
 * Only the cursor row and its soft-wrap continuations are considered — never a row of past output.
 * That is what makes a `$ ` printed inside output harmless: it isn't where the cursor is, so it is
 * never examined. Where the prompt ends is [ProductionGuard.promptEnd], the same heuristic the
 * production guard uses to decide what was typed.
 *
 * There is no semantic prompt marking (OSC 133) to lean on, and the snapshot doesn't carry
 * [app.skerry.shared.terminal.TermRow.wrapped], so a soft wrap is inferred from a row being filled
 * to its last column. A row that happens to end exactly at the edge therefore joins the next one —
 * the cost is a highlight reaching one row too far, on a line the user is editing anyway.
 */
internal fun commandLineSlices(source: HighlightSource): List<CommandLineSlice> {
    val screen = source.screen
    val cursor = source.cursor
    // A full-screen app (vim, htop, mc) has no shell line: whatever the cursor sits on is the app's.
    if (source.altScreen) return emptyList()
    if (cursor.row !in screen.indices) return emptyList()

    val first = chainStart(screen, cursor.row)
    val startCol = promptEndColumn(screen[first])
    // No prompt terminator: output, or an app's own prompt — not a shell line we can cut.
    if (startCol < 0) return emptyList()
    // The cursor sits inside the prompt itself — `read -p`, a password, an app asking a question.
    if (cursor.row == first && cursor.col < startCol) return emptyList()
    return chainSlices(screen, first, startCol)
}

/**
 * Slices of a command line the session already ran, starting at grid row [row], or an empty list
 * when that row doesn't begin one.
 *
 * A past line has no cursor to anchor on, so the anchor is [HighlightSource.executedCommands]: the
 * text after the prompt must equal a command this session executed. Output that merely contains a
 * `$ ` never matches, because it would have to be character-for-character a command that ran.
 */
internal fun executedCommandSlices(source: HighlightSource, row: Int): List<CommandLineSlice> {
    val screen = source.screen
    if (source.altScreen || source.executedCommands.isEmpty()) return emptyList()
    if (row !in screen.indices || source.cursor.row !in screen.indices) return emptyList()
    // Only the first row of a wrapped chain starts a command; the rest are handled with it.
    if (row > 0 && isFilledToEdge(screen[row - 1])) return emptyList()
    // The line under the cursor is the live command line and is highlighted by its own path.
    if (row == chainStart(screen, source.cursor.row)) return emptyList()
    if (!rowHasPromptMarker(screen[row])) return emptyList()

    val startCol = promptEndColumn(screen[row])
    if (startCol < 0) return emptyList()
    val slices = chainSlices(screen, row, startCol)
    if (slices.isEmpty()) return emptyList()
    val text = commandLineText(screen, slices).text
    return if (text in source.executedCommands) slices else emptyList()
}

/** Slices of the soft-wrap chain starting at [first], with the prompt cut at [startCol]. */
private fun chainSlices(
    screen: List<List<TermCell>>,
    first: Int,
    startCol: Int,
): List<CommandLineSlice> {
    val last = chainEnd(screen, first)
    val slices = ArrayList<CommandLineSlice>(last - first + 1)
    for (r in first..last) {
        val from = if (r == first) startCol else 0
        // The text ends where the row does, not at the cursor: editing mid-line must not drop the
        // colors from everything to the right of it.
        val to = lastUsedColumn(screen[r]) + 1
        if (to > from) slices.add(CommandLineSlice(r, from, to))
    }
    return slices
}

/**
 * Allocation-free scan for a prompt terminator followed by whitespace. Runs for every visible row of
 * every snapshot, ahead of the StringBuilder and the regex behind [ProductionGuard.promptEnd].
 */
private fun rowHasPromptMarker(row: List<TermCell>): Boolean {
    var prev = ' '
    var scanned = 0
    for (cell in row) {
        for (ch in cell.text) {
            if (prev in ProductionGuard.PROMPT_TERMINATORS && ch.isWhitespace()) return true
            // A prompt lives at the start of the line; scanning the whole row would only find the
            // `>` of a diff or the `%` of a progress bar and pay for flattening the row for nothing.
            if (++scanned > MAX_PROMPT_SCAN) return false
            prev = ch
        }
    }
    return false
}

/** Columns a prompt may occupy. Past this, a terminator belongs to output, not to a prompt. */
private const val MAX_PROMPT_SCAN = 96

/**
 * Flattens [slices] into the string the tokenizer sees, keeping the grid position of every
 * character. A soft wrap continues the same word, so rows are concatenated without a separator.
 */
internal fun commandLineText(screen: List<List<TermCell>>, slices: List<CommandLineSlice>): CommandLineText {
    val sb = StringBuilder()
    val rows = ArrayList<Int>()
    val cols = ArrayList<Int>()
    for (slice in slices) {
        val row = screen[slice.row]
        for (c in slice.startCol until slice.endColExclusive) {
            val cell = row.getOrNull(c) ?: continue
            // A continuation cell has empty text: it adds a column but no character.
            for (ch in cell.text) { sb.append(ch); rows.add(slice.row); cols.add(c) }
        }
    }
    return CommandLineText(sb.toString(), rows.toIntArray(), cols.toIntArray())
}

/**
 * Column where the typed text starts on a prompt row, or -1 when the row doesn't look like a prompt.
 * Works in columns rather than string indices so a wide glyph in the prompt doesn't shift the cut.
 */
private fun promptEndColumn(row: List<TermCell>): Int {
    val flat = rowText(row) ?: return -1
    val end = ProductionGuard.promptEnd(flat.text)
    if (end == 0) return -1
    // promptEnd points just past the terminator's trailing space; that space's column + 1 is the cut.
    return flat.column(end - 1) + 1
}

/** First row of the soft-wrap chain containing [row]. */
private fun chainStart(screen: List<List<TermCell>>, row: Int): Int {
    var first = row
    while (first > 0 && row - first < MAX_COMMAND_ROWS && isFilledToEdge(screen[first - 1])) first--
    return first
}

/** Last row of the soft-wrap chain containing [row]. */
private fun chainEnd(screen: List<List<TermCell>>, row: Int): Int {
    var last = row
    while (last < screen.lastIndex && last - row < MAX_COMMAND_ROWS && isFilledToEdge(screen[last])) last++
    return last
}

/** Whether the row runs to its last column — the only available hint that the line wrapped. */
private fun isFilledToEdge(row: List<TermCell>): Boolean = row.lastOrNull()?.text?.isNotBlank() == true

/** Index of the last non-blank column, or -1 for a blank row. */
private fun lastUsedColumn(row: List<TermCell>): Int {
    for (c in row.indices.reversed()) if (row[c].text.isNotBlank()) return c
    return -1
}
