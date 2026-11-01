package app.skerry.shared.terminal

const val FAILED_COMMAND_INPUT_CHARS = 2_048
const val FAILED_COMMAND_OUTPUT_CHARS = 4_000
const val FAILED_COMMAND_OUTPUT_ROWS = 100

/** Detached from moving scrollback anchors. A missing capture is null, never another command's text. */
data class FailedCommandSnapshot(
    val command: String,
    val output: String,
    val exitCode: Int,
    val outputTruncated: Boolean,
)

/** Only completed failures with all retained, ordered OSC 133 bounds can become AI context. */
fun failedCommandSnapshot(screen: List<List<TermCell>>, mark: ShellCommandMark): FailedCommandSnapshot? {
    val exit = mark.exitCode?.takeIf { it > 0 } ?: return null
    if (mark.failureCaptured) return mark.failureSnapshot
    val prompt = TerminalPos(mark.promptRow, mark.promptCol)
    val input = TerminalPos(mark.inputRow ?: return null, mark.inputCol ?: return null)
    val output = TerminalPos(mark.outputRow ?: return null, mark.outputCol ?: return null)
    val end = TerminalPos(mark.endRow ?: return null, mark.endCol ?: return null)
    if (!retainedOrderedBounds(screen, prompt, input, output, end)) return null
    val command = boundedRange(screen, input, output, FAILED_COMMAND_INPUT_CHARS)
    if (command.truncated || command.text.isBlank()) return null
    val tail = boundedRange(screen, output, end, FAILED_COMMAND_OUTPUT_CHARS)
    return FailedCommandSnapshot(command.text.trim(), tail.text, exit, tail.truncated)
}

private fun retainedOrderedBounds(
    screen: List<List<TermCell>>,
    prompt: TerminalPos,
    input: TerminalPos,
    output: TerminalPos,
    end: TerminalPos,
): Boolean = listOf(prompt, input, output, end).all { it.row in screen.indices && it.col in 0..screen[it.row].size } &&
    prompt <= input && input < output && output <= end

private class BoundedRange(val text: String, val truncated: Boolean)

/**
 * Walk only the bounded tail, then use the selection extractor for soft wraps and SGR 8 masking.
 * Keep whole logical lines: cutting a wrapped PASSWORD= line could leave its value without its key.
 * An oversized logical line is omitted, not split. Work and allocation are independent of scrollback.
 */
private fun boundedRange(
    screen: List<List<TermCell>>,
    start: TerminalPos,
    end: TerminalPos,
    limit: Int,
): BoundedRange {
    if (start == end) return BoundedRange("", false)
    val last = if (end.col == 0 && end.row > start.row) end.row - 1 else end.row
    var firstComplete = last + 1
    var cost = 0
    var rows = 0
    var index = last
    while (index >= start.row && rows < FAILED_COMMAND_OUTPUT_ROWS) {
        val row = screen[index]
        val from = if (index == start.row) start.col else 0
        val until = if (index == end.row) end.col else row.size
        val rowCost = rangeRowCost(row, from, until, followed = index < last, limit = limit - cost)
        if (rowCost > limit - cost) break
        cost += rowCost
        rows++
        if (index == start.row || !screen[index - 1].wrapsToNextRow()) firstComplete = index
        index--
    }
    return extractBoundedRange(screen, start, end, firstComplete, last)
}

private fun extractBoundedRange(
    screen: List<List<TermCell>>,
    start: TerminalPos,
    end: TerminalPos,
    first: Int,
    last: Int,
): BoundedRange {
    val truncated = first > start.row
    if (first > last) return BoundedRange("", truncated)
    val from = TerminalPos(first, if (first == start.row) start.col else 0)
    val until = TerminalPos(last, if (last == end.row) end.col else screen[last].size)
    val text = TerminalSelection(from, until).extract(screen, maskHidden = true).trimEnd('\n')
    return BoundedRange(text, truncated)
}

private fun rangeRowCost(row: List<TermCell>, from: Int, until: Int, followed: Boolean, limit: Int): Int {
    val joinsNext = followed && row.wrapsToNextRow()
    val separator = if (followed && !joinsNext) 1 else 0
    return separator + boundedRowCost(row, from, until, trimEnd = !joinsNext, limit = limit)
}

private fun boundedRowCost(row: List<TermCell>, from: Int, until: Int, trimEnd: Boolean, limit: Int): Int {
    var trimTail = trimEnd
    var cost = 0
    for (column in until - 1 downTo from) {
        val cell = row[column]
        val length = when {
            cell.style.hidden -> 1
            trimTail -> cell.text.indexOfLast { !it.isWhitespace() } + 1
            else -> cell.text.length
        }
        if (length > 0) trimTail = false
        cost += length
        if (cost > limit) break
    }
    return cost
}
