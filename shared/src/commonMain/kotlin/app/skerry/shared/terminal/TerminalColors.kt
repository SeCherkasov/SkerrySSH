package app.skerry.shared.terminal

/**
 * The colors the user sees, as the terminal reports them to OSC 10/11/12 and OSC 4 queries. The
 * renderer owns the theme; the emulator only needs it to answer an application asking whether it
 * runs on a light or a dark background.
 */
class TerminalColors(
    val foreground: TermColor.Rgb,
    val background: TermColor.Rgb,
    val cursor: TermColor.Rgb,
    /** ANSI 0..15 — exactly 16. */
    val ansi: List<TermColor.Rgb>,
) {
    init {
        require(ansi.size == ANSI_COLORS) { "ANSI palette must contain exactly $ANSI_COLORS colors, got ${ansi.size}" }
    }

    private companion object {
        const val ANSI_COLORS = 16
    }
}

/** The theme-independent part of the xterm 256-color palette: the 6×6×6 cube and the grayscale ramp. */
object XtermPalette {
    private const val FIRST = 16
    private const val FIRST_GRAY = 232
    private const val LAST = 255

    private val colors = Array(LAST - FIRST + 1) { i ->
        val index = FIRST + i
        if (index < FIRST_GRAY) {
            val n = index - FIRST
            TermColor.Rgb(level(n / 36), level((n / 6) % 6), level(n % 6))
        } else {
            val v = 8 + (index - FIRST_GRAY) * 10
            TermColor.Rgb(v, v, v)
        }
    }

    private fun level(v: Int) = if (v == 0) 0 else 55 + v * 40

    /** Color of palette [index] 16..255; null for the theme's 0..15 and anything out of range. */
    fun rgb(index: Int): TermColor.Rgb? = if (index in FIRST..LAST) colors[index - FIRST] else null
}
