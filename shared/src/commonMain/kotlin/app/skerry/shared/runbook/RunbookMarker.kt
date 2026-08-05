package app.skerry.shared.runbook

import app.skerry.shared.terminal.STEP_MARK_OSC
import app.skerry.shared.terminal.TerminalStepMark

/**
 * How a runbook step learns whether it succeeded.
 *
 * A step runs in the user's own interactive shell — not over an exec channel — so its `cd`, exported
 * variables and `sudo` credentials carry over to the next step, and the human watches it happen.
 * The price is that a PTY reports no exit status: the shell only writes bytes. So the step is sent
 * as `<opening probe>; <command>; <closing probe>`, and both probes write an escape sequence the
 * terminal parses and never draws (OSC [STEP_MARK_OSC], see [TerminalStepMark]): the opening one
 * says where the step's output starts, the closing one carries `$?`.
 *
 * The status used to be a printed line, which left the marker and a blank row on screen for every
 * step. Nothing of the protocol is drawn now: the marks themselves are escape sequences, and the
 * echo of the probes — which a PTY produces the moment the line is typed — is hidden by the terminal
 * ([app.skerry.shared.terminal.TerminalEchoFilter], fed from [echoFragments]). What the operator
 * sees is their own command after the prompt, and nothing else.
 *
 * The token is spliced into the format rather than passed as an argument: the echo carries the
 * *characters* `\033`, never the escape byte, so it can never be mistaken for a real mark. The
 * status a mark carries is the host's own word — see [TerminalStepMark].
 *
 * Assumes a POSIX-ish shell (`sh`/`bash`/`zsh`/`ash`): `$?` and a `printf` builtin that understands
 * `\033`/`\a`. Under `fish` or PowerShell no mark ever appears and the step stays running until the
 * user stops the run.
 */
object RunbookMarker {

    /**
     * Marker token for step [stepIndex] of run [runId]. Reduced to `[a-z0-9_]` so it needs no shell
     * quoting and cannot contain the `;` that separates the fields of the mark; the run id makes it
     * unique across runs, the index across steps, so a mark left over by an abandoned step is never
     * mistaken for this one's.
     */
    fun token(runId: String, stepIndex: Int): String =
        PREFIX + runId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(ID_CHARS) + "_" + stepIndex

    /**
     * The probe that opens a step: everything printed after it is the step's output. It carries the
     * token like the closing one does, so a window can only be opened and closed by the same step.
     */
    fun startProbe(token: String): String = "printf '$OSC;$token;$BEL'"

    /** The probe that closes a step: reports `$?` under [token]. */
    fun probe(token: String): String = "printf '$OSC;$token;%s$BEL' \"\$?\""

    /**
     * The line actually sent to the terminal for a step: [command] between the two probes, so `$?`
     * is the command's own status. A multi-line command keeps its shape and gets the closing probe
     * on a new line (its `$?` is then the last line's, as when pasting a script); a command already
     * ending in `;` or `&` isn't given a second separator.
     *
     * The opening probe always leads, on the first line: it stands before anything the step could
     * do to what follows it.
     */
    fun probeLine(command: String, token: String): String =
        opening(token) + command.trimEnd() + closing(command, token)

    /**
     * The parts of [probeLine] that are protocol rather than the operator's command — what the
     * terminal hides from the echo of the line ([app.skerry.shared.terminal.TerminalEchoFilter]).
     * Built here, from the same pieces, so the two can never disagree about where the command ends.
     *
     * Line breaks are left out of the fragments on purpose: a PTY echoes one as CR LF and the shell
     * prints its continuation prompt (`> `) before the next line, so a fragment spanning a break
     * could never match. Each fragment is then matched inside one echoed line, wherever in the
     * stream it turns up — for a multi-line step the closing one arrives after the shell has already
     * started running the first line. What stays visible is the shell's own prompt, not the probes.
     */
    fun echoFragments(command: String, token: String): List<String> =
        listOf(opening(token), closing(command, token).trimStart('\n'))

    private fun opening(token: String): String = "${startProbe(token)}; "

    /** What follows the command: the separator its shape requires, then the closing probe. */
    private fun closing(command: String, token: String): String {
        val probe = probe(token)
        val trimmed = command.trimEnd()
        if (trimmed.isEmpty()) return probe
        // A trailing backslash continues the line and swallows whichever separator comes next, so
        // both `cmd \; probe` and `cmd \` + newline + probe hand the probe to `cmd` as arguments and
        // no mark is ever emitted. A blank line is what ends such a command: the continuation joins
        // with the empty line, and the newline after that terminates it. Checked before the
        // multi-line branch below, because what matters is how the text ends, not whether it already
        // spans lines.
        if (endsInLineContinuation(trimmed)) return "\n\n$probe"
        if (trimmed.contains('\n')) return "\n$probe"
        // Anything the probe cannot legally follow on the same line goes onto the next one, exactly
        // as if the step had been pasted as a two-line script. Both cases are silent killers when
        // got wrong: after a trailing comment the probe is swallowed and never runs, and after a
        // dangling `&&`/`;;` the appended `;` is a syntax error that makes the shell drop the WHOLE
        // line — in both cases no mark is ever emitted and the run waits forever.
        if (endsInComment(trimmed) || endsInDanglingOperator(trimmed)) return "\n$probe"
        val last = trimmed.last()
        return if (last == ';' || last == '&') " $probe" else "; $probe"
    }

    /**
     * Whether [line] ends inside a `#` comment. `#` only opens one at the start of a word and
     * outside quotes, so `echo a#b` and `grep "#tag"` are ordinary arguments. Backslash escapes and
     * both quote styles are tracked; `$'…'` and here-documents are not — a step needing those is
     * multi-line anyway and takes the branch above.
     */
    private fun endsInComment(line: String): Boolean {
        var single = false
        var double = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\\' && !single -> i++ // the next character is literal
                c == '\'' && !double -> single = !single
                c == '"' && !single -> double = !double
                c == '#' && !single && !double && (i == 0 || line[i - 1].isWhitespace()) -> return true
            }
            i++
        }
        return false
    }

    /** Whether [line] ends on an operator that still expects a command after it. */
    private fun endsInDanglingOperator(line: String): Boolean =
        DANGLING_OPERATORS.any { line.endsWith(it) }

    /**
     * Whether [text] ends in an unescaped `\`, which continues the line onto the next one. Only an
     * odd number of trailing backslashes does: `cmd \\` ends in a literal backslash and is a
     * complete command.
     */
    private fun endsInLineContinuation(text: String): Boolean {
        var backslashes = 0
        var at = text.length - 1
        while (at >= 0 && text[at] == '\\') {
            backslashes++
            at--
        }
        return backslashes % 2 == 1
    }

    private val DANGLING_OPERATORS = listOf("&&", "||", "|", ";;")

    // Written as printf's own escapes (`\033`, `\a`), not as bytes: an invisible control character
    // in a Kotlin source file is unreadable in a diff and silently lost on edit.
    private const val OSC = "\\033]$STEP_MARK_OSC"
    private const val BEL = "\\a"

    private const val PREFIX = "sk_"
    // Short on purpose: the token rides in the probe the PTY echoes onto the screen, so every
    // character of it is noise the user reads. 8 hex characters of a UUID already separate one
    // run's marks from another's.
    private const val ID_CHARS = 8
}
