package app.skerry.shared.runbook

/**
 * How a runbook step learns whether it succeeded.
 *
 * A step runs in the user's own interactive shell — not over an exec channel — so its `cd`, exported
 * variables and `sudo` credentials carry over to the next step, and the human watches it happen.
 * The price is that a PTY reports no exit status: the shell only writes bytes. So the step is sent
 * as `<command>; <probe>`, where the probe prints a one-off marker carrying `$?`, and the runner
 * reads the code back out of the terminal buffer ([exitCodeIn]).
 *
 * The probe is `printf` with the token passed as an *argument* (`%s`), never spliced into the
 * format: the PTY echoes the line the moment it is typed, long before the command has run, and that
 * echo must not read as a result. Passing the token through `%s` means the echoed text never
 * contains `token:` at all — only the printed output does. [exitCodeIn] is what proves it.
 *
 * Assumes a POSIX-ish shell (`sh`/`bash`/`zsh`/`ash`): `$?` and a `printf` builtin. Under `fish` or
 * PowerShell no marker ever appears and the step stays running until the user stops the run.
 */
object RunbookMarker {

    /**
     * Marker token for step [stepIndex] of run [runId]. Reduced to `[a-z0-9_]` so it needs no shell
     * quoting and can be searched literally; the run id makes it unique across runs, the index
     * across steps, so a marker left in the scrollback by an earlier step is never mistaken for
     * this one's.
     */
    fun token(runId: String, stepIndex: Int): String =
        PREFIX + runId.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(ID_CHARS) +
            "_" + stepIndex + "__"

    /** The probe command alone: prints `<token>:<exit code>` on a line of its own. */
    fun probe(token: String): String = "printf '\\n%s:%s\\n' '$token' \"\$?\""

    /**
     * The line actually sent to the terminal for a step: [command] followed by the probe, so `$?`
     * is the command's own status. A multi-line command keeps its shape and gets the probe on a new
     * line (its `$?` is then the last line's, as when pasting a script); a command already ending in
     * `;` or `&` isn't given a second separator.
     */
    fun probeLine(command: String, token: String): String {
        val probe = probe(token)
        val trimmed = command.trimEnd()
        if (trimmed.isEmpty()) return probe
        if (trimmed.contains('\n')) return "$trimmed\n$probe"
        // Anything the probe cannot legally follow on the same line goes onto the next one, exactly
        // as if the step had been pasted as a two-line script. Both cases are silent killers when
        // got wrong: after a trailing comment the probe is swallowed and never runs, and after a
        // dangling `&&`/`;;` the appended `;` is a syntax error that makes the shell drop the WHOLE
        // line — in both cases no marker is ever printed and the run waits forever.
        if (endsInComment(trimmed) || endsInDanglingOperator(trimmed)) return "$trimmed\n$probe"
        val last = trimmed.last()
        return if (last == ';' || last == '&') "$trimmed $probe" else "$trimmed; $probe"
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
     * Exit code printed by [token]'s probe in [text] (terminal buffer), or `null` if the step hasn't
     * finished. The last marker wins — a repeated token can only come from the same step, and the
     * newest print is the current truth. Only ASCII digits count: a value that isn't a plain number
     * (or overflows an `Int`) is treated as "no answer yet" rather than as a fabricated status.
     */
    fun exitCodeIn(text: String, token: String): Int? {
        val needle = "$token:"
        var found: Int? = null
        var at = text.indexOf(needle)
        while (at >= 0) {
            val start = at + needle.length
            var end = start
            while (end < text.length && text[end] in '0'..'9') end++
            if (end > start) text.substring(start, end).toIntOrNull()?.let { found = it }
            at = text.indexOf(needle, at + needle.length)
        }
        return found
    }

    private val DANGLING_OPERATORS = listOf("&&", "||", "|", ";;")

    private const val PREFIX = "__skerry_rb_"
    // Short on purpose: the printed marker must fit one terminal row, or a wrap would split it in
    // two and the runner would never see the step finish. 8 hex characters of a UUID are already
    // more than enough to separate one run's markers from another's.
    private const val ID_CHARS = 8
}
