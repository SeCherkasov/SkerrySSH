package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Ctrl-R overlay on its own, without a terminal behind it. [TerminalScreenState] only wires the
 * callbacks; what the overlay does with them is decided here.
 */
class TerminalReverseSearchTest {

    private class Harness(
        var canOpen: Boolean = true,
        var history: MutableList<String> = mutableListOf("git status", "git push", "ls -la"),
    ) {
        var opened = 0
        val accepted = mutableListOf<String>()
        val forgotten = mutableListOf<String>()
        val search = TerminalReverseSearch(
            canOpen = { canOpen },
            matches = { q -> history.filter { it.contains(q) } },
            onOpen = { opened++ },
            onAccept = { accepted += it },
            onForget = { forgotten += it },
        )
    }

    @Test
    fun `open is refused where there is no line history`() {
        val h = Harness(canOpen = false)
        h.search.open()
        assertNull(h.search.query)
        assertEquals(0, h.opened) // the other overlay is not disturbed either
    }

    @Test
    fun `open drops the conflicting overlay and starts empty`() {
        val h = Harness()
        h.search.open()
        assertEquals("", h.search.query)
        assertEquals(1, h.opened)
        assertEquals(h.history, h.search.results) // an empty query matches everything
    }

    @Test
    fun `typing narrows the results and re-anchors the cursor`() {
        val h = Harness()
        h.search.open()
        h.search.next() // move off the first match
        assertEquals(1, h.search.index)

        h.search.append("git")
        assertEquals(listOf("git status", "git push"), h.search.results)
        // Re-anchored: the old cursor pointed into a different result list.
        assertEquals(0, h.search.index)

        h.search.backspace()
        assertEquals("gi", h.search.query)
        assertEquals(0, h.search.index)
    }

    @Test
    fun `next and previous wrap around`() {
        val h = Harness()
        h.search.open()
        h.search.prev() // backwards off the first entry lands on the last
        assertEquals("ls -la", h.search.selection)
        h.search.next()
        assertEquals("git status", h.search.selection)
    }

    @Test
    fun `accept hands over the selection and closes`() {
        val h = Harness()
        h.search.open()
        h.search.next()
        h.search.accept()
        assertEquals(listOf("git push"), h.accepted)
        assertNull(h.search.query) // closed
    }

    @Test
    fun `delete hands the selection to the owner and stays open`() {
        val h = Harness()
        h.search.open()
        h.search.deleteSelected()
        assertEquals(listOf("git status"), h.forgotten)
        assertTrue(h.search.query != null) // the overlay is still up, ready for the next delete
    }

    @Test
    fun `clampIndex pulls the cursor back after the owner shrank history`() {
        val h = Harness()
        h.search.open()
        h.search.prev() // last entry
        assertEquals(2, h.search.index)

        h.history.removeAt(2)
        h.search.clampIndex()

        // Without the clamp the index would still read 2 and `selection` would wrap modulo the new
        // size back to the first entry — a delete would then hit an unrelated command.
        assertEquals(1, h.search.index)
        assertEquals("git push", h.search.selection)
    }

    @Test
    fun `clampIndex resets to zero when history empties`() {
        val h = Harness()
        h.search.open()
        h.search.next()

        h.history.clear()
        h.search.clampIndex()

        assertEquals(0, h.search.index)
        assertNull(h.search.selection)
    }

    @Test
    fun `an in-range cursor is left alone`() {
        val h = Harness()
        h.search.open()
        h.search.next()
        h.search.clampIndex()
        assertEquals(1, h.search.index)
    }
}
