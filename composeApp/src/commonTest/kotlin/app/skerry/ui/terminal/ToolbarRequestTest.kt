package app.skerry.ui.terminal

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ask behind the toolbar buttons that own their own state. It exists because a one-shot signal
 * is dropped when the button is not composed (issue #337), so what matters is that it survives
 * until a reader arrives and that exactly one reader gets it.
 */
class ToolbarRequestTest {

    @Test
    fun `a fresh request is not pending`() {
        assertFalse(ToolbarRequest().pending)
    }

    @Test
    fun `the ask waits until it is taken`() {
        val request = ToolbarRequest()
        request.raise()
        assertTrue(request.pending, "the ask outlives the frame it was made in")
        assertTrue(request.take(), "the reader gets it")
        assertFalse(request.pending, "and it is gone")
    }

    /** A button that composes twice must not open its palette twice. */
    @Test
    fun `a second take gets nothing`() {
        val request = ToolbarRequest()
        request.raise()
        request.take()
        assertFalse(request.take(), "the ask is spent")
    }

    /** Holding the chord down is one ask, not a queue that fires again on the next tab switch. */
    @Test
    fun `raising twice before a take is still one ask`() {
        val request = ToolbarRequest()
        request.raise()
        request.raise()
        assertTrue(request.take())
        assertFalse(request.take(), "the second raise did not queue a second ask")
    }

    @Test
    fun `taking nothing reports nothing`() {
        assertFalse(ToolbarRequest().take())
    }

    /** Separate buttons hold separate asks — one chord must not open the palette beside it. */
    @Test
    fun `requests are independent`() {
        val snippets = ToolbarRequest()
        val runbooks = ToolbarRequest()
        snippets.raise()
        assertFalse(runbooks.pending)
        assertFalse(runbooks.take())
        assertTrue(snippets.take())
    }
}
