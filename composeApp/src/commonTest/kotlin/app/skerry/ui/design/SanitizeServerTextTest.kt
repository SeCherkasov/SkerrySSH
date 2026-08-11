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

}
