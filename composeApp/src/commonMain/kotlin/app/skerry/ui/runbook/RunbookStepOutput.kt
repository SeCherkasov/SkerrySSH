package app.skerry.ui.runbook

/**
 * How much of one step's output the run keeps, in characters. Enough for a screenful of context on
 * the run screen, far short of a build log — the terminal underneath is the full record, and this
 * copy lives in memory for as long as the run does.
 */
const val RUNBOOK_STEP_OUTPUT_LIMIT = 20_000

/**
 * The output of the step marked by [token], read out of the terminal tail [text]: everything the
 * command printed between the echo of the line the runner typed and the marker that ended it.
 *
 * `null` where the step has not finished — no marker yet — or where the echo has already scrolled
 * out of the tail window. In the second case the start of the block is genuinely unknown, and
 * guessing would hand the previous step's output to this one.
 *
 * Long output is cut to its last [RUNBOOK_STEP_OUTPUT_LIMIT] characters at a line boundary: the end
 * of a noisy step is the part worth reading, and half a first line reads as corruption.
 */
fun runbookStepOutput(text: String, token: String): String? {
    val markerAt = text.lastIndexOf("$token:")
    if (markerAt < 0) return null
    // The echo carries the token as a quoted argument of the probe, never followed by ':' — that is
    // what tells the two apart (see RunbookMarker).
    val echoAt = text.lastIndexOf(token, markerAt - 1)
    if (echoAt < 0) return null
    val newline = text.indexOf('\n', echoAt)
    if (newline < 0 || newline + 1 > markerAt) return ""
    val block = text.substring(newline + 1, markerAt).trim('\n')
    if (block.length <= RUNBOOK_STEP_OUTPUT_LIMIT) return block
    val tail = block.takeLast(RUNBOOK_STEP_OUTPUT_LIMIT)
    val firstBreak = tail.indexOf('\n')
    return if (firstBreak < 0) tail else tail.substring(firstBreak + 1)
}
