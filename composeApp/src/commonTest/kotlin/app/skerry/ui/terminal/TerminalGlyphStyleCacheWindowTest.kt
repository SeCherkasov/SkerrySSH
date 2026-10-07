package app.skerry.ui.terminal

import androidx.compose.ui.text.TextStyle
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.highlight.HighlightKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TerminalGlyphStyleCacheWindowTest {
    private fun style(index: Int) = TermStyle(fg = TermColor.Rgb(0, index shr 8, index and 255))

    @Test
    fun `a colorful viewport keeps its styles across repaints`() {
        val cache = GlyphStyleCache(capacity = 2)
        cache.fitWindow(cells = 12)
        val styles = List(12) { index -> cache.style(style(index), HighlightKind.None) { TextStyle() } }
        repeat(3) {
            styles.forEachIndexed { index, expected ->
                assertSame(expected, cache.style(style(index), HighlightKind.None) { error("viewport style evicted") })
            }
        }
        assertEquals(12, cache.size)
    }

    @Test
    fun `window growth remains bounded and shrink releases old styles`() {
        val cache = GlyphStyleCache(capacity = 2)
        cache.fitWindow(cells = 12)
        repeat(100) { index -> cache.style(style(index), HighlightKind.None) { TextStyle() } }
        assertEquals(24, cache.size)
        cache.fitWindow(cells = 3)
        assertEquals(6, cache.size)
        cache.fitWindow(cells = 0)
        assertEquals(2, cache.size)
    }
}
