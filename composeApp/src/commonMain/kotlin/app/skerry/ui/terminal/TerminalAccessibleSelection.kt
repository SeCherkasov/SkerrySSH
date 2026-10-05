package app.skerry.ui.terminal

import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.shared.terminal.lineSelectionAt
import app.skerry.shared.terminal.wrapsToNextRow

/** Clipboard and PRIMARY must contain only content that was visible in the terminal. */
internal fun TerminalScreenState.selectedCopyText(): String? = selection
    ?.takeIf { !it.isEmpty }
    ?.extract(screen, maskHidden = true)
    ?.takeIf { it.isNotEmpty() }

/** Bound work on the UI thread and reject concealed cells before interpreting a selected path. */
internal fun TerminalScreenState.selectionIsSafePathCandidate(): Boolean {
    val selected = selection?.takeIf { !it.isEmpty } ?: return false
    var cells = 0
    var textUnits = 0
    for (rowIndex in selected.start.row.coerceAtLeast(0)..selected.end.row.coerceAtMost(screen.lastIndex)) {
        val row = screen.getOrNull(rowIndex) ?: continue
        val from = (if (rowIndex == selected.start.row) selected.start.col else 0).coerceIn(0, row.size)
        val to = (if (rowIndex == selected.end.row) selected.end.col else row.size).coerceIn(from, row.size)
        cells += to - from
        if (cells > MAX_PATH_LENGTH * 2) return false
        for (col in from until to) {
            if (row[col].style.hidden) return false
            textUnits += row[col].text.length
            if (textUnits > MAX_PATH_LENGTH * 2) return false
        }
    }
    return true
}

/** Select the nearest visible word at the terminal cursor, without requiring a pointer. */
internal fun TerminalScreenState.selectCursorWord(): Boolean {
    val row = cursorRow.coerceIn(0, screen.lastIndex.coerceAtLeast(0))
    val cells = screen.getOrNull(row) ?: return false
    val col = cursorCol.coerceIn(0, cells.lastIndex.coerceAtLeast(0))
    val nearest = (col downTo 0).firstOrNull { !cells[it].text.isBlank() } ?: return false
    selectWordAt(TerminalPos(row, nearest))
    return selectedText() != null
}

/** Select the row at the terminal cursor. */
internal fun TerminalScreenState.selectCursorLine(): Boolean {
    selectLineAt(TerminalPos(cursorRow, cursorCol))
    return selectedText() != null
}

/** Walk backwards through nonblank output rows, starting before the current selection. */
internal fun TerminalScreenState.selectPreviousOutputLine(): Boolean =
    selectOutputLineFrom(selection?.start?.row?.minus(1) ?: (cursorRow - 1))

/** Start from the newest output row each time, for a screen-reader action. */
internal fun TerminalScreenState.selectLastOutputLine(): Boolean =
    selectOutputLineFrom(cursorRow - 1)

private fun TerminalScreenState.selectOutputLineFrom(first: Int): Boolean {
    for (row in first.coerceAtMost(screen.lastIndex) downTo 0) {
        val candidate = lineSelectionAt(screen, TerminalPos(row, 0))
        if (candidate.extract(screen).isNotBlank()) {
            selectLineAt(TerminalPos(row, 0))
            return true
        }
    }
    return false
}

internal data class SelectedSpeechChunk(val text: String, val nextOffset: Int?)

/** Read one bounded part of a selection without allocating the entire scrollback selection. */
internal fun TerminalScreenState.selectedSpeechChunk(startOffset: Int = 0, limit: Int = 160): SelectedSpeechChunk? {
    val selected = selection?.takeIf { !it.isEmpty } ?: return null
    val collector = SpeechChunkCollector(startOffset, limit)
    for (rowIndex in selected.start.row.coerceAtLeast(0)..selected.end.row.coerceAtMost(screen.lastIndex)) {
        val row = screen.getOrNull(rowIndex) ?: continue
        val segment = speechRowSegment(selected, row, rowIndex)
        for (col in segment.from until segment.to) {
            if (!collector.append(row[col].spokenText())) return collector.more()
        }
        if (segment.hardBreak && !collector.append(" ")) return collector.more()
    }
    return collector.done()
}

private data class SpeechRowSegment(val from: Int, val to: Int, val hardBreak: Boolean)

private fun speechRowSegment(selection: TerminalSelection, row: List<TermCell>, index: Int): SpeechRowSegment {
    val from = (if (index == selection.start.row) selection.start.col else 0).coerceIn(0, row.size)
    var to = (if (index == selection.end.row) selection.end.col else row.size).coerceIn(from, row.size)
    val wrapped = index < selection.end.row && row.wrapsToNextRow()
    if (!wrapped) while (to > from && row[to - 1].text.isBlank() && !row[to - 1].style.hidden) to--
    return SpeechRowSegment(from, to, hardBreak = index < selection.end.row && !wrapped)
}

private fun TermCell.spokenText(): String = if (style.hidden && text.isNotEmpty()) "•" else text

private class SpeechChunkCollector(private val startOffset: Int, private val limit: Int) {
    private var itemOffset = 0
    private val preview = StringBuilder()

    fun append(value: String): Boolean {
        if (value.isEmpty()) return true
        if (itemOffset < startOffset || (preview.isEmpty() && value.isBlank())) {
            itemOffset++
            return true
        }
        if (preview.length + value.length > limit) {
            if (!value.isBlank()) return false
        } else preview.append(value)
        itemOffset++
        return true
    }

    fun more(): SelectedSpeechChunk = SelectedSpeechChunk(preview.toString().trim(), itemOffset)
    fun done(): SelectedSpeechChunk? = preview.toString().trim().takeIf { it.isNotEmpty() }
        ?.let { SelectedSpeechChunk(it, null) }
}

/** Initial spoken text for callers that do not need chunk navigation. */
internal fun TerminalScreenState.selectedSpeechPreview(limit: Int = 160): String? =
    selectedSpeechChunk(limit = limit)?.text
