package app.skerry.ui.terminal

import app.skerry.shared.terminal.TermCell
import app.skerry.shared.terminal.TermSnapshotRow
import app.skerry.shared.terminal.TermStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalLinksTest {

    @Test
    fun `accepts web schemes with authority`() {
        assertTrue(isSafeLinkUri("https://skerry.app/docs"))
        assertTrue(isSafeLinkUri("http://example.com"))
        assertTrue(isSafeLinkUri("ftp://files.example.com/x"))
        assertTrue(isSafeLinkUri("mailto:dev@skerry.app"))
    }

    @Test
    fun `scheme match is case-insensitive`() {
        assertTrue(isSafeLinkUri("HTTPS://Skerry.App"))
        assertTrue(isSafeLinkUri("MailTo:dev@skerry.app"))
    }

    @Test
    fun `rejects dangerous and non-web schemes`() {
        assertFalse(isSafeLinkUri("file:///etc/passwd"))
        assertFalse(isSafeLinkUri("javascript:alert(1)"))
        assertFalse(isSafeLinkUri("data:text/html,<script>"))
        assertFalse(isSafeLinkUri("ssh://root@host"))
    }

    @Test
    fun `rejects degenerate http without authority`() {
        assertFalse(isSafeLinkUri("http:"))
        assertFalse(isSafeLinkUri("https:evil"))
    }

    @Test
    fun `rejects uris carrying control characters`() {
        // A server could embed \n/\r to corrupt platform URI dispatch.
        val nl = 10.toChar()
        val cr = 13.toChar()
        assertFalse(isSafeLinkUri("https://ok.test${nl}https://evil.test"))
        assertFalse(isSafeLinkUri("https://ok.test${cr}x"))
    }

    // --- Plain-text URL detection (no OSC 8): the char-index level ---

    @Test
    fun `detects a bare https url in surrounding text`() {
        val text = "see https://skerry.app/docs for more"
        val links = detectPlainTextLinks(text)
        assertEquals(1, links.size)
        val link = links.single()
        assertEquals("https://skerry.app/docs", link.uri)
        assertEquals("https://skerry.app/docs", text.substring(link.start, link.endExclusive))
    }

    @Test
    fun `detects multiple urls on one line`() {
        val text = "http://a.test and ftp://b.test/x"
        val uris = detectPlainTextLinks(text).map { it.uri }
        assertEquals(listOf("http://a.test", "ftp://b.test/x"), uris)
    }

    @Test
    fun `ignores text without a url`() {
        assertTrue(detectPlainTextLinks("just a normal line, nothing here").isEmpty())
        // A scheme with no authority must not match (mirrors isSafeLinkUri).
        assertTrue(detectPlainTextLinks("run https:evil now").isEmpty())
        // www. without an explicit scheme is intentionally not linkified.
        assertTrue(detectPlainTextLinks("visit www.skerry.app today").isEmpty())
    }

    @Test
    fun `trims trailing sentence punctuation but keeps balanced parens`() {
        assertEquals("https://a.test", detectPlainTextLinks("go to https://a.test.").single().uri)
        assertEquals("https://a.test/p", detectPlainTextLinks("(see https://a.test/p)").single().uri)
        // A ')' that closes a '(' inside the URL is kept.
        assertEquals(
            "https://en.w.org/wiki/Foo_(bar)",
            detectPlainTextLinks("https://en.w.org/wiki/Foo_(bar)").single().uri,
        )
    }

    @Test
    fun `does not linkify dangerous schemes`() {
        assertTrue(detectPlainTextLinks("file:///etc/passwd").isEmpty())
        assertTrue(detectPlainTextLinks("javascript:alert(1)").isEmpty())
    }

    // --- Plain-text URL detection: mapping onto grid columns and click hit-testing ---

    private fun row(text: String): List<TermCell> = text.map { TermCell(it) }

    @Test
    fun `maps a url onto its grid columns`() {
        val screen = listOf(row("x https://a.test y"))
        val span = rowLinkSpans(screen, 0).single()
        assertEquals(2, span.start)                 // 'h' column
        assertEquals(2 + "https://a.test".length, span.endExclusive)
        assertEquals("https://a.test", span.uri)
    }

    @Test
    fun `linkAt returns the uri only under the url columns`() {
        val screen = listOf(row("go https://a.test"))
        assertNull(linkAt(screen, 0, 0))                        // 'g'
        assertEquals("https://a.test", linkAt(screen, 0, 3))    // 'h'
        assertEquals("https://a.test", linkAt(screen, 0, screen[0].lastIndex))
    }

    @Test
    fun `detection is independent of the OSC 8 hyperlink field`() {
        // A cell may already carry an OSC 8 URI; rowLinkSpans still reports the bare text URL. Which one
        // wins (and the no-double-underline skip) is decided by the renderer/click layer, not here.
        val cells = "https://a.test".map { TermCell(it.toString(), hyperlink = "https://osc8.test") }
        assertEquals("https://a.test", rowLinkSpans(listOf(cells), 0).single().uri)
    }

    @Test
    fun `plain rows without a scheme allocate nothing and yield no spans`() {
        assertTrue(rowLinkSpans(listOf(row("total 42  drwxr-xr-x  root:root  10:30")), 0).isEmpty())
    }

    // --- URLs split by a soft wrap: detection runs over the joined logical line ---

    /** A row the emulator marked as soft-wrapped (the logical line continues on the next row). */
    private fun wrapped(text: String): List<TermCell> = TermSnapshotRow(text.map { TermCell(it) }, wrapped = true)

    @Test
    fun `a url split by a soft wrap is one link on both rows`() {
        val screen = listOf(wrapped("see https://a.test/docs/rate-li"), row("mits#new-certs and more"))
        val uri = "https://a.test/docs/rate-limits#new-certs"
        val top = rowLinkSpans(screen, 0).single()
        assertEquals(uri, top.uri)
        assertEquals(4, top.start)
        assertEquals(screen[0].size, top.endExclusive) // underlined to the wrap point
        val bottom = rowLinkSpans(screen, 1).single()
        assertEquals(uri, bottom.uri)
        assertEquals(0, bottom.start)
        assertEquals("mits#new-certs".length, bottom.endExclusive)
        // Ctrl+click on the tail opens the whole URL, not the fragment under the pointer.
        assertEquals(uri, linkAt(screen, 1, 2))
        assertEquals(uri, linkAt(screen, 0, 4))
        assertNull(linkAt(screen, 1, "mits#new-certs".length + 1))
    }

    @Test
    fun `a url spanning three wrapped rows is linked on the middle row too`() {
        val screen = listOf(wrapped("https://a.test/aaaa"), wrapped("bbbb"), row("cccc end"))
        val uri = "https://a.test/aaaabbbbcccc"
        assertEquals(uri, rowLinkSpans(screen, 1).single().uri)
        assertEquals(0, rowLinkSpans(screen, 1).single().start)
        assertEquals(4, rowLinkSpans(screen, 1).single().endExclusive)
        assertEquals(uri, linkAt(screen, 2, 0))
    }

    @Test
    fun `a hard line break keeps the two rows apart`() {
        val screen = listOf(row("see https://a.test/docs/rate-li"), row("mits#new-certs"))
        assertEquals("https://a.test/docs/rate-li", rowLinkSpans(screen, 0).single().uri)
        assertTrue(rowLinkSpans(screen, 1).isEmpty())
        assertNull(linkAt(screen, 1, 2))
    }

    @Test
    fun `a url whose scheme separator straddles the wrap is still linked`() {
        // The `://` itself lands on the row boundary — a per-row scan sees no scheme on either row.
        val screen = listOf(wrapped("see https:"), row("//a.test/x rest"))
        assertEquals("https://a.test/x", rowLinkSpans(screen, 0).single().uri)
        assertEquals("https://a.test/x", linkAt(screen, 1, 0))
    }

    @Test
    fun `a url running past the chain cap is dropped, not opened truncated`() {
        // 12 wrapped rows: from the head the join stops at the clamp, so the URL's tail is unknown —
        // reporting the joined prefix would open an address the server never printed.
        val screen = buildList {
            add(wrapped("https://a.test/aaaaa"))
            repeat(11) { add(wrapped("bbbbbbbbbbbbbbbbbbbb")) }
            add(row("tail"))
        }
        assertTrue(rowLinkSpans(screen, 0).isEmpty())
        assertNull(linkAt(screen, 0, 3))
        assertTrue(rowLinkSpans(screen, 12).isEmpty())
    }

    @Test
    fun `a url spanning exactly the chain cap is still linked`() {
        // 8 wraps = 9 rows, the longest chain the clamp lets through whole.
        val screen = buildList {
            add(wrapped("https://a.test/aaaaa"))
            repeat(7) { add(wrapped("bbbbbbbbbbbbbbbbbbbb")) }
            add(row("cccc"))
        }
        val uri = "https://a.test/aaaaa" + "bbbbbbbbbbbbbbbbbbbb".repeat(7) + "cccc"
        assertEquals(uri, rowLinkSpans(screen, 0).single().uri)
        assertEquals(uri, linkAt(screen, 8, 0))
    }

    @Test
    fun `a draw window reports the wrapped url on every row it crosses`() {
        val screen = listOf(wrapped("see https://a.test/docs/rate-li"), row("mits#new-certs"), row("plain row"))
        assertEquals(setOf(0, 1), linkSpansByRow(screen, 0..2).keys)
    }

    @Test
    fun `a long chain resolves the same from a draw window and from a single row`() {
        // 13 rows of one logical line: the underline (computed for the whole window) and the hand
        // cursor (computed for the pointed row) must agree on every cell, whatever the scroll offset.
        val screen = buildList {
            add(wrapped("https://a.test/aaaaa"))          // runs past the first block's edge — unlinkable
            repeat(8) { add(wrapped("bbbbbbbbbbbbbbbbbbbb")) }
            add(wrapped("x https://a.test/q y"))          // wholly inside the second block — linkable
            repeat(2) { add(wrapped("cccccccccccccccccccc")) }
            add(row("tail"))
        }
        val whole = linkSpansByRow(screen, 0..12)
        val scrolled = linkSpansByRow(screen, 4..12)
        // Not a vacuous comparison: the second block really does carry a link.
        assertEquals("https://a.test/q", whole.getValue(9).single().uri)
        for (r in 0..12) {
            assertEquals(whole[r].orEmpty(), rowLinkSpans(screen, r), "row $r")
            if (r >= 4) assertEquals(whole[r].orEmpty(), scrolled[r].orEmpty(), "row $r scrolled")
        }
    }

    @Test
    fun `a scheme marker on the last row of a chain is found from any row`() {
        val screen = listOf(wrapped("plain text here"), wrapped("still plain"), row("go https://a.test"))
        assertTrue(rowLinkSpans(screen, 0).isEmpty())
        assertEquals("https://a.test", rowLinkSpans(screen, 2).single().uri)
    }

    @Test
    fun `an out-of-range row yields nothing`() {
        val screen = listOf(row("go https://a.test"))
        assertTrue(rowLinkSpans(screen, 5).isEmpty())
        assertNull(linkAt(screen, 5, 0))
    }

    @Test
    fun `a wide glyph before the wrap keeps the columns aligned on both rows`() {
        // 世 occupies columns 0-1, so the URL on the first row starts at column 2, not 1.
        val head = TermSnapshotRow(
            buildList {
                add(TermCell("世", width = app.skerry.shared.terminal.CellWidth.Wide))
                add(TermCell("", width = app.skerry.shared.terminal.CellWidth.Continuation))
                "https://a.test/pa".forEach { add(TermCell(it)) }
            },
            wrapped = true,
        )
        val screen = listOf(head, row("th x"))
        val top = rowLinkSpans(screen, 0).single()
        assertEquals(2, top.start)
        assertEquals(head.size, top.endExclusive)
        assertEquals("https://a.test/path", top.uri)
        assertEquals("https://a.test/path", linkAt(screen, 1, 1))
    }

    @Test
    fun `a url wrapped by a real emulator is one link end to end`() {
        val emu = app.skerry.shared.terminal.TerminalEmulator(cols = 20, rows = 4)
        emu.feed("see https://a.test/docs/rate-limits done".encodeToByteArray())
        val screen = emu.lines
        assertEquals("https://a.test/docs/rate-limits", rowLinkSpans(screen, 0).single().uri)
        assertEquals("https://a.test/docs/rate-limits", linkAt(screen, 1, 0))
    }

    @Test
    fun `a url wholly inside a wrapped row is reported on that row only`() {
        val screen = listOf(wrapped("go https://a.test now padding"), row("tail of the line"))
        assertEquals("https://a.test", rowLinkSpans(screen, 0).single().uri)
        assertTrue(rowLinkSpans(screen, 1).isEmpty())
    }

    @Test
    fun `wide cells shift columns so mapping still lands on the url`() {
        // A leading wide glyph (CJK) occupies two columns: [Wide, Continuation].
        val cells = buildList {
            add(TermCell("世", width = app.skerry.shared.terminal.CellWidth.Wide))
            add(TermCell("", width = app.skerry.shared.terminal.CellWidth.Continuation))
            add(TermCell(' '))
            "https://a.test".forEach { add(TermCell(it)) }
        }
        val span = rowLinkSpans(listOf(cells), 0).single()
        assertEquals(3, span.start)                 // url starts after wide glyph (cols 0,1) + space (col 2)
        assertEquals("https://a.test", linkAt(listOf(cells), 0, 3))
    }

    // --- openableLinkAt: the single hover/Ctrl+click resolution point ---

    @Test
    fun `resolves the hyperlink and the bare url under a visible cell`() {
        val osc = listOf(listOf(TermCell(text = "x", hyperlink = "https://osc.test")))
        assertEquals("https://osc.test", openableLinkAt(osc, 0, 0))
        val bare = listOf(row("https://a.test"))
        assertEquals("https://a.test", openableLinkAt(bare, 0, 3))
    }

    @Test
    fun `a concealed cell offers nothing to open`() {
        // SGR 8: the cell's text is invisible, so there must be no invisible click target either -
        // for the cell's own OSC 8 hyperlink and for a bare URL detected around it alike.
        val hidden = TermStyle(hidden = true)
        val osc = listOf(listOf(TermCell(text = "x", style = hidden, hyperlink = "https://osc.test")))
        assertNull(openableLinkAt(osc, 0, 0))
        val bare = listOf("https://a.test".map { TermCell(it, hidden) })
        assertNull(openableLinkAt(bare, 0, 3))
    }

    @Test
    fun `a url with a concealed tail is not a link at all`() {
        // Visible "https://a.test" + concealed ".evil.tld/x" reads as one token to the detector;
        // underlining only the visible prefix while opening the joined URI would make the spoof
        // look MORE legitimate, so a span covering any hidden cell is dropped whole.
        val hidden = TermStyle(hidden = true)
        val cells = "https://a.test".map { TermCell(it) } + ".evil.tld/x".map { TermCell(it, hidden) }
        assertTrue(rowLinkSpans(listOf(cells), 0).isEmpty())
        assertNull(openableLinkAt(listOf(cells), 0, 3))
    }

    @Test
    fun `a concealed tail on the wrapped row kills the whole link`() {
        // The realistic shape of the spoof: the visible URL runs to the right margin and the
        // concealed tail lands on the continuation row. Pins the multi-row rowAt mapping inside
        // coversConcealedCell, which the single-row test above cannot reach.
        val hidden = TermStyle(hidden = true)
        val screen = listOf(
            wrapped("go https://a.test"),
            ".evil.tld/x".map { TermCell(it, hidden) },
        )
        assertTrue(rowLinkSpans(screen, 0).isEmpty())
        assertTrue(rowLinkSpans(screen, 1).isEmpty())
        assertNull(openableLinkAt(screen, 0, 5))
    }

    @Test
    fun `the cell's own hyperlink wins over a bare url around it`() {
        // A cell can carry an OSC 8 URI while sitting inside detectable bare-URL text; the
        // explicit hyperlink is the server's declared target and must win.
        val cells = "https://a.test".mapIndexed { i, ch ->
            if (i == 0) TermCell(text = ch.toString(), hyperlink = "https://osc.test") else TermCell(ch)
        }
        assertEquals("https://osc.test", openableLinkAt(listOf(cells), 0, 0))
    }

    @Test
    fun `an unsafe hyperlink never resolves`() {
        val osc = listOf(listOf(TermCell(text = "x", hyperlink = "file:///etc/passwd")))
        assertNull(openableLinkAt(osc, 0, 0))
    }
}
