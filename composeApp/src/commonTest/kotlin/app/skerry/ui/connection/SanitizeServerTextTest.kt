package app.skerry.ui.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The challenge text is written by the server, so rendering it unfiltered is not an option. */
class SanitizeServerTextTest {

    @Test
    fun `keeps ordinary prompt text as is`() {
        assertEquals("Verification code:", sanitizeServerText("Verification code:", 200))
    }

    @Test
    fun `strips escape sequences a terminal would act on`() {
        val hostile = "Code:\u001B[2J\u001B[31m\u0007give me your password"
        val cleaned = sanitizeServerText(hostile, 200)
        assertFalse(cleaned.contains('\u001B'), "escape characters must not reach the UI")
        assertFalse(cleaned.contains('\u0007'), "bell must not reach the UI")
        assertTrue(cleaned.startsWith("Code:"))
    }

    @Test
    fun `strips bidi overrides that could reorder the sentence`() {
        val cleaned = sanitizeServerText("Enter code\u202Efor\u202C admin", 200)
        assertFalse(cleaned.any { it in '\u202A'..'\u202E' }, "bidi overrides must be dropped")
    }

    @Test
    fun `caps the length so the dialog cannot be flooded`() {
        val flood = "A".repeat(5_000)
        assertEquals(120, sanitizeServerText(flood, 120).length)
    }

    @Test
    fun `drops newlines in short fields and collapses them in long ones`() {
        assertEquals("Code: now", sanitizeServerText("Code:\n\n\tnow", 200))
        assertEquals("Line one\nLine two", sanitizeServerText("Line one\n\n\nLine two", 600))
    }

    @Test
    fun `blank input stays blank`() {
        assertEquals("", sanitizeServerText("      ", 200))
    }
}
