package app.skerry.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.skerry.shared.terminal.ShellCommandMark
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_marks_strip
import org.jetbrains.compose.resources.stringResource

/**
 * The command-mark gutter: a strip in the terminal's left padding ([width]), one marker per
 * command the shell bracketed (OSC 133), tinted by how it ended — green for exit 0, red for any
 * other exit, dim for a command still running or one that never reported. The end state is not
 * colour alone (SC 1.4.1): a finished-0 command draws a capsule, a failure a square, a running
 * or unreported one a hollow bar. A tap selects that command's output; the copy chord and the
 * context menu then work on it as on any selection.
 *
 * A separate overlay rather than another pass inside the text canvas: the text canvas draws after
 * its own padding, and this strip lives in that padding — no geometry of the grid changes, so the
 * terminal's columns stay exactly what they were before the shell had integration. [topInset] is
 * the same top padding the text canvas applies: row 0 draws below it, not at the screen edge.
 * The strip is the pointer path; the keyboard chords and the terminal node's custom accessibility
 * actions (jump + select) are the screen-reader path. The strip names itself so TalkBack does not
 * walk an anonymous picture.
 */
@Composable
internal fun CommandMarkGutter(
    state: TerminalScreenState,
    cellHeightPx: Float,
    scrollPx: () -> Float,
    colors: MarkColors,
    width: Dp,
    topInset: Dp,
    modifier: Modifier = Modifier,
) {
    // The alternate screen shows a TUI's rows, not the marked ones — markers there would sit on
    // foreign text (the anchors live in primary-buffer coordinates).
    if (state.commandMarks.isEmpty() || state.altScreen) return
    val description = stringResource(Res.string.term_marks_strip)
    Canvas(
        modifier
            .width(width)
            .fillMaxHeight()
            .padding(top = topInset)
            // A marker on a partly scrolled row would spill into the top inset — desktop does
            // not clip by default (the text canvas clips for the same reason).
            .clipToBounds()
            .semantics { contentDescription = description }
            .pointerInput(state, cellHeightPx) {
                detectTapGestures { offset ->
                    // Rounded to the nearest row, not floored: the strip is narrower than the
                    // 24x24 minimum, and half a row of slop is what keeps a tap on the marker's
                    // edge from missing it without grabbing a neighbouring command.
                    val row = ((offset.y + scrollPx() + cellHeightPx / 2) / cellHeightPx).toInt()
                    // A fresh snapshot (gestures run outside recomposition) — a list captured at
                    // first composition would hit-test marks the buffer already shifted off.
                    // Only the marker's own row selects: a tap between commands on a busy screen
                    // must not grab a neighbouring one.
                    state.commandMarks.firstOrNull { it.promptRow == row }?.let(state::selectCommandOutput)
                }
            },
    ) {
        val scroll = scrollPx()
        val firstRow = (scroll / cellHeightPx).toInt()
        val lastRow = ((scroll + size.height) / cellHeightPx).toInt()
        // The marker: a short bar in the middle of the strip, two thirds of a row tall.
        val markWidth = MARK_DP.toPx()
        val barLeft = (size.width - markWidth) / 2f
        val barHeight = cellHeightPx * MARK_ROW_FRACTION
        for (mark in state.commandMarks) {
            if (mark.promptRow < firstRow || mark.promptRow > lastRow) continue
            val top = mark.promptRow * cellHeightPx - scroll + (cellHeightPx - barHeight) / 2f
            when (colors.shape(mark)) {
                // Capsule vs square vs hollow — the end state stays readable without colour.
                MarkerShape.CAPSULE -> drawRoundRect(
                    color = colors.of(mark),
                    topLeft = Offset(barLeft, top),
                    size = Size(markWidth, barHeight),
                    cornerRadius = CornerRadius(markWidth / 2f),
                )
                MarkerShape.SQUARE -> drawRect(
                    color = colors.of(mark),
                    topLeft = Offset(barLeft, top),
                    size = Size(markWidth, barHeight),
                )
                MarkerShape.HOLLOW -> drawRoundRect(
                    color = colors.of(mark),
                    topLeft = Offset(barLeft, top),
                    size = Size(markWidth, barHeight),
                    cornerRadius = CornerRadius(markWidth / 2f),
                    style = Stroke(width = HOLLOW_STROKE_DP.toPx()),
                )
            }
        }
    }
}

