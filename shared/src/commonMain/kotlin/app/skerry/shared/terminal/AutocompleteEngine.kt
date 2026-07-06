package app.skerry.shared.terminal

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
 */
class AutocompleteEngine(
    private val history: CommandHistory = CommandHistory(),
    private val builtins: List<String> = COMMON_COMMANDS,
) {
    private val line = StringBuilder()

    // Cycle cursor for alternatives (Shift+Tab): index into [candidates]. Reset to 0 on any line
    // change so the best suggestion shows again after a new character.
    private var cycleIndex = 0

    /** Current typed line (for tests/diagnostics). */
    val currentLine: String get() = line.toString()

    /** Command history (for reverse-search from the UI). */
    val commandHistory: CommandHistory get() = history

    /** Forgets a command from history (e.g. a typo that produced "command not found"). `true` if it was present. */
    fun forget(command: String): Boolean = history.forget(command)

    /** Resets the tracked line without recording it to history (e.g. entering a no-echo mode). */
    fun reset() {
        line.clear()
        cycleIndex = 0
    }

    /**
     * Processes [data] bytes the user sent to the PTY. Returns the command if the input contained
     * Enter (also recorded to history), else `null`. Multiple lines in one block are processed in
     * order — the LAST committed one is returned.
     */
    fun onUserInput(data: ByteArray): String? {
        cycleIndex = 0 // line changed — cycling restarts from the best candidate
        var committed: String? = null
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            when {
                b == CR || b == LF -> {
                    val cmd = line.toString().trim()
                    if (cmd.isNotEmpty()) {
                        history.record(cmd)
                        committed = cmd
                    }
                    line.clear()
                }
                b == BS || b == DEL -> if (line.isNotEmpty()) line.deleteAt(line.length - 1)
                b == CTRL_U || b == CTRL_C -> line.clear()
                b == ESC -> { line.clear(); i = skipEscapeSequence(data, i) } // arrows/navigation — reset
                b == TAB -> { /* accept — handled by the UI via acceptSuggestion */ }
                b < 0x20 -> { /* other control bytes — ignored, line untouched */ }
                else -> {
                    // Printable character: decoded as UTF-8 (multi-byte sequences taken whole).
                    val (ch, next) = decodeUtf8(data, i)
                    if (ch != null) line.append(ch)
                    i = next
                    continue
                }
            }
            i++
        }
        return committed
    }

    /**
     * Ordered list of full completion candidates for the current line (for cycling). Priority:
     * history, then common commands, then (once an argument has started) known subcommands and
     * path/tokens seen in this session's history. Duplicates collapsed, first-seen order kept.
     * Empty if there's nothing to suggest (empty line / ends with a space).
     */
    fun candidates(): List<String> {
        val prefix = line.toString()
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

    /** Full suggestion for the current line — the candidate under the cycle cursor, or `null`. */
    fun suggestion(): String? {
        val c = candidates()
        if (c.isEmpty()) return null
        return c[cycleIndex.mod(c.size)]
    }

    /** Suggestion tail — what to render in gray after the typed text, or `null`. */
    fun suggestionTail(): String? {
        val full = suggestion() ?: return null
        return full.substring(line.length)
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
        val tail = suggestionTail() ?: return null
        line.append(tail)
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
