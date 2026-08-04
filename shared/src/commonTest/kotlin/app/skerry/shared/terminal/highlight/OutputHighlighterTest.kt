package app.skerry.shared.terminal.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutputHighlighterTest {

    private fun spans(text: String): List<HighlightSpan> {
        val found = highlightOutputLine(text)
        var prevEnd = 0
        for (span in found) {
            assertTrue(span.start >= prevEnd, "spans overlap or unsorted in `$text`: $found")
            assertTrue(span.start < span.endExclusive && span.endExclusive <= text.length, "bad span in `$text`: $span")
            prevEnd = span.endExclusive
        }
        return found
    }

    private fun textOf(text: String, span: HighlightSpan) = text.substring(span.start, span.endExclusive)

    private fun kindOf(text: String, token: String): HighlightKind? =
        spans(text).firstOrNull { textOf(text, it) == token }?.kind

    @Test
    fun `syslog line gets timestamp and level`() {
        val line = "2026-08-04 10:11:12 ERROR failed to bind"
        assertEquals(HighlightKind.Timestamp, kindOf(line, "2026-08-04"))
        assertEquals(HighlightKind.Timestamp, kindOf(line, "10:11:12"))
        assertEquals(HighlightKind.LevelError, kindOf(line, "ERROR"))
    }

    @Test
    fun `bracketed level marks the word only`() {
        val line = "[WARN] disk 82%"
        assertEquals(HighlightKind.LevelWarn, kindOf(line, "WARN"))
    }

    @Test
    fun `lowercase level with a colon counts`() {
        assertEquals(HighlightKind.LevelError, kindOf("error: no such file", "error"))
    }

    @Test
    fun `level word in prose is ignored`() {
        assertTrue(spans("an error occurred while parsing").isEmpty())
    }

    @Test
    fun `level word must stand on a token boundary`() {
        assertTrue(spans("terror strikes").isEmpty())
        assertTrue(spans("ERRORS everywhere").isEmpty())
    }

    @Test
    fun `all level families are recognized`() {
        assertEquals(HighlightKind.LevelError, kindOf("FATAL boom", "FATAL"))
        assertEquals(HighlightKind.LevelWarn, kindOf("WARNING low space", "WARNING"))
        assertEquals(HighlightKind.LevelInfo, kindOf("INFO started", "INFO"))
        assertEquals(HighlightKind.LevelDebug, kindOf("DEBUG payload", "DEBUG"))
        assertEquals(HighlightKind.LevelOk, kindOf("OK ready", "OK"))
    }

    @Test
    fun `ipv4 with a port`() {
        assertEquals(HighlightKind.Address, kindOf("connect to 10.0.0.14:8080 now", "10.0.0.14:8080"))
    }

    @Test
    fun `bare ipv4`() {
        assertEquals(HighlightKind.Address, kindOf("from 192.168.1.1", "192.168.1.1"))
    }

    @Test
    fun `things that only look like addresses are skipped`() {
        assertTrue(spans("build 1.2.3.4.5 tag").isEmpty())
        assertTrue(spans("host 999.1.1.1 down").isEmpty())
        assertTrue(spans("version 1.2.3").isEmpty())
    }

    @Test
    fun `clock time with milliseconds`() {
        assertEquals(HighlightKind.Timestamp, kindOf("at 23:59:59.123 done", "23:59:59.123"))
    }

    @Test
    fun `plain text yields nothing`() {
        assertTrue(spans("total 48").isEmpty())
        assertTrue(spans("drwxr-xr-x 2 root root 4096 Aug  4 10:00 etc").isEmpty())
    }

    @Test
    fun `scanning stops at the limit`() {
        val line = "x".repeat(600) + " ERROR here"
        assertTrue(highlightOutputLine(line, limit = 512).isEmpty())
    }

    @Test
    fun `empty line yields nothing`() {
        assertTrue(spans("").isEmpty())
    }
}
