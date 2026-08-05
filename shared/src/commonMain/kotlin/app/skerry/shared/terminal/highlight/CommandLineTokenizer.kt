package app.skerry.shared.terminal.highlight

/** Beyond this a "command line" is pasted data, not something worth tokenizing per keystroke. */
const val MAX_HIGHLIGHT_LENGTH = 4096

/** Characters that end a word and start an operator. Braces are left out — `{a,b}` is expansion, not control. */
private const val OPERATOR_CHARS = "|&;<>()"

/** Quotes that open a string literal. Backquote is treated as one too: close enough, and it never nests here. */
private const val QUOTE_CHARS = "\"'`"

/** Single-character variables the shell defines itself: `$?`, `$$`, `$!`, `$#`, `$@`, `$*`, `$-`, `$0`..`$9`. */
private const val SPECIAL_VARS = "?$!#@*-"

/**
 * Splits a shell command line into highlight spans (fish-style: command green, options cyan, quoted
 * strings yellow, paths blue, operators magenta).
 *
 * One left-to-right scan, no regex — this runs on every screen snapshot while the user types.
 * Returned spans are sorted, non-overlapping and within `[0, line.length)`.
 *
 * Deliberately not a shell parser: quoted text is one span (a `$VAR` inside `"…"` is not resolved),
 * `2>&1` reads as digits plus an operator, and an unknown command is simply left uncolored — see
 * [CommandVocabulary] for why nothing is ever marked as wrong.
 */
fun tokenizeCommandLine(line: String, vocabulary: CommandVocabulary): List<HighlightSpan> {
    val text = if (line.length > MAX_HIGHLIGHT_LENGTH) line.substring(0, MAX_HIGHLIGHT_LENGTH) else line
    return Tokenizer(text, vocabulary).run()
}

private class Tokenizer(private val text: String, private val vocabulary: CommandVocabulary) {

    private val out = ArrayList<HighlightSpan>()

    /** Whether the next word sits where a command name goes (line start, after `|`, `;`, `&&`, `sudo`). */
    private var commandPosition = true

    /** The command whose subcommand the next word could be, or `null`. */
    private var pendingCommand: String? = null

