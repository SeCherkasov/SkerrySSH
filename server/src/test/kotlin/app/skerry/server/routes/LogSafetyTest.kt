package app.skerry.server.routes

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `accountId` and `teamId` are client-chosen and bounded only in length ([MAX_ACCOUNT_ID]), so
 * whatever they carry reaches the operator's log verbatim. The characters that matter are the ones
 * a log viewer treats as structure rather than as text.
 */
class LogSafetyTest {

    @Test
    fun `an id cannot forge a second line in the log`() {
        assertEquals("victim@x.io?WARN admin deleted", logSafe("victim@x.io\nWARN admin deleted"))
        assertEquals("a?b", logSafe("a\rb"))
    }

    /**
     * `isISOControl` covers U+0000-001F and U+007F-009F only. U+2028/U+2029 are line breaks to every
     * JSON- or JS-based log viewer, and the bidi controls reverse the rest of the line, so an id
     * ending in one draws the text after it out of order.
     */
    @Test
    fun `separators and formatting characters are neutralized too`() {
        assertEquals("a?b", logSafe("a\u2028b"), "U+2028 is a line break to a JSON log viewer")
        assertEquals("a?b", logSafe("a\u2029b"), "U+2029 likewise")
        assertEquals("a?b", logSafe("a\u202Eb"), "right-to-left override")
        assertEquals("a?b", logSafe("a\u2066b"), "a bidi isolate")
        assertEquals("a?b", logSafe("a\u200Fb"), "the right-to-left mark")
        assertEquals("a?b", logSafe("a\u00ADb"), "a soft hyphen, which draws as nothing")
    }

    @Test
    fun `an ordinary id is left alone`() {
        assertEquals("maya@example.com", logSafe("maya@example.com"))
    }
}
