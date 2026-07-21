package app.skerry.ui.session

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One session a broadcast can address: its [id], the [label] shown in the picker, and [send] —
 * writing to that session's terminal, returning whether the session was still live enough to take it.
 * The controller never touches [Session] itself, so broadcasting is testable without a live transport.
 */
data class BroadcastTarget(
    val id: String,
    val label: String,
    val send: (String) -> Boolean,
)

/**
 * Broadcast mode: type once, run on several sessions (csshX / tmux `synchronize-panes`). Holds only
 * the selection — the live target list is passed in on every call, so a session closed after being
 * selected is simply skipped rather than resurrected or crashed on.
 *
 * The selection is deliberately kept as ids of *sessions*, not hosts: two tabs onto the same host
 * are two shells with two working directories, and a broadcast means "these shells".
 */
@Stable
class BroadcastController {

    var selectedIds: Set<String> by mutableStateOf(emptySet())
        private set

    fun isSelected(id: String): Boolean = id in selectedIds

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll(targets: List<BroadcastTarget>) {
        selectedIds = selectedIds + targets.map { it.id }
    }

    fun clear() {
        selectedIds = emptySet()
    }

    /** How many of the still-live [targets] are selected (stale ids don't count). */
    fun selectedCount(targets: List<BroadcastTarget>): Int = targets.count { it.id in selectedIds }

    /**
     * Send [command] plus a newline to every selected live target; returns how many actually
     * received it. A blank command is a no-op. One target failing (its channel died between the
     * picker rendering and the send) must not swallow the rest of the broadcast — that would be the
     * worst possible outcome for a command meant to run everywhere.
     */
    fun send(command: String, targets: List<BroadcastTarget>): Int {
        val text = command.trim()
        if (text.isEmpty()) return 0
        var delivered = 0
        for (target in targets) {
            if (target.id !in selectedIds) continue
            // The target reports it: a write into the terminal's outbound queue always succeeds, so
            // counting the call itself would report a host whose transport just died as reached.
            if (runCatching { target.send(text + "\n") }.getOrDefault(false)) delivered++
        }
        return delivered
    }
}