    fun run(): List<HighlightSpan> {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            i = when {
                ch == ' ' || ch == '\t' -> i + 1
                ch == '#' && startsToken(i) -> {
                    add(i, text.length, HighlightKind.Comment)
                    text.length
                }
                ch in OPERATOR_CHARS -> operator(i)
                else -> word(i)
            }
        }
        return out
    }

    private fun add(start: Int, endExclusive: Int, kind: HighlightKind) {
        if (start < endExclusive) out.add(HighlightSpan(start, endExclusive, kind))
    }

    private fun startsToken(at: Int): Boolean = at == 0 || text[at - 1].isWhitespace()

    /**
     * Consumes a run of operator characters as one span (`&&`, `||`, `>>`). A pure redirect keeps the
     * argument position — the word after `>` is a file, not a command; everything else (`|`, `;`, `&`,
     * `(`) starts a new command.
     */
    private fun operator(start: Int): Int {
        var end = start
        while (end < text.length && text[end] in OPERATOR_CHARS) end++
        add(start, end, HighlightKind.Operator)
        val redirectOnly = (start until end).all { text[it] == '<' || text[it] == '>' }
        commandPosition = !redirectOnly
        pendingCommand = null
        return end
    }

    /**
     * Consumes one word — everything up to whitespace or an operator, with quoted sections and
     * variables taken as they come. A word made only of plain characters is classified as a whole
     * (command / option / path); one carrying quotes or variables keeps those inner spans, plus an
     * option prefix when it starts with a dash (`--message="…"`).
     */
    private fun word(start: Int): Int {
        val inner = ArrayList<HighlightSpan>()
        var i = start
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == ' ' || ch == '\t' || ch in OPERATOR_CHARS -> return finishWord(start, i, inner)
                ch in QUOTE_CHARS -> {
                    val end = quoteEnd(i)
                    inner.add(HighlightSpan(i, end, HighlightKind.StringLit))
                    i = end
                }
                ch == '$' -> {
                    val end = variableEnd(i)
                    if (end > i) inner.add(HighlightSpan(i, end, HighlightKind.Variable))
                    i = if (end > i) end else i + 1
                }
                else -> i++
            }
        }
        return finishWord(start, i, inner)
    }

    /** End (exclusive) of the string literal opened at [start]; an unterminated quote runs to end of line. */
    private fun quoteEnd(start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            // A backslash escapes inside "…" and `…`, but is literal inside '…' (POSIX).
            if (quote != '\'' && text[i] == '\\' && i + 1 < text.length) { i += 2; continue }
            if (text[i] == quote) return i + 1
            i++
        }
        return text.length
    }

    /** End (exclusive) of the variable reference at [start] (`$NAME`, `${…}`, `$1`, `$?`), or [start] if it isn't one. */
    private fun variableEnd(start: Int): Int {
        val next = text.getOrNull(start + 1) ?: return start
        if (next == '{') {
            var i = start + 2
            while (i < text.length && text[i] != '}') i++
            return if (i < text.length) i + 1 else text.length
        }
        if (next in SPECIAL_VARS) return start + 2
        if (!next.isLetterOrDigit() && next != '_') return start
        var i = start + 1
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return i
    }

    private fun finishWord(start: Int, end: Int, inner: List<HighlightSpan>): Int {
        if (end <= start) return end + 1
        if (inner.isEmpty()) classifyPlainWord(start, end) else classifyMixedWord(start, inner)
        return end
    }

    private fun classifyPlainWord(start: Int, end: Int) {
        val word = text.substring(start, end)
        val assignment = assignmentEnd(word)
        when {
            // `FOO=1 make build`: the assignment is a prefix of the command, not the command itself.
            commandPosition && assignment > 0 -> {
                add(start, start + assignment, HighlightKind.Variable)
                if (isPathWord(word.substring(assignment))) {
                    add(start + assignment, end, HighlightKind.PathLit)
                }
                return // command position and pending command both survive an assignment prefix
            }
            isOptionWord(word) -> {
                add(start, end, HighlightKind.Option)
                return // an option never fills the command or argument slot
            }
            // A path is checked before the vocabulary: `/usr/bin/git` is a path that happens to end in a name.
            isPathWord(word) -> add(start, end, HighlightKind.PathLit)
            commandPosition && vocabulary.isCommand(word) -> {
                add(start, end, HighlightKind.Command)
                pendingCommand = word
                commandPosition = word in COMMAND_PREFIXES
                return
            }
            pendingCommand?.let { vocabulary.isSubcommand(it, word) } == true ->
                add(start, end, HighlightKind.Subcommand)
        }
        commandPosition = false
        pendingCommand = null
    }

    private fun classifyMixedWord(start: Int, inner: List<HighlightSpan>) {
        val first = inner.first()
        if (first.start > start && text[start] == '-') {
            add(start, first.start, HighlightKind.Option)
            out.addAll(inner)
            return // still an option: `--message="fix"` doesn't consume the command slot
        }
        out.addAll(inner)
        commandPosition = false
        pendingCommand = null
    }

    /** Length of a `NAME=` prefix (including the `=`), or 0 when [word] isn't an assignment. */
    private fun assignmentEnd(word: String): Int {
        val eq = word.indexOf('=')
        if (eq <= 0) return 0
        if (!word[0].isLetter() && word[0] != '_') return 0
        for (i in 0 until eq) if (!word[i].isLetterOrDigit() && word[i] != '_') return 0
        return eq + 1
    }

    private fun isOptionWord(word: String): Boolean =
        word.length > 1 && word[0] == '-' && (word[1].isLetter() || word[1] == '-')

    /** A word that names a filesystem location: absolute, home- or dot-relative, or simply containing a separator. */
    private fun isPathWord(word: String): Boolean = when {
        word.isEmpty() -> false
        word == "~" || word.startsWith("~/") -> true
        word[0] == '-' -> false
        else -> word.contains('/')
    }
}
