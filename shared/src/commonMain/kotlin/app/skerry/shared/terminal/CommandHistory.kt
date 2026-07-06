package app.skerry.shared.terminal

/**
 * History of entered commands for autocomplete (fish/zsh-autosuggestion style). Stores commands
 * newest-first, collapses duplicates by moving a repeated command back to the top, capped at
 * [capacity]. Pure logic, no I/O; currently in-memory per session only (not persisted).
 *
 * Everything the user types and confirms with Enter is recorded here. The layer above filters out
 * no-echo input (passwords/passphrases) before recording, via
 * [app.skerry.shared.ssh.ShellChannel.echoSuppressed] (see `TerminalScreenState.typeInput`). SSH
 * echo status isn't always available, so an in-session password is a residual risk: don't add
 * disk persistence for history until echo detection covers all transports, or secrets could land
 * on disk.
 */
class CommandHistory(private val capacity: Int = 500) {

    private val entries = ArrayDeque<String>() // index 0 is the most recent

    /** Snapshot of history, newest first. */
    val commands: List<String> get() = entries.toList()

    /** Fills history from a ready-made list (e.g. loaded from a store); order is newest first. */
    fun preload(history: List<String>) {
        entries.clear()
        history.asReversed().forEach { record(it) }
    }

    /**
     * Records an executed [command]. Empty/blank input is ignored; an existing entry is moved to
     * the top (no duplicates), otherwise it's prepended. The tail beyond [capacity] is dropped.
     */
    fun record(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        entries.remove(trimmed)
        entries.addFirst(trimmed)
        while (entries.size > capacity) entries.removeLast()
    }

    /**
     * Most recent command starting with [prefix] and strictly longer than it, or `null`. A
     * blank [prefix] yields no suggestion (doesn't interfere at the start of a line).
     */
    fun suggestion(prefix: String): String? = matches(prefix).firstOrNull()

    /**
     * All commands starting with [prefix] and strictly longer than it, newest first (for cycling
     * alternatives). A blank [prefix] yields an empty list.
     */
    fun matches(prefix: String): List<String> {
        if (prefix.isBlank()) return emptyList()
        return entries.filter { it.length > prefix.length && it.startsWith(prefix) }
    }

    /**
     * Substring search (reverse-search, like Ctrl-R in bash/zsh): commands CONTAINING [query],
     * newest first. A blank [query] yields an empty list.
     */
    fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return entries.filter { it.contains(query) }
    }

    /** Forgets [command] (e.g. a typo that produced "command not found"). `true` if it was present. */
    fun forget(command: String): Boolean = entries.remove(command.trim())
}
