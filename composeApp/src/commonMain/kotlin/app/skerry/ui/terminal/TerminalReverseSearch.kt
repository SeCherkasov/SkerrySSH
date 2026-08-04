package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Ctrl-R reverse search over command history: the overlay's whole state, so desktop keys and the
 * mobile panel/IME drive it uniformly and the render overlay reads a single source.
 *
 * Split out of [TerminalScreenState], which owns the buffer, the PTY and the other overlays. This
 * is a picker: a query, a cursor into the matches, and the command it hands back. It never touches
 * history itself — [onForget] does, and [clampIndex] is how its owner tells it the list shrank.
 *
 * @param canOpen whether the overlay may open at all (alt-screen has no line history).
 * @param matches history entries for a query, newest first.
 * @param onOpen invoked on open so the owner can drop a conflicting overlay — find-in-scrollback
 *   and this one cannot both hold the keyboard.
 * @param onAccept the picked command, to be inserted on the shell line.
 * @param onForget the picked command, to be dropped from history.
 */
@Stable
class TerminalReverseSearch(
    private val canOpen: () -> Boolean,
    private val matches: (String) -> List<String>,
    private val onOpen: () -> Unit,
    private val onAccept: (String) -> Unit,
    private val onForget: (String) -> Unit,
) {
    /** Current query, or `null` if the overlay is closed. */
    var query: String? by mutableStateOf(null)
        private set

    /** Index of the selected match in [results]. */
    var index: Int by mutableStateOf(0)
        private set

    /** Matches for the current query (newest to oldest), or empty if the overlay is closed. */
    val results: List<String>
        get() = query?.let(matches) ?: emptyList()

    /** Selected match (at [index]) or `null`. */
    val selection: String?
        get() {
            val r = results
            return if (r.isEmpty()) null else r[index.mod(r.size)]
        }

    /** Open the overlay with an empty query. No-op where [canOpen] says there is no line history. */
    fun open() {
        if (!canOpen()) return
        onOpen()
        query = ""
        index = 0
    }

    /** Close the overlay without inserting anything. */
    fun close() {
        query = null
        index = 0
    }

    /** Append [text] to the query (resets selection to the first match). */
    fun append(text: String) {
        val q = query ?: return
        query = q + text
        index = 0
    }

    /** Remove the last character of the query. */
    fun backspace() {
        val q = query ?: return
        query = q.dropLast(1)
        index = 0
    }

    /** Move to the next (older) match. */
    fun next() {
        val n = results.size
        if (n > 0) index = (index + 1) % n
    }

    /** Move to the previous (newer) match. */
    fun prev() {
        val n = results.size
        if (n > 0) index = (index - 1 + n) % n
    }

    /** Accept the selected match (inserted via [onAccept]) and close the overlay. */
    fun accept() {
        selection?.let(onAccept)
        close()
    }

    /** Drop the selected match from history via [onForget]; the overlay stays open. */
    fun deleteSelected() {
        selection?.let(onForget)
    }

    /**
     * Pull [index] back into range after the owner shrank history. Called by the owner rather than
     * derived on read, because [results] is recomputed per read and an out-of-range index would
     * otherwise wrap to an unrelated entry between the removal and the next keypress.
     */
    fun clampIndex() {
        val n = results.size
        index = if (n == 0) 0 else index.coerceAtMost(n - 1)
    }
}
