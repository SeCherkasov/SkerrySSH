package app.skerry.shared.terminal

import kotlin.concurrent.Volatile

/**
 * Terminal autocomplete engine (fish/zsh-style inline suggestion). Doesn't parse the remote
 * shell — it locally tracks the line the user is TYPING (from bytes sent to the PTY) and offers a
 * ghost completion from command history and a list of common commands/paths.
 *
 * Line tracking is approximate (the client doesn't know the real cursor position): handles
 * printable ASCII/UTF-8, Backspace/Delete, Ctrl-U/Ctrl-C (reset), and Enter (commits the line to
 * history). Control/ESC sequences (arrows, etc.) clear the suggestion but leave the line intact.
 * Sufficient for typing a command from scratch — the common autocomplete case.
 *
 * UI usage: [onUserInput] on each block sent to the session; [suggestionTail] is what to render
 * in gray after the typed text; [acceptSuggestion] returns the bytes to send to accept the
 * suggestion (Tab/→), updating the internal line.
 *
 * Every piece of state here is a value that is replaced, never edited in place, and published
 * through [Volatile]. A terminal reaches the engine from more than one thread — a runbook step
 * advances on its own dispatcher while the user types into the same pane — and a `StringBuilder`
 * being appended to under a reader is how that turns into an exception on the keyboard's thread
 * rather than a line. Two writers can still lose an update to each other; what they cannot do is
 * tear one.
 */
