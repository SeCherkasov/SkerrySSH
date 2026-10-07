package app.skerry.ui.terminal

import androidx.compose.ui.text.TextStyle
import app.skerry.shared.terminal.TermStyle
import app.skerry.shared.terminal.highlight.HighlightKind

/** Minimum budget; a larger drawn window gets room for one style per cell. */
private const val MIN_GLYPH_STYLE_CACHE_STYLES = 1024

/**
 * Styles shared within one font/palette/theme/density generation. Truecolor output can paint a
 * different style on every cell forever, so evict the oldest style incrementally at a bounded
 * budget. Keep room for a drawn window, like [RowRenderCache], to avoid thrashing on colorful TUIs.
 * Hits need only a hash lookup; highlight categories share a slot without allocating pair keys.
 *
 * This object is also the row-layout generation. Eviction must keep its identity: the rendering
 * inputs did not change, so layouts in [RowRenderCache] remain valid even after a style is evicted.
 * Used only on the UI thread, like that cache.
 */
internal class GlyphStyleCache(private val capacity: Int = MIN_GLYPH_STYLE_CACHE_STYLES) {
    private val entries = LinkedHashMap<TermStyle, Array<TextStyle?>>()
    private var styleBudget = capacity

    init {
        require(capacity > 0)
    }

    val size: Int get() = entries.size

    /** Two windows at one style per cell; shrinking the viewport releases the excess entries. */
    fun fitWindow(cells: Int) {
        styleBudget = maxOf(capacity, cells * 2)
        while (entries.size > styleBudget) entries.remove(entries.keys.first())
    }

    fun style(style: TermStyle, kind: HighlightKind, create: () -> TextStyle): TextStyle {
        val byKind = entries[style] ?: run {
            if (entries.size >= styleBudget) entries.remove(entries.keys.first())
            arrayOfNulls<TextStyle>(HIGHLIGHT_KIND_COUNT).also { entries[style] = it }
        }
        return byKind[kind.ordinal] ?: create().also { byKind[kind.ordinal] = it }
    }
}
