package app.skerry.shared.ai.local

import kotlin.test.Test
import kotlin.test.assertEquals

class ThinkTagFilterTest {

    private fun run(vararg deltas: String): String {
        val filter = ThinkTagFilter()
        return deltas.joinToString("") { filter.feed(it) } + filter.tail()
    }

    @Test
    fun `passes plain text through unchanged`() {
        assertEquals("CMD: ls -la\nINFO: lists files", run("CMD: ls -la\n", "INFO: lists files"))
    }

    @Test
    fun `strips a leading think block in a single delta`() {
        assertEquals("CMD: uptime", run("<think>reasoning here</think>\n\nCMD: uptime"))
    }

    @Test
    fun `strips a think block split across many deltas`() {
        assertEquals("CMD: df -h", run("<th", "ink>let me", " reason</th", "ink>\n", "CMD: df -h"))
    }

    @Test
    fun `strips an empty think block with surrounding newlines`() {
        // Qwen3 with /no_think still emits an empty think block.
        assertEquals("CMD: free -h", run("<think>\n\n</think>\n\nCMD: free -h"))
    }

    @Test
    fun `keeps leading whitespace when no think block follows`() {
        assertEquals("\n hello", run("\n hello"))
    }

    @Test
    fun `emits text that only starts like a think tag`() {
        assertEquals("<thinking is hard>", run("<thi", "nking is hard>"))
    }

    @Test
    fun `a later think tag is not stripped`() {
        // Only the leading think block is stripped; a tag mentioned in the answer is content.
        assertEquals("echo '<think>'", run("echo '<think>'"))
    }

    @Test
    fun `unclosed think block yields empty output`() {
        assertEquals("", run("<think>never stops reasoning"))
    }

    @Test
    fun `incomplete tag prefix at stream end is flushed`() {
        assertEquals("<think", run("<think"))
    }
}