class AutocompleteEngine(
    private val history: CommandHistory = CommandHistory(),
    private val builtins: List<String> = COMMON_COMMANDS,
) {
    @Volatile
    private var line: String = ""

    // Cycle cursor for alternatives (Shift+Tab): index into [candidates]. Reset to 0 on any line
    // change so the best suggestion shows again after a new character.
    @Volatile
    private var cycleIndex = 0

    /** Current typed line (for tests/diagnostics). */
    val currentLine: String get() = line

    /**
     * Whether the tracked line may no longer match the host's. Set when input carried a control byte
     * this engine ignores but a shell acts on (Ctrl-W erases a word, Ctrl-T transposes): from there
     * the line here and the line on screen have diverged, and anything built on it — a suggestion, a
     * completion to insert — would complete a line the user is not on. Cleared when the line is.
     */
    @Volatile
    var lineSuspect: Boolean = false
        private set

    /**
     * The narrower half of [lineSuspect]: the shell only *appended* to the tracked line, so what is
     * held is still a genuine prefix of what it has. A Tab the UI did not consume is the case — the
     * shell answers it with a completion. Nothing may be offered or quoted from a prefix, but it can
     * still be classified: a production guard reading `rm -rf /srv/bac` finds the same reason the
     * finished line would, and a completed command whose line wrapped is off the screen's only row.
     */
    @Volatile
    var linePartial: Boolean = false
        private set

    /** The line is the client's own again: neither a guess nor the beginning of a longer one. */
    private fun clearSuspicion() {
        lineSuspect = false
        linePartial = false
    }

    /**
     * A line ran without passing through here — a ready-made command sent straight to the PTY — and
     * [tail] is what it left behind on the shell's line, usually nothing. `null` when that cannot be
     * known: Ctrl-O runs the line *and* recalls the next history entry into it, so what is there
     * afterwards is the shell's business.
     *
     * With a [tail] the line is replaced rather than marked suspect: what happened to it is known
     * exactly, and `suspect` would cost the *next* command its history entry, its suggestion and the
     * tracked candidate the production guard classifies. What is lost either way is the command that
     * ran — the engine never saw it, so it is not history.
     */
    fun lineRanElsewhere(tail: String?) {
        line = tail.orEmpty()
        lineSuspect = tail == null
        linePartial = false
        // As on any other line change: the alternative being cycled belonged to the line that left.
        cycleIndex = 0
    }

    /** Command history (for reverse-search from the UI). */
    val commandHistory: CommandHistory get() = history

    /** Forgets a command from history (e.g. a typo that produced "command not found"). `true` if it was present. */
    fun forget(command: String): Boolean = history.forget(command)

    /** Resets the tracked line without recording it to history (e.g. entering a no-echo mode). */
    fun reset() {
        line = ""
        cycleIndex = 0
        clearSuspicion()
    }

    /**
     * Processes [data] bytes the user sent to the PTY. Returns the command if the input contained
     * Enter (also recorded to history), else `null`. Multiple lines in one block are processed in
     * order — the LAST committed one is returned.
     */
    fun onUserInput(data: ByteArray): String? {
        cycleIndex = 0 // line changed — cycling restarts from the best candidate
        var committed: String? = null
        // Built in a buffer that never leaves this function: a paste arrives as one block, and
        // growing the shared line a character at a time would copy it once per character. What is
        // published to [line] is still only a finished string, so a reader on another thread cannot
        // see anything half-built.
        val buffer = StringBuilder(line)
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == CR || b == LF -> {
                    val cmd = buffer.toString().trim()
                    if (cmd.isNotEmpty() && !lineSuspect) {
                        history.record(cmd)
                        committed = cmd
                    }
                    buffer.setLength(0)
                    line = ""
                    clearSuspicion()
                }
                b == BS || b == DEL -> if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                b == CTRL_U || b == CTRL_C -> { buffer.setLength(0); line = ""; clearSuspicion() }
                // arrows/navigation — reset
                b == ESC -> {
                    buffer.setLength(0)
                    line = ""
                    clearSuspicion()
                    i = skipEscapeSequence(data, i)
                }
                // A Tab the UI consumed never gets here (it calls [acceptSuggestion] instead), so one
                // that does went to the shell, which answers it by rewriting the line — a completion,
                // a list of them, a bell. The tracked line is a prefix of the real one from then on
                // and nothing local can say how much of one, so it stops being trusted: offering a
                // ghost for it would complete text the shell does not have, and the production guard
                // would quote the prefix as the whole command.
                b == TAB -> { lineSuspect = true; linePartial = true }
                // A control byte that edits the line on the shell side (Ctrl-W kills a word, Ctrl-K
                // the rest of it) leaves the two lines disagreeing — mark the line, nothing is offered
                // for it. Cursor moves and screen redraws (Ctrl-A/E, Ctrl-L) keep the line as it is.
                b in LINE_EDITING_CONTROLS -> { lineSuspect = true; linePartial = false }
                b < 0x20 -> { /* other control bytes — ignored, line untouched */ }
                else -> {
                    // Printable character: decoded as UTF-8 (multi-byte sequences taken whole).
                    val (ch, next) = decodeUtf8(data, i)
                    // What the shell completed sits where the cursor was, and this lands after it:
                    // the two lines part company here, so what is held stops being a prefix of the
                    // shell's. A deletion does not — dropping the end of a prefix leaves a prefix.
                    if (ch != null) { buffer.append(ch); linePartial = false }
                    i = next
                    continue
                }
            }
            i++
        }
        line = buffer.toString()
        return committed
    }

    /**
     * Ordered list of full completion candidates for the current line (for cycling). Priority:
     * history, then common commands, then (once an argument has started) known subcommands and
     * path/tokens seen in this session's history. Duplicates collapsed, first-seen order kept.
     * Empty if there's nothing to suggest (empty line / ends with a space).
     */
    fun candidates(): List<String> = candidatesFor(currentLine)

    private fun candidatesFor(prefix: String): List<String> {
        if (prefix.isBlank() || prefix.endsWith(' ')) return emptyList()
        val out = LinkedHashSet<String>()
        history.matches(prefix).forEach { out.add(it) }
        builtins.forEach { if (it.length > prefix.length && it.startsWith(prefix)) out.add(it) }
        if (prefix.contains(' ')) {
            subcommandCandidates(prefix).forEach { out.add(it) }
            tokenCandidates(prefix).forEach { out.add(it) }
        }
        return out.filter { it.length > prefix.length && it.startsWith(prefix) }.toList()
    }

    /**
     * Full suggestion for the current line — the candidate under the cycle cursor, or `null`. Also
     * `null` for a line a control byte may have edited ([lineSuspect]): completing it would rewrite a
     * line the user is no longer on.
     */
    fun suggestion(): String? = if (lineSuspect) null else suggestionFor(currentLine)

    private fun suggestionFor(prefix: String): String? {
        val c = candidatesFor(prefix)
        if (c.isEmpty()) return null
        return c[cycleIndex.mod(c.size)]
    }

    /**
     * Suggestion tail — what to render in gray after the typed text, or `null`.
     *
     * The line is read once and both halves are taken from that one value. Reading it again for the
     * cut is what makes a value that cannot tear tear anyway: the line can change between the two
     * reads, and the tail then belongs to a line nobody is on — a cut past its end throws, and a
     * cut before it returns text that [acceptSuggestion] would type into the shell.
     */
    fun suggestionTail(): String? = tailOf(line)

    private fun tailOf(prefix: String): String? {
        if (lineSuspect) return null
        return suggestionFor(prefix)?.substring(prefix.length)
    }

    /**
     * Switches to the next suggestion alternative (Shift+Tab). Cycles through [candidates] with
     * wraparound; a no-op with zero/one candidates. Doesn't change the line, only the selected ghost.
     */
    fun cycleSuggestion() {
        val size = candidates().size
        if (size > 1) cycleIndex = (cycleIndex + 1).mod(size)
    }

    /**
     * Accepts the suggestion: returns the bytes to send to the session to complete the command
     * (the tail), and updates the internal line. `null` if there's nothing to accept.
     */
    fun acceptSuggestion(): ByteArray? {
        // One read for the tail and for what it is appended to: taking the line again would append a
        // tail computed for a different one.
        val prefix = line
        val tail = tailOf(prefix) ?: return null
        line = prefix + tail
        cycleIndex = 0
        return tail.encodeToByteArray()
    }

    /**
     * Known-subcommand suggestions: for a line `cmd partial` (exactly two words, `cmd` in
     * [SUBCOMMANDS]) returns `cmd sub` for each subcommand starting with `partial`.
     */
    private fun subcommandCandidates(prefix: String): List<String> {
        val words = prefix.split(' ')
        if (words.size != 2) return emptyList()
        val (cmd, partial) = words
        val subs = SUBCOMMANDS[cmd] ?: return emptyList()
        return subs.filter { it != partial && it.startsWith(partial) }.map { "$cmd $it" }
    }

    /**
     * Completes the last word with a path/token seen as an argument in this session's history
     * (paths, file/unit names, etc). Tokens are collected from history on the fly, newest first.
     */
    private fun tokenCandidates(prefix: String): List<String> {
        val lastSpace = prefix.lastIndexOf(' ')
        val head = prefix.substring(0, lastSpace + 1)
        val partial = prefix.substring(lastSpace + 1)
        if (partial.isEmpty()) return emptyList()
        return sessionTokens()
            .filter { it.length > partial.length && it.startsWith(partial) }
            .map { head + it }
    }

    /** Distinct arguments (not the first word) from command history, newest first, deduplicated. */
    private fun sessionTokens(): List<String> {
        val seen = LinkedHashSet<String>()
        for (cmd in history.commands) {
            val parts = cmd.split(' ')
            for (i in 1 until parts.size) {
                val t = parts[i]
                if (t.length >= 2) seen.add(t)
            }
        }
        return seen.toList()
    }

    /** Skips an ESC sequence (CSI/`ESC [ … final` or plain `ESC x`); returns the index past it. */
    private fun skipEscapeSequence(data: ByteArray, escIndex: Int): Int {
        if (escIndex + 1 >= data.size) return escIndex
        val next = data[escIndex + 1].toInt() and 0xFF
        if (next != '['.code && next != 'O'.code) return escIndex + 1 // plain ESC x
        var j = escIndex + 2
        while (j < data.size) {
            val c = data[j].toInt() and 0xFF
            if (c in 0x40..0x7E) return j // CSI final byte
            j++
        }
        return data.size - 1
    }

    /**
     * Decodes one UTF-8 character starting at [i]; returns (character string|null, next byte
     * index). A String, not a Char: a character outside the BMP (4-byte UTF-8) is a surrogate
     * pair in UTF-16, which a single Char can't hold.
     */
    private fun decodeUtf8(data: ByteArray, i: Int): Pair<String?, Int> {
        val b = data[i].toInt() and 0xFF
        val len = when {
            b < 0x80 -> 1
            b in 0xC0..0xDF -> 2
            b in 0xE0..0xEF -> 3
            b in 0xF0..0xF7 -> 4
            else -> 1 // invalid leading byte — skip one
        }
        if (i + len > data.size) return null to (i + 1) // incomplete sequence in this block
        val text = data.copyOfRange(i, i + len).decodeToString()
        return text.ifEmpty { null } to (i + len)
    }

    private companion object {
        const val CR = 13
        const val LF = 10
        const val BS = 8
        const val DEL = 127
        const val CTRL_C = 3
        const val CTRL_U = 21
        const val ESC = 27
        const val TAB = 9

        /**
         * Control bytes after which the tracked line is no longer something to quote or complete.
         *
         * Most rewrite the line and this engine cannot follow the result: Ctrl-D (delete forward),
         * Ctrl-K (kill to end), Ctrl-N/Ctrl-P (history recall, which replaces it wholesale), Ctrl-O
         * (run it and recall the next), Ctrl-R (reverse search — the line is replaced and what is
         * typed next goes into the search box, not onto it), Ctrl-T (transpose), Ctrl-W (kill word),
         * Ctrl-Y (yank), Ctrl-_ (undo). Ctrl-A/Ctrl-B/Ctrl-E/Ctrl-F leave the content alone and move the cursor,
         * which is worse for the same reason: what is typed next lands where the cursor is, not at
         * the end, so a line built by appending is one the shell does not have — and the production
         * guard would quote it. The cost is wider than an edited line: Ctrl-A is also the tmux and
         * screen prefix, and it reaches the PTY like any other Ctrl byte, so a window switch costs
         * the next command its ghost and its history entry until the line is run or cleared. The
         * alternative is a confirmation that names a command nobody wrote.
         *
         * Ctrl-U and Ctrl-C clear the line outright and are handled above; Ctrl-L only redraws.
         */
        val LINE_EDITING_CONTROLS = setOf(1, 2, 4, 5, 6, 11, 14, 15, 16, 18, 20, 23, 25, 31)
    }
}

