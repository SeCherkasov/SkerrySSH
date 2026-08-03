package app.skerry.ui.terminal

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TerminalMatch
import app.skerry.shared.terminal.TerminalSearchError
import app.skerry.shared.terminal.matchNearestTo
import app.skerry.shared.terminal.searchTerminal

/**
 * Find-in-scrollback for one terminal session: the panel's whole state, so desktop keys and the
 * mobile panel drive it uniformly and the render overlay reads a single source.
 *
 * Split out of [TerminalScreenState], which owns the buffer, the PTY and the other overlays. It
 * searches whatever [buffer] returns — in alt-screen (vim/less/htop) that is the application's own
 * frame, which has no scrollback, so the search follows what is on screen.
 *
 * @param scope where a search pass runs; a newer pass cancels the previous one.
 * @param nowMillis clock for the refresh throttle (injected for tests).
 * @param buffer the current screen, read fresh at the start of every pass.
 * @param onOpen invoked when the panel opens, so the owner can drop a conflicting overlay
 *   (reverse search cannot hold the keyboard at the same time).
 */
@Stable
class TerminalOutputSearch(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val buffer: () -> List<List<TermCell>>,
    private val onOpen: () -> Unit = {},
) {

    /** Current search query, or `null` if the panel is closed. */
    var query: String? by mutableStateOf(null)
        private set

    /** Whether the search respects letter case (panel's `Aa` toggle). */
    var caseSensitive: Boolean by mutableStateOf(false)
        private set

    /** Whether the query is a regular expression rather than a literal (panel's `.*` toggle). */
    var regex: Boolean by mutableStateOf(false)
        private set

    /** Matches in the current buffer, top to bottom. Empty while the panel is closed. */
    var matches: List<TerminalMatch> by mutableStateOf(emptyList())
        private set

    /** Index of the selected match in [matches], or `-1` when there is nothing selected. */
    var index: Int by mutableStateOf(-1)
        private set

    /** Why the query yielded nothing usable (bad or too costly regex), or `null`. */
    var error: TerminalSearchError? by mutableStateOf(null)
        private set

    /** Whether the match list hit its cap and more matches exist in the buffer. */
    var truncated: Boolean by mutableStateOf(false)
        private set

    /** The selected match (render scrolls to it and paints it as the current hit), or `null`. */
    val currentMatch: TerminalMatch?
        get() = matches.getOrNull(index)

    // Query of the last search, kept across closing so reopening the panel resumes it (as editors do).
    private var lastQuery: String = ""

    // Buffer row the selection is measured from when the query changes: the bottom of what the user
    // was looking at, so an incremental search lands on the nearest hit above rather than at the top
    // of a long scrollback.
    private var anchor: Int = 0

    // When the match list was last rebuilt, for the snapshot-driven throttle in [refresh].
    private var lastRefreshAt: Long = Long.MIN_VALUE / 2

    // The running search. A newer one cancels it: only the latest query's result may be published,
    // and an abandoned pass stops scanning instead of burning a core to completion.
    private var job: Job? = null

    // Steps requested by next/previous that no published list has applied yet. The pass runs off
    // this coroutine, so a press is banked here and applied when its result lands — two quick
    // presses move two matches, not one.
    private var pendingStep: Int = 0

    /**
     * Bumped by every user action that deliberately moves the selection (open, new query, toggle,
     * next/previous). The viewport follows *this*, not the selected match itself: while scrollback
     * evicts rows under streaming output, a match's row index shifts without the user asking for
     * anything, and scrolling to it would yank them off the line they are reading.
     */
    var navVersion: Int by mutableStateOf(0)
        private set

    /**
     * Re-run the search because a new snapshot landed, keeping the selected match. Throttled to
     * [SEARCH_REFRESH_INTERVAL_MS]: nothing visible lags behind — the highlight is computed per
     * frame over the visible rows — only the counter and the navigation list. No-op with the panel
     * closed.
     */
    fun refreshFromSnapshot() {
        if (query != null) refresh(keep = currentMatch, force = false)
    }

    /**
     * Open the search panel, restoring the previous query. [anchorRow] is the buffer row at the
     * viewport bottom (kept current by [TerminalScreen] through [setAnchorRow]); the first
     * selected match is the last one at or above it.
     */
    fun open(anchorRow: Int = anchor) {
        onOpen()
        anchor = anchorRow
        query = lastQuery
        navVersion++
        refresh(keep = null)
    }

    /**
     * Report the buffer row currently at the bottom of the viewport. The render layer owns the
     * scroll position, so it feeds the anchor that an incremental search re-selects around.
     */
    fun setAnchorRow(row: Int) {
        anchor = row
    }

    /** Close the search panel and drop its matches (the highlight goes with them). */
    fun close() {
        job?.cancel()
        job = null
        pendingStep = 0
        query = null
        matches = emptyList()
        index = -1
        error = null
        truncated = false
    }

    /** Replace the query and re-run the search (incremental: selection re-anchors to the viewport). */
    fun updateQuery(text: String) {
        if (query == null) return
        // A pasted novel is not a search term; the cap keeps pattern compilation bounded too.
        val next = if (text.length <= MAX_SEARCH_QUERY_LENGTH) text else text.take(MAX_SEARCH_QUERY_LENGTH)
        lastQuery = next
        query = next
        pendingStep = 0
        navVersion++
        refresh(keep = null)
    }

    /** Toggle case sensitivity, keeping the selected match if it survives. */
    fun applyCase(enabled: Boolean) {
        if (caseSensitive == enabled) return
        caseSensitive = enabled
        navVersion++
        refresh(keep = currentMatch)
    }

    /** Switch between literal and regex matching, keeping the selected match if it survives. */
    fun applyRegex(enabled: Boolean) {
        if (regex == enabled) return
        regex = enabled
        navVersion++
        refresh(keep = currentMatch)
    }

    /** Select the next (lower) match, wrapping around. No-op without matches. */
    fun next() {
        step(+1)
    }

    /** Select the previous (higher) match, wrapping around. No-op without matches. */
    fun prev() {
        step(-1)
    }

    /**
     * Move the selection by [delta] matches. The step is banked ([pendingStep]) and applied
     * to the freshly published list rather than to the current one: navigation must walk the buffer
     * as it is now, not as it was when the list was last rebuilt (up to
     * [SEARCH_REFRESH_INTERVAL_MS] ago under streaming output).
     */
    private fun step(delta: Int) {
        if (query.isNullOrEmpty()) return
        pendingStep += delta
        navVersion++
        refresh(keep = currentMatch)
    }

    /**
     * Re-run the search over the current buffer. [keep] is the match to stay on if it is still
     * there (output arriving, a toggle flipped); otherwise the selection re-anchors to the viewport
     * ([anchorRow]).
     *
     * The pass runs in its own coroutine and only the latest one publishes: a full buffer walk
     * takes tens of milliseconds, and doing it inline would either block the UI thread (a keystroke
     * in the field) or the coroutine that feeds the emulator (a published snapshot), which the user
     * sees as a terminal that stopped updating.
     *
     * Snapshot-driven refreshes are additionally throttled to [SEARCH_REFRESH_INTERVAL_MS] ([force]
     * `false`). Nothing visible lags behind — the highlight is computed per frame over the visible
     * rows by [TerminalScreen] — only the counter and the navigation list.
     */
    private fun refresh(keep: TerminalMatch?, force: Boolean = true) {
        val current = query
        if (current.isNullOrEmpty()) {
            job?.cancel()
            job = null
            matches = emptyList()
            index = -1
            error = null
            truncated = false
            return
        }
        val now = nowMillis()
        if (!force && now - lastRefreshAt < SEARCH_REFRESH_INTERVAL_MS) return
        lastRefreshAt = now
        // Everything the pass depends on is captured up front: it runs off this coroutine, while
        // the fields it reads keep changing.
        val snapshot = buffer()
        val wasCaseSensitive = caseSensitive
        val useRegex = regex
        val from = keep?.row ?: anchor
        job?.cancel()
        job = scope.launch {
            val result = searchTerminal(
                screen = snapshot,
                query = current,
                caseSensitive = wasCaseSensitive,
                regex = useRegex,
                cancelled = { !isActive },
            )
            if (!isActive) return@launch
            // One atomic publish: readers must never see a new match list against an old counter
            // or a selection index from another query.
            Snapshot.withMutableSnapshot {
                // A newer search took over while this one ran — its result is the one that counts.
                if (query != current || caseSensitive != wasCaseSensitive || regex != useRegex) {
                    return@withMutableSnapshot
                }
                matches = result.matches
                error = result.error
                truncated = result.truncated
                index = selectMatch(result.matches, keep, from)
            }
        }
    }

    /**
     * Index to select in a freshly built [matches] list: the same hit if it is still there, else the
     * nearest one to [anchorRow] — then any banked next/previous steps ([pendingStep]).
     */
    private fun selectMatch(matches: List<TerminalMatch>, keep: TerminalMatch?, anchorRow: Int): Int {
        if (matches.isEmpty()) {
            pendingStep = 0
            return -1
        }
        val kept = keep?.let { matches.indexOf(it) } ?: -1
        val base = if (kept >= 0) kept else matchNearestTo(matches, anchorRow)
        val stepped = if (pendingStep == 0) base else (base + pendingStep).mod(matches.size)
        pendingStep = 0
        return stepped
    }
}
