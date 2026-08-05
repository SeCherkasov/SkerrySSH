package app.skerry.shared.terminal

/**
 * Private OSC code that carries a runbook step's boundaries out of band: `OSC 8375 ; token ; code
 * BEL`. 8375 is `S`,`K` in ASCII decimal — no terminal assigns it, and a session shared with
 * another client simply ignores what it doesn't know.
 *
 * Two forms, both carrying the step's token:
 * - `OSC 8375 ; <token> ; BEL` — *the step's output starts here*, emitted before the command runs;
 * - `OSC 8375 ; <token> ; <exit code> BEL` — the step finished with that status.
 *
 * The terminal acts on neither unless the client has declared that step
 * ([TerminalEmulator.expectStep]): an ordinary session parks nothing, and a host repeating a token
 * it read off the screen cannot close a capture window this client never opened.
 *
 * Neither form draws anything, which is the whole point: the status used to be a printed line and
 * left two rows of protocol on screen per step.
 *
 * What the mark says is what the host's shell said. A compromised host can emit a status of its own
 * choosing — as it could forge the printed marker before it — so a step's exit code is evidence
 * about the host, not proof against it. Runbooks are not a security boundary over a hostile host.
 */
const val STEP_MARK_OSC = 8375

/**
 * How much of one step's output the terminal hands over, in characters. Enough for a screenful of
 * context on the run screen, far short of a build log — the buffer underneath is the full record.
 */
const val STEP_MARK_OUTPUT_LIMIT = 20_000

/** Longest token accepted in a step mark; an untrusted server must not park a big string here. */
const val MAX_STEP_MARK_TOKEN = 64

/**
 * Longest status accepted in a step mark. A shell's `$?` is 0..255; anything past nine digits is not
 * one, and the cap keeps a hostile host from making the terminal scan a megabyte of digits per mark.
 */
const val MAX_STEP_MARK_STATUS = 9

/**
 * Reported as the exit code when the closing mark carried something that is not a status. The mark
 * is still the step's own, and its probe has already run — dropping it would leave the run waiting
 * for a report that can never come again, so the step ends, and it ends as a failure.
 */
const val UNREADABLE_STATUS = -1

/**
 * A finished step as the terminal reports it: which step ([token]), what the shell said ([exitCode])
 * and what the command printed between the two marks ([output], capped at [STEP_MARK_OUTPUT_LIMIT]).
 *
 * [output] is `null` when the capture was lost rather than empty — the buffer it pointed into was
 * rebuilt mid-step (a resize, `clear`, RIS) or the step ran on the alt screen. An empty string means
 * the command really did print nothing, and the run screen says the two differently: "nothing
 * printed" is a claim about the command, and it must not be made on the terminal's behalf.
 */
data class TerminalStepMark(val token: String, val exitCode: Int, val output: String?)

/**
 * Text of rows [first]..[last] of the buffer — the step's output, read straight off the screen at
 * the moment the closing mark arrived. [firstCol] is where the opening mark left the cursor and
 * [lastCol] where the closing one found it, so the fragment starts and ends exactly at the command's
 * own bytes rather than at a row boundary.
 *
 * Soft-wrapped rows are joined back into one line ([TermRow.wrapped]), trailing blanks go, and the
 * result is cut to its last [limit] characters *at a row boundary*: the end of a noisy step is the
 * part worth reading, and half a first line reads as corruption. Rows are walked from the end for
 * that reason — a step that printed a hundred thousand lines costs the kept tail, not the whole run.
 */
internal fun stepMarkOutput(
    first: Int,
    firstCol: Int,
    last: Int,
    lastCol: Int,
    limit: Int = STEP_MARK_OUTPUT_LIMIT,
    rowAt: (Int) -> TermRow,
): String {
    if (last < first) return ""
    val kept = ArrayDeque<StepMarkRow>()
    var length = 0
    var index = last
    while (index >= first) {
        val row = rowAt(index)
        val from = if (index == first) firstCol.coerceIn(0, row.size) else 0
        val until = if (index == last) lastCol.coerceIn(from, row.size) else row.size
        // A wrapped row continues into the next one, so its trailing blanks are part of the line —
        // trimming them would glue the halves of a word together.
        val raw = buildString(until - from) { for (c in from until until) append(row[c].text) }
        val text = if (row.wrapped) raw else raw.trimEnd()
        val cost = text.length + if (kept.isEmpty()) 0 else 1
        if (length + cost > limit && kept.isNotEmpty()) break
        kept.addFirst(StepMarkRow(text, row.wrapped))
        length += cost
        index--
    }
    val text = buildString(length) {
        kept.forEachIndexed { at, row ->
            append(row.text)
            if (at < kept.size - 1 && !row.wrapped) append('\n')
        }
    }.trim('\n')
    // The row-boundary cut above cannot drop the last row — a step whose whole output is one row is
    // still worth showing. That row is at most a screen wide, so this only bites on a terminal wider
    // than the limit; the cap is what the caller was promised, so it holds even there.
    return if (text.length <= limit) text else text.takeLast(limit)
}

/** One row on its way into a step's output: its text and whether it continues into the next row. */
private class StepMarkRow(val text: String, val wrapped: Boolean)