/**
 * Small list of common commands/paths for autocomplete when history is empty. Intentionally short
 * and conservative (nothing destructive is suggested as the first match ahead of a destructive word).
 */
val COMMON_COMMANDS: List<String> = listOf(
    "cd ", "ls -la", "ls -lah", "cat ", "grep -rn ", "tail -f ", "less ",
    "cd /etc/", "cd /var/log/", "cd /home/", "cd /usr/local/",
    "systemctl status ", "systemctl restart ", "journalctl -u ", "journalctl -xe",
    "docker ps", "docker logs ", "docker compose up -d", "docker compose down",
    "git status", "git pull", "git log --oneline",
    "df -h", "du -sh ", "free -h", "top", "htop", "ps aux | grep ",
    "sudo ", "exit", "clear",
)

/**
 * Known subcommands of common CLIs for second-word completion (`git pus` -> `git push`).
 * Intentionally compact, no destructive suggestions first. Works with an empty history too.
 */
val SUBCOMMANDS: Map<String, List<String>> = mapOf(
    "git" to listOf(
        "status", "add", "commit", "push", "pull", "fetch", "checkout", "switch", "branch",
        "log", "diff", "stash", "merge", "rebase", "clone", "remote", "reset", "tag", "restore",
    ),
    "docker" to listOf(
        "ps", "images", "logs", "exec", "run", "build", "pull", "push", "stop", "start",
        "restart", "rm", "rmi", "compose", "inspect", "stats", "network", "volume", "system",
    ),
    "systemctl" to listOf(
        "status", "start", "stop", "restart", "reload", "enable", "disable", "list-units",
        "daemon-reload", "is-active", "is-enabled",
    ),
    "kubectl" to listOf(
        "get", "describe", "logs", "apply", "delete", "exec", "rollout", "scale",
        "port-forward", "config", "cluster-info",
    ),
    "apt" to listOf("update", "upgrade", "install", "remove", "search", "show", "list", "autoremove"),
    "brew" to listOf("install", "update", "upgrade", "list", "search", "info", "uninstall", "services"),
    "npm" to listOf("install", "run", "start", "test", "build", "update", "list", "ci"),
    "cargo" to listOf("build", "run", "test", "check", "add", "update", "clippy", "fmt"),
)
