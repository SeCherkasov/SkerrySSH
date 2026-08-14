package app.skerry.shared.terminal

import kotlin.concurrent.Volatile

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

    /**
     * Entries (newest first) and the session-only marks, replaced together as ONE value.
     *
     * A terminal reads its history from the coroutine that owns the emulator — every published
     * screen refreshes the ghost suggestion — while the line that produced an entry is recorded
     * from the thread the user typed on. A list being iterated while another thread adds to it is
     * a `ConcurrentModificationException` on the reader; two separately-volatile fields were the
     * subtler failure: a reader pairing a stale entries snapshot with fresher marks saw a
     * host-authored entry unmarked and persisted it. One immutable holder makes the pair atomic —
     * two writers can still lose an update to each other, but no reader can ever see entries and
     * marks from different moments. Replacing the value costs a copy per command entered, which is
     * once per Enter.
     */
    private class State(val entries: List<String>, val sessionOnly: Set<String>)

    @Volatile
    private var state = State(emptyList(), emptySet())

    /** Snapshot of history, newest first. */
    val commands: List<String> get() = state.entries

    /**
     * The entries a store may persist: what the user typed, without the commands recorded off the
     * screen. A completion the host drew serves the ghost and reverse search in THIS session, but
     * persisting it would carry host-authored text into the cross-host command palette — which
     * offers it back while the user is connected somewhere else.
     */
    val persistedCommands: List<String>
        get() {
            val current = state
            if (current.sessionOnly.isEmpty()) return current.entries
            return current.entries.filterNot { it in current.sessionOnly }
        }

    /** Fills history from a ready-made list (e.g. loaded from a store); order is newest first. */
    fun preload(history: List<String>) {
        state = State(emptyList(), emptySet())
        history.asReversed().forEach { record(it) }
    }

    /**
     * Records an executed [command]. Empty/blank input is ignored; an existing entry is moved to
     * the top (no duplicates), otherwise it's prepended. The tail beyond [capacity] is dropped,
     * and its session-only marks with it — a mark whose entry aged out would otherwise pin its
     * string for the life of the session.
     *
     * [sessionOnly] keeps the entry out of [persistedCommands] — for a command completed by the
     * host on its own screen row. Recording the same text again as the user's own lifts the mark:
     * a command actually typed end to end is the user's to keep. The reverse never happens: a
     * session-only record for text that is already the user's own entry leaves it the user's —
     * otherwise the host, by drawing a completion equal to a stored command, could demote it and
     * have the next save silently erase its persisted copy.
     */
    fun record(command: String, sessionOnly: Boolean = false) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        val current = state
        val updated = listOf(trimmed) + current.entries.filterNot { it == trimmed }
        var marks = when {
            !sessionOnly -> current.sessionOnly - trimmed
            trimmed in current.entries && trimmed !in current.sessionOnly -> current.sessionOnly
            else -> current.sessionOnly + trimmed
        }
        if (updated.size > capacity) marks = marks - updated.drop(capacity).toSet()
        state = State(updated.take(capacity), marks)
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
        return state.entries.filter { it.length > prefix.length && it.startsWith(prefix) }
    }

    /**
     * Substring search (reverse-search, like Ctrl-R in bash/zsh): commands CONTAINING [query],
     * newest first. A blank [query] yields an empty list.
     */
    fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return state.entries.filter { it.contains(query) }
    }

    /** Forgets [command] (e.g. a typo that produced "command not found"). `true` if it was present. */
    fun forget(command: String): Boolean {
        val trimmed = command.trim()
        // One read for what is kept and for what it is compared against: taking the state again
        // would answer about a value this call never looked at, and then overwrite it.
        val current = state
        val kept = current.entries.filterNot { it == trimmed }
        if (kept.size == current.entries.size) return false
        state = State(kept, current.sessionOnly - trimmed)
        return true
    }
}
