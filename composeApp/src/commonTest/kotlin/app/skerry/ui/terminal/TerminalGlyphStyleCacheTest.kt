package app.skerry.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import app.skerry.shared.terminal.TermColor
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.highlight.HighlightKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class TerminalGlyphStyleCacheTest {
    private fun rgb(index: Int) = TermStyle(fg = TermColor.Rgb(index shr 16, (index shr 8) and 255, index and 255))

    @Test
    fun `a truecolor stream retains only the style budget`() {
        val cache = GlyphStyleCache(capacity = 8)
        repeat(1_000) { index ->
            cache.style(rgb(index), HighlightKind.None) { TextStyle(color = Color(index)) }
            assertEquals(minOf(index + 1, 8), cache.size, "retained styles after paint $index")
        }
    }

    @Test
    fun `style and category hits reuse the measured style`() {
        val cache = GlyphStyleCache(capacity = 2)
        val original = cache.style(rgb(1), HighlightKind.None) { TextStyle(color = Color.Red) }
        assertSame(original, cache.style(rgb(1), HighlightKind.None) { error("cache miss") })
        val highlighted = cache.style(rgb(1), HighlightKind.Command) { TextStyle(color = Color.Green) }
        assertNotSame(original, highlighted)
        assertSame(highlighted, cache.style(rgb(1), HighlightKind.Command) { error("category miss") })
        assertEquals(1, cache.size)
    }

    @Test
    fun `eviction keeps other entries and recreates the evicted style`() {
        val cache = GlyphStyleCache(capacity = 2)
        val first = cache.style(rgb(1), HighlightKind.None) { TextStyle(color = Color.Red) }
        val second = cache.style(rgb(2), HighlightKind.None) { TextStyle(color = Color.Green) }
        cache.style(rgb(3), HighlightKind.None) { TextStyle(color = Color.Blue) }
        assertSame(second, cache.style(rgb(2), HighlightKind.None) { error("unrelated entry lost") })
        val restored = cache.style(rgb(1), HighlightKind.None) { TextStyle(color = Color.Red) }
        assertNotSame(first, restored)
        assertEquals(first, restored)
        assertEquals(2, cache.size)
    }
}
