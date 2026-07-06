package app.skerry.shared.terminal

/**
 * Codepoint metrics for the terminal grid — stateless pure functions (column width, combinability,
 * encoding to cell text). Tested directly.
 */
internal object CharMetrics {

    /**
     * Character width in columns via a simplified East Asian Width + emoji table: 2 for CJK/Hangul/
     * kana/fullwidth forms and emoji blocks, else 1. Combining/zero-width chars count as 1 for now
     * (a separate combining layer handles those).
     */
    fun charWidth(cp: Int): Int = if (
        cp in 0x1100..0x115F ||              // Hangul Jamo
        cp in 0x2E80..0x303E ||              // CJK radicals, Kangxi, punctuation
        cp in 0x3041..0x33FF ||              // Hiragana/Katakana, CJK symbols
        cp in 0x3400..0x4DBF ||              // CJK Ext A
        cp in 0x4E00..0x9FFF ||              // CJK Unified
        cp in 0xA000..0xA4CF ||              // Yi
        cp in 0xAC00..0xD7A3 ||              // Hangul syllables
        cp in 0xF900..0xFAFF ||              // CJK compatibility
        cp in 0xFE10..0xFE19 ||              // vertical forms
        cp in 0xFE30..0xFE6F ||              // CJK compat forms
        cp in 0xFF00..0xFF60 ||              // fullwidth forms
        cp in 0xFFE0..0xFFE6 ||              // fullwidth signs
        cp in 0x1F300..0x1FAFF ||            // emoji, symbols, pictographs
        cp in 0x20000..0x3FFFD               // CJK Ext B and beyond
    ) 2 else 1

    /**
     * Zero-width combining mark (simplified table, like [charWidth]): diacritics, ZWJ, variation
     * selectors, combining marks for symbols. Full Mn/Me category not covered.
     * Hangul Jamo (L/V/T composition) is deliberately excluded: its width is handled by [charWidth]
     * (as in ncurses).
     */
    fun isCombining(cp: Int): Boolean =
        cp == 0x200D ||                  // ZWJ (emoji joiner)
        cp in 0x0300..0x036F ||          // combining diacritical marks
        cp in 0x0483..0x0489 ||          // combining marks (Cyrillic, etc.)
        cp in 0x0591..0x05BD ||          // Hebrew (niqqud, partial)
        cp in 0x0610..0x061A ||          // Arabic (partial)
        cp in 0x064B..0x065F ||          // Arabic diacritics
        cp == 0x0670 ||
        cp in 0x06D6..0x06DC ||
        cp in 0x0E31..0x0E3A ||          // Thai (partial)
        cp in 0x1AB0..0x1AFF ||          // combining diacritical marks extended
        cp in 0x1DC0..0x1DFF ||          // combining diacritical marks supplement
        cp in 0x20D0..0x20FF ||          // combining marks for symbols
        cp in 0xFE00..0xFE0F ||          // variation selectors
        cp in 0xFE20..0xFE2F ||          // combining half marks
        cp in 0xE0100..0xE01EF           // variation selectors supplement

    /** Codepoint to string: BMP is one Char, astral is a surrogate pair, invalid is U+FFFD. */
    fun codePointToString(cp: Int): String = when {
        cp in 0..0xFFFF && cp !in 0xD800..0xDFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> "�"
    }
}
