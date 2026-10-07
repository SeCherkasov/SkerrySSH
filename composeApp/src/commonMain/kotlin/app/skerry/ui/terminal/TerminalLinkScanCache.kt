package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell

/**
 * URL detection for immutable emulator snapshots, shared across content publishes. A block is
 * valid only while every row has the same identity and its wrap/clipping boundaries agree.
 * Negative results are cached too: ordinary log lines need no repeated character scan.
 *
 * Keeps only the logical blocks touched by the latest window (at most eight off-window rows at
 * each edge). Paging through history cannot accumulate results or retired snapshots. UI thread
 * only; remember per TerminalScreenState. Clicks still resolve directly from a fresh snapshot.
 */
internal class LinkScanCache {
    private class Entry(
        val chain: WrapChain,
        val rows: List<List<TermCell>>,
        val spans: Map<Int, List<TextLinkSpan>>,
        var used: Boolean = true,
    )

    private val entries = HashMap<Int, Entry>()
    val size: Int get() = entries.size

    fun beginScan() {
        for (entry in entries.values) entry.used = false
    }

    fun finishScan() {
        entries.entries.removeAll { !it.value.used }
    }

    fun spans(
        screen: List<List<TermCell>>,
        chain: WrapChain,
        create: () -> Map<Int, List<TextLinkSpan>>,
    ): Map<Int, List<TextLinkSpan>> {
        val first = chain.rows.first
        val cached = entries[first]
        if (cached != null && cached.chain == chain &&
            cached.rows.indices.all { cached.rows[it] === screen[first + it] }
        ) {
            cached.used = true
            return cached.spans
        }
        val fresh = create()
        entries[first] = Entry(chain, List(chain.rows.count()) { screen[first + it] }, fresh)
        return fresh
    }
}
