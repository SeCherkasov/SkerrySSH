package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermSnapshotRow
import app.skerry.shared.terminal.TermStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalLinkScanCacheTest {
    private fun row(text: String, wrapped: Boolean = false): List<TermCell> =
        TermSnapshotRow(text.map { TermCell(it) }, wrapped)

    @Test
    fun unchangedRowsReusePositiveAndNegativeResults() {
        val cache = LinkScanCache()
        val screen = listOf(row("see https://a.test/x"), row("ordinary log line"))
        val first = linkSpansByRow(screen, 0..1, cache)
        val scans = linkChainScans
        val second = linkSpansByRow(screen.toList(), 0..1, cache)
        assertSame(first[0], second[0])
        assertEquals(scans, linkChainScans, "even the no-scheme path must be reused")
        val changed = listOf(screen[0], row("now https://b.test"))
        val found = linkSpansByRow(changed, 0..1, cache)
        assertEquals(scans + 1, linkChainScans)
        assertEquals(linkSpansByRow(changed, 0..1), found)
    }

    @Test
    fun narrowWindowCachesWholeWrappedBlock() {
        val cache = LinkScanCache()
        val screen = listOf(row("see https:", wrapped = true), row("//example.com/path"))
        assertEquals(linkSpansByRow(screen, 0..0), linkSpansByRow(screen, 0..0, cache))
        val scans = linkChainScans
        val tail = linkSpansByRow(screen, 1..1, cache)
        assertEquals(scans, linkChainScans, "moving within a block must not repeat detection")
        assertEquals("https://example.com/path", tail[1]?.single()?.uri)
        assertEquals(setOf(1), tail.keys, "off-window spans stay out of the draw map")
    }

    @Test
    fun changedOffWindowTailInvalidatesVisiblePrefix() {
        val cache = LinkScanCache()
        val head = row("https://good.test", wrapped = true)
        val screen = listOf(head, row("/path"))
        assertTrue(linkSpansByRow(screen, 0..0, cache).isNotEmpty())
        val hiddenTail = TermSnapshotRow(".evil.test/path".map { TermCell(it, TermStyle(hidden = true)) }, false)
        val hidden = listOf(head, hiddenTail)
        assertTrue(linkSpansByRow(hidden, 0..0, cache).isEmpty(), "concealed tail rejects the entire URI")
        val changed = listOf(head, row("/changed"))
        assertEquals("https://good.test/changed", linkSpansByRow(changed, 0..0, cache)[0]?.single()?.uri)
    }

    @Test
    fun changedPredecessorInvalidatesClippedStartWithIdenticalBlockRows() {
        val cache = LinkScanCache()
        val screen = List(9) { row("prefix", wrapped = true) } +
            listOf(row("https://a.test", wrapped = true), row("/x "))
        assertTrue(linkSpansByRow(screen, 9..10, cache).isEmpty(), "URL at a clipped block edge is dropped")
        val changed = screen.toMutableList().apply { this[8] = row("prefix") }
        val found = linkSpansByRow(changed, 9..10, cache)
        assertEquals("https://a.test/x", found[9]?.single()?.uri)
        assertEquals(linkSpansByRow(changed, 9..10), found)
    }

    @Test
    fun appendedRowInvalidatesClippedEndWithIdenticalBlockRows() {
        val cache = LinkScanCache()
        val screen = List(8) { row("prefix ", wrapped = true) } + listOf(row("https://a.test", wrapped = true))
        assertTrue(linkSpansByRow(screen, 8..8, cache).isNotEmpty())
        val extended = screen + listOf(row(".evil.test/path"))
        assertTrue(linkSpansByRow(extended, 8..8, cache).isEmpty(), "the cached URL would now be truncated")
        assertEquals(linkSpansByRow(extended, 8..8), linkSpansByRow(extended, 8..8, cache))
    }

    @Test
    fun trimmingAndReflowCannotReuseAnOldRowIndex() {
        val cache = LinkScanCache()
        val screen = listOf(row("https://a.test"), row("https://b.test"), row("https://c.test"))
        linkSpansByRow(screen, 0..2, cache)
        val trimmed = screen.drop(1)
        assertEquals(linkSpansByRow(trimmed, 0..1), linkSpansByRow(trimmed, 0..1, cache))
        val reflowed = listOf(row("https:", true), row("//c.test"))
        assertEquals(linkSpansByRow(reflowed, 0..1), linkSpansByRow(reflowed, 0..1, cache))
    }

    @Test
    fun scrollingOnlyRetainsLatestWindowAndEmptyWindowReleasesIt() {
        val cache = LinkScanCache()
        val screen = List(1_000) { row("https://example.com/$it") }
        for (start in 0..996) {
            val window = start..start + 3
            assertEquals(linkSpansByRow(screen, window), linkSpansByRow(screen, window, cache))
            assertEquals(4, cache.size, "deep history must not accumulate cached blocks")
        }
        assertTrue(linkSpansByRow(emptyList(), 0..-1, cache).isEmpty())
        assertEquals(0, cache.size)
    }
}
