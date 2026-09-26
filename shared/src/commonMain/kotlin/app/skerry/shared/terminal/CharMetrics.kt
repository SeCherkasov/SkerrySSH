package app.skerry.shared.terminal

/**
 * Codepoint metrics for the terminal grid — stateless pure functions (column width, combinability,
 * encoding to cell text). Tested directly.
 */
internal object CharMetrics {

    /**
     * Character width in columns: 2 for East_Asian_Width Wide/Fullwidth (CJK, Hangul syllables,
     * fullwidth forms, emoji with default emoji presentation), else 1 — ambiguous-width characters
     * count as narrow, as in every non-CJK locale. Zero-width characters are [isCombining]'s business.
     */
    fun charWidth(cp: Int): Int = when {
        cp < FIRST_WIDE -> 1
        cp <= BMP_LAST -> if (bmpClass(cp) and WIDE != 0) 2 else 1
        else -> if (inRanges(UnicodeWidthTables.WIDE, cp)) 2 else 1
    }

    /**
     * Whether [cp] draws nothing of its own and joins the cell before it: nonspacing and enclosing
     * marks (diacritics, viramas, variation selectors) and format characters (ZWJ, zero-width space,
     * bidi marks, tags) — the soft hyphen and the prepended concatenation marks excepted, since they
     * draw. Hangul Jamo vowels and finals are letters here, as in ncurses: [charWidth] handles them.
     */
    fun isCombining(cp: Int): Boolean = when {
        cp < FIRST_ZERO_WIDTH -> false
        cp <= BMP_LAST -> bmpClass(cp) and ZERO_WIDTH != 0
        else -> inRanges(UnicodeWidthTables.ZERO_WIDTH, cp)
    }

    // Both tables answered once per BMP code point: box drawing and CJK streams asked the binary
    // searches for every character they printed. Filled on first sight; a racing fill writes the
    // same byte, so no lock is needed.
    private val bmpClasses = ByteArray(BMP_LAST + 1)

    private fun bmpClass(cp: Int): Int {
        val known = bmpClasses[cp].toInt()
        if (known != 0) return known
        var c = LOOKED_UP
        if (inRanges(UnicodeWidthTables.WIDE, cp)) c = c or WIDE
        if (inRanges(UnicodeWidthTables.ZERO_WIDTH, cp)) c = c or ZERO_WIDTH
        bmpClasses[cp] = c.toByte()
        return c
    }

    private const val BMP_LAST = 0xFFFF
    private const val LOOKED_UP = 1
    private const val WIDE = 2
    private const val ZERO_WIDTH = 4

    /** Binary search over sorted inclusive `[start, end]` pairs. */
    private fun inRanges(ranges: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < ranges[2 * mid] -> hi = mid - 1
                cp > ranges[2 * mid + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    private val FIRST_WIDE = UnicodeWidthTables.WIDE[0]
    private val FIRST_ZERO_WIDTH = UnicodeWidthTables.ZERO_WIDTH[0]

    // One shared instance per printable ASCII char: feed() calls codePointToString once per
    // printed character, and streaming plain text allocated a fresh one-char String per byte.
    private val ASCII_STRINGS = Array(0x7F - 0x20) { (0x20 + it).toChar().toString() }

    // Box-drawing glyphs get the same treatment: TUI borders (tmux, mc, htop) repeat them across
    // whole rows, and the generic BMP branch allocated one String per cell.
    private val BOX_DRAWING_STRINGS = Array(0x80) { (0x2500 + it).toChar().toString() }

    /** Codepoint to string: BMP is one Char, astral is a surrogate pair, invalid is U+FFFD. */
    fun codePointToString(cp: Int): String = when {
        cp in 0x20..0x7E -> ASCII_STRINGS[cp - 0x20]
        cp in 0x2500..0x257F -> BOX_DRAWING_STRINGS[cp - 0x2500]
        cp in 0..0xFFFF && cp !in 0xD800..0xDFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> "�"
    }
}

/**
 * How many columns [text] occupies where it is drawn in a monospaced row — a CJK or emoji glyph is
 * one `Char` (often two) and two columns wide, so a character count says a line fits a row twice as
 * wide as the row it is going into. Public because the same question is asked outside the terminal
 * grid: whether a row can show a whole command before it runs it.
 *
 * Rounded up rather than looked up past U+2000: [CharMetrics.charWidth] answers the grid by the
 * Unicode tables, and a font is free to draw an ambiguous or text-presentation symbol wide anyway.
 * The caller here is a gate, where over-counting only asks for one confirmation more often, so
 * everything symbolic counts as two.
 */
fun displayColumns(text: String): Int {
    var columns = 0
    var i = 0
    while (i < text.length) {
        val high = text[i]
        val low = if (high.isHighSurrogate() && i + 1 < text.length) text[i + 1] else null
        val code = if (low?.isLowSurrogate() == true) {
            0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        } else {
            high.code
        }
        columns += if (code >= WIDE_UNLESS_KNOWN) 2 else CharMetrics.charWidth(code)
        i += if (low?.isLowSurrogate() == true) 2 else 1
    }
    return columns
}

/**
 * Past this every code point is counted wide. Not a boundary between alphabets — Latin ligatures and
 * the presentation forms live above it too — but the point past which counting a narrow glyph as
 * wide costs one confirmation and missing a wide one costs a hidden tail.
 */
private const val WIDE_UNLESS_KNOWN = 0x2000
