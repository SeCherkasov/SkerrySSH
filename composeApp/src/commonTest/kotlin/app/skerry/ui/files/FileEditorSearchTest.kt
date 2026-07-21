package app.skerry.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F7 search in the built-in editor: match order, wrap-around, and what never matches. */
class FileEditorSearchTest {

    private val text = "server {\n  listen 443;\n  server_name skerry.app;\n}\n"

    @Test
    fun `finds the first match at or after the caret`() {
        assertEquals(0 until 6, findNextMatch(text, "server", from = 0))
        assertEquals(text.indexOf("server_name") until text.indexOf("server_name") + 6, findNextMatch(text, "server", from = 6))
    }

    @Test
    fun `wraps around to the start after the last match`() {
        val last = text.indexOf("server_name")

        assertEquals(0 until 6, findNextMatch(text, "server", from = last + 1))
    }

    @Test
    fun `matching ignores case`() {
        val listen = text.indexOf("listen")
        assertEquals(listen until listen + 6, findNextMatch(text, "LISTEN", from = 0))
    }

    @Test
    fun `a missing needle and a blank query do not match`() {
        assertNull(findNextMatch(text, "nginx", from = 0))
        assertNull(findNextMatch(text, "", from = 0))
    }

    @Test
    fun `a caret past the end is clamped instead of failing`() {
        assertEquals(0 until 6, findNextMatch(text, "server", from = text.length + 100))
    }
}