/** How a marker is drawn — the shape carries the end state where the hue does (SC 1.4.1). */
internal enum class MarkerShape { CAPSULE, SQUARE, HOLLOW }

/** Marker colours, resolved by the caller from the terminal theme — the strip reads like the ANSI text it marks. */
internal class MarkColors(val ok: Color, val fail: Color, val dim: Color) {
    fun of(mark: ShellCommandMark) = when (mark.exitCode) {
        0 -> ok
        null -> dim
        else -> fail
    }

    /** Capsule = exit 0, square = any other exit, hollow = running or unreported. */
    fun shape(mark: ShellCommandMark) = when (mark.exitCode) {
        0 -> MarkerShape.CAPSULE
        null -> MarkerShape.HOLLOW
        else -> MarkerShape.SQUARE
    }
}

/** The last command that finished with a reported exit code (OSC 133 `D`), or `null` — the runbook/guard/AI hook. */
internal val TerminalScreenState.lastFinishedCommand: ShellCommandMark?
    get() = commandMarks.lastOrNull { it.exitCode != null }

/** The command whose prompt row sits strictly above [row] (nearest first), or `null` — the jump-up target. */
internal fun TerminalScreenState.commandMarkBefore(row: Int): ShellCommandMark? =
    commandMarks.lastOrNull { it.promptRow < row }

/** The command whose prompt row sits strictly below [row] (nearest first), or `null` — the jump-down target. */
internal fun TerminalScreenState.commandMarkAfter(row: Int): ShellCommandMark? =
    commandMarks.firstOrNull { it.promptRow > row }

/**
 * A command's output as a linear selection over the published screen (its C..D anchors; a
 * still-running command extends to the bottom). `null` when the shell gave no output anchor or
 * the output has scrolled out of the buffer.
 */
internal fun TerminalScreenState.commandOutputSelection(mark: ShellCommandMark): TerminalSelection? {
    val outputRow = mark.outputRow ?: return null
    if (outputRow !in screen.indices) return null
    val finishedEnd = mark.endRow
    val endRow = if (finishedEnd != null) {
        finishedEnd.coerceIn(outputRow, screen.lastIndex)
    } else {
        // A still-running command owns the rows down to its newest printed line — the blank
        // rows below are screen it has not used yet, and selecting through them would quote
        // a screen height of empty lines after every gutter tap on a running command.
        var last = screen.lastIndex
        while (last > outputRow && screen[last].all { it.text.isBlank() }) last--
        last
    }
    // A D parked at column 0 (the shell reports on the fresh line after the output's newline)
    // means the output ended WITH the previous line — reading to col 0 of a blank row would
    // copy a trailing break that is the report's, not the command's. Only a real D says that:
    // for a still-running command there is no report row, and the newest printed line is output.
    if (finishedEnd != null && endRow > outputRow && (mark.endCol ?: 0) == 0) {
        return TerminalSelection(TerminalPos(outputRow, mark.outputCol ?: 0), TerminalPos(endRow - 1, screen[endRow - 1].size))
    }
    val endCol = if (finishedEnd != null) mark.endCol ?: 0 else screen[endRow].size
    return TerminalSelection(TerminalPos(outputRow, mark.outputCol ?: 0), TerminalPos(endRow, endCol))
}

/**
 * The last command's exit code paired with ITS OWN output, when the shell reports marks —
 * "explain this" is a different question at 0 and at 1, and a code pasted onto another
 * command's text would say what did not happen. A command that printed nothing is quoted as
 * the bare code, which is the whole truth about it. Without marks this is the state's
 * [TerminalScreenState.lastOutput] heuristic. The header line is model-facing prompt text, not
 * drawn UI, so it stays English.
 */
internal fun TerminalScreenState.lastOutputWithExit(): String? {
    val mark = lastFinishedCommand ?: return lastOutput()
    val output = commandOutputSelection(mark)?.extract(screen).orEmpty()
    val head = "Exit code: ${mark.exitCode}"
    return if (output.isEmpty()) head else "$head\n$output"
}

private val MARK_DP = 3.dp
private const val MARK_ROW_FRACTION = 0.66f
private val HOLLOW_STROKE_DP = 1.5.dp
