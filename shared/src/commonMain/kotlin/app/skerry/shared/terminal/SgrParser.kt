package app.skerry.shared.terminal

/**
 * Parses and applies SGR (`CSI ... m`) — pure functions over [TermStyle], no emulator state;
 * tested directly. The emulator holds the current style and reassigns it with the result of [apply].
 */
internal object SgrParser {

    /**
     * Applies SGR to [start] and returns the new style. A parameter's `:`-subparameters (modern
     * colon form: `4:3`, `38:2::r:g:b`, `58:5:n`) follow it in [params] flagged as subparameters.
     * Extended colors (38/48/58) accept both forms: colon subparameters within one parameter, or the
     * legacy `;` form where type and components are separate parameters (consumed from the tail).
     * An omitted field reads as 0, as in xterm.
     */
    fun apply(params: CsiParams, start: TermStyle): TermStyle {
        if (params.size == 0) return TermStyle()
        var style = start
        var i = 0
        while (i < params.size) {
            val end = groupEnd(params, i)
            var next = end
            when (val p = value(params, i)) {
                0 -> style = TermStyle()
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underlineStyle = underlineFromSub(if (end - i > 1) value(params, i + 1) else 1))
                5, 6 -> style = style.copy(blink = true)
                7 -> style = style.copy(inverse = true)
                8 -> style = style.copy(hidden = true)
                9 -> style = style.copy(strikethrough = true)
                21 -> style = style.copy(underlineStyle = UnderlineStyle.Double)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underlineStyle = UnderlineStyle.None)
                25 -> style = style.copy(blink = false)
                27 -> style = style.copy(inverse = false)
                28 -> style = style.copy(hidden = false)
                29 -> style = style.copy(strikethrough = false)
                in 30..37 -> style = style.copy(fg = TermColor.indexed(p - 30))
                38 -> { val (col, n) = extendedColor(params, i, end); style = style.copy(fg = col); next = n }
                39 -> style = style.copy(fg = TermColor.Default)
                in 40..47 -> style = style.copy(bg = TermColor.indexed(p - 40))
                48 -> { val (col, n) = extendedColor(params, i, end); style = style.copy(bg = col); next = n }
                49 -> style = style.copy(bg = TermColor.Default)
                58 -> { val (col, n) = extendedColor(params, i, end); style = style.copy(underlineColor = col); next = n }
                59 -> style = style.copy(underlineColor = TermColor.Default)
                in 90..97 -> style = style.copy(fg = TermColor.indexed(8 + p - 90))
                in 100..107 -> style = style.copy(bg = TermColor.indexed(8 + p - 100))
            }
            i = next
        }
        return style
    }

    private fun value(params: CsiParams, i: Int): Int = params.at(i, 0).coerceAtLeast(0)

    /** Index past the parameter starting at [i] and its `:`-subparameters. */
    private fun groupEnd(params: CsiParams, i: Int): Int {
        var j = i + 1
        while (j < params.size && params.isSub(j)) j++
        return j
    }

    /** Start of the parameter [k] parameters after the one starting at [from]. */
    private fun skipGroups(params: CsiParams, from: Int, k: Int): Int {
        var idx = from
        repeat(k) { if (idx < params.size) idx = groupEnd(params, idx) }
        return idx
    }

    private fun underlineFromSub(sub: Int): UnderlineStyle = when (sub) {
        0 -> UnderlineStyle.None
        2 -> UnderlineStyle.Double
        3 -> UnderlineStyle.Curly
        4 -> UnderlineStyle.Dotted
        5 -> UnderlineStyle.Dashed
        else -> UnderlineStyle.Single // 1 and unknown substyles
    }

    /**
     * Parses an extended color (38/48/58) whose parameter spans [at] until [end], in either form.
     * Returns the color and where parsing continues: [end] for the colon form, past the consumed
     * legacy `;` parameters otherwise.
     */
    private fun extendedColor(params: CsiParams, at: Int, end: Int): Pair<TermColor, Int> {
        // Colon form: type and components are subparameters within one parameter (38:2:..., 38:5:n).
        if (end - at > 1) return colonColor(params, at, end) to end
        // Legacy `;` form: the next parameter is the type, followed by components as separate parameters.
        fun nextFirst(k: Int): Int? = skipGroups(params, at, k).takeIf { it < params.size }?.let { value(params, it) }
        return when (nextFirst(1)) {
            5 -> (nextFirst(2)?.let { TermColor.indexed(it.coerceIn(0, 255)) } ?: TermColor.Default) to skipGroups(params, at, 3)
            2 -> {
                val r = (nextFirst(2) ?: 0).coerceIn(0, 255)
                val g = (nextFirst(3) ?: 0).coerceIn(0, 255)
                val b = (nextFirst(4) ?: 0).coerceIn(0, 255)
                TermColor.Rgb(r, g, b) to skipGroups(params, at, 5)
            }
            else -> TermColor.Default to end
        }
    }

    /**
     * Color from one parameter's colon subparameters, [at] until [end]: `[sel, 2, cs?, r, g, b]` ->
     * Rgb (the optional colorspace field is skipped when there are 6+ elements), `[sel, 5, n]` ->
     * Indexed. The first element is the 38/48/58 selector.
     */
    private fun colonColor(params: CsiParams, at: Int, end: Int): TermColor {
        val count = end - at
        fun sub(k: Int, default: Int): Int = if (k < count) value(params, at + k) else default
        return when (sub(1, -1)) {
            5 -> TermColor.indexed(sub(2, 0).coerceIn(0, 255))
            2 -> {
                val base = if (count >= 6) 3 else 2 // 38:2:cs:r:g:b vs 38:2:r:g:b
                TermColor.Rgb(
                    sub(base, 0).coerceIn(0, 255),
                    sub(base + 1, 0).coerceIn(0, 255),
                    sub(base + 2, 0).coerceIn(0, 255),
                )
            }
            else -> TermColor.Default
        }
    }
}
