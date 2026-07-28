package app.skerry.shared.rdp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RdpFileTest {

    private fun parse(text: String) = RdpFileParser.parse(text)

    @Test
    fun `reads typed settings`() {
        val file = parse(
            """
            full address:s:server.example.com
            server port:i:3390
            username:s:alice
            domain:s:CORP
            redirectclipboard:i:1
            """.trimIndent(),
        ).file
        assertEquals("server.example.com", file.string("full address"))
        assertEquals(3390, file.int("server port"))
        assertEquals("alice", file.string("username"))
        assertEquals("CORP", file.string("domain"))
        assertEquals(true, file.bool("redirectclipboard"))
    }

    @Test
    fun `keys are case-insensitive and CRLF framed`() {
        val file = parse("Full Address:s:host\r\nUseRedirectionServerName:i:1\r\n").file
        assertEquals("host", file.string("full address"))
        assertEquals(true, file.bool("useredirectionservername"))
    }

    @Test
    fun `a value may contain colons`() {
        val file = parse("loadbalanceinfo:s:tsv://MS Terminal Services Plugin.1.Employees").file
        assertEquals("tsv://MS Terminal Services Plugin.1.Employees", file.string("loadbalanceinfo"))
    }

    @Test
    fun `an integer setting read as a string stays typed`() {
        // Asking for the wrong type is a caller bug, not a file error: the entry keeps its declared
        // type and the mismatched accessor answers null rather than guessing.
        val file = parse("server port:i:3389\nfull address:s:host").file
        assertNull(file.string("server port"))
        assertNull(file.int("full address"))
    }

    @Test
    fun `a non-numeric integer setting is dropped with a warning`() {
        val result = parse("server port:i:notanumber")
        assertNull(result.file.int("server port"))
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `unparsable lines are ignored`() {
        val result = parse("\n# a comment\nnonsense\nfull address:s:host\n:s:novalue\n")
        assertEquals("host", result.file.string("full address"))
        assertEquals(1, result.file.entries.size)
    }

    @Test
    fun `the first occurrence of a key wins`() {
        val file = parse("full address:s:first\nfull address:s:second").file
        assertEquals("first", file.string("full address"))
    }

    @Test
    fun `a UTF-16 file decoded as UTF-8 still parses`() {
        // mstsc saves .rdp as UTF-16LE; the picker decodes it as UTF-8, which turns the BOM into a
        // replacement char and leaves a NUL filler after every ASCII character. Stripping both is
        // what makes a file straight out of Windows importable at all.
        val filler = Char(0)
        val replacement = Char(0xFFFD)
        val text = "full address:s:host\r\n".map { "$it$filler" }.joinToString("")
        assertEquals("host", parse("$replacement$text").file.string("full address"))
    }

    @Test
    fun `a UTF-8 BOM does not become part of the first key`() {
        assertEquals("host", parse("${Char(0xFEFF)}full address:s:host").file.string("full address"))
    }

    @Test
    fun `a huge file is truncated rather than parsed whole`() {
        val text = (1..RdpFileParser.MAX_LINES + 500).joinToString("\n") { "key$it:s:value" }
        val result = parse(text)
        assertEquals(RdpFileParser.MAX_LINES, result.file.entries.size)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `the publisher signature is dropped without a warning`() {
        // Every signed farm file carries a multi-kilobyte signature; reporting it as skipped would
        // make a perfectly readable file look damaged.
        val result = parse("signature:s:" + "A".repeat(RdpFileParser.MAX_VALUE_LENGTH + 1) + "\nfull address:s:host")
        assertEquals("host", result.file.string("full address"))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `an over-long value is dropped`() {
        val result = parse("full address:s:" + "a".repeat(RdpFileParser.MAX_VALUE_LENGTH + 1))
        assertNull(result.file.string("full address"))
        assertTrue(result.warnings.isNotEmpty())
    }
}
