package app.skerry.shared.terminal

/**
 * Parses and applies SGR (`CSI ... m`) — pure functions over [TermStyle], no emulator state;
 * tested directly. The emulator holds the current style and reassigns it with the result of [apply].
 */
internal object SgrParser {

    /**
     * Parses SGR parameters preserving subparameter structure: each `;`-separated parameter
     * becomes an array of its `:`-parts (modern colon form). Empty parts become 0 (e.g. the
     * colorspace field in `58:2::r:g:b`).
     */
    fun parseParams(raw: String): List<IntArray> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { part ->
            part.split(':').map { it.toIntOrNull() ?: 0 }.toIntArray()
        }
    }

    /**
     * Applies SGR to [start] and returns the new style. [params] is a list of `;`-separated
     * parameters; each parameter is an array of its `:`-subparameters (modern colon form: `4:3`,
     * `38:2::r:g:b`, `58:5:n`). Extended colors (38/48/58) accept both forms: colon subparameters
     * within one parameter, or the legacy `;` form where type and components are separate
     * parameters (consumed from the tail of the list).
     */
    fun apply(params: List<IntArray>, start: TermStyle): TermStyle {
        if (params.isEmpty()) return TermStyle()
        var style = start
        var i = 0
        while (i < params.size) {
            val param = params[i]
            when (val p = param.firstOrNull() ?: 0) {
                0 -> style = TermStyle() // parseParams yields empty subparameters as 0, not -1
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underlineStyle = underlineFromSub(param.getOrElse(1) { 1 }))
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
                in 30..37 -> style = style.copy(fg = TermColor.Indexed(p - 30))
                38 -> { val (col, used) = extendedColor(params, i); style = style.copy(fg = col); i += used }
                39 -> style = style.copy(fg = TermColor.Default)
                in 40..47 -> style = style.copy(bg = TermColor.Indexed(p - 40))
                48 -> { val (col, used) = extendedColor(params, i); style = style.copy(bg = col); i += used }
                49 -> style = style.copy(bg = TermColor.Default)
                58 -> { val (col, used) = extendedColor(params, i); style = style.copy(underlineColor = col); i += used }
                59 -> style = style.copy(underlineColor = TermColor.Default)
                in 90..97 -> style = style.copy(fg = TermColor.Indexed(8 + p - 90))
                in 100..107 -> style = style.copy(bg = TermColor.Indexed(8 + p - 100))
            }
            i++
        }
        return style
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
     * Parses an extended color (38/48/58) in either form. Returns the color and the number of
     * EXTRA `;`-parameters consumed from [params] starting at [at] (0 for the colon form, since
     * the color is already within the parameter).
     */
    private fun extendedColor(params: List<IntArray>, at: Int): Pair<TermColor, Int> {
        val param = params[at]
        // Colon form: type and components are subparameters within one parameter (38:2:..., 38:5:n).
        if (param.size > 1) return colonColor(param) to 0
        // Legacy `;` form: the next parameter is the type, followed by components as separate parameters.
        fun nextFirst(k: Int) = params.getOrNull(at + k)?.firstOrNull()
        return when (nextFirst(1)) {
            5 -> (nextFirst(2)?.let { TermColor.Indexed(it.coerceIn(0, 255)) } ?: TermColor.Default) to 2
            2 -> {
                val r = (nextFirst(2) ?: 0).coerceIn(0, 255)
                val g = (nextFirst(3) ?: 0).coerceIn(0, 255)
                val b = (nextFirst(4) ?: 0).coerceIn(0, 255)
                TermColor.Rgb(r, g, b) to 4
            }
            else -> TermColor.Default to 0
        }
    }

    /**
     * Color from a single parameter's colon subparameters: `[2, cs?, r, g, b]` -> Rgb (the
     * optional colorspace field is skipped when there are 6+ elements), `[5, n]` -> Indexed. The
     * first element is the 38/48/58 selector.
     */
    private fun colonColor(param: IntArray): TermColor = when (param.getOrElse(1) { -1 }) {
        5 -> TermColor.Indexed(param.getOrElse(2) { 0 }.coerceIn(0, 255))
        2 -> {
            val base = if (param.size >= 6) 3 else 2 // 38:2:cs:r:g:b vs 38:2:r:g:b
            TermColor.Rgb(
                param.getOrElse(base) { 0 }.coerceIn(0, 255),
                param.getOrElse(base + 1) { 0 }.coerceIn(0, 255),
                param.getOrElse(base + 2) { 0 }.coerceIn(0, 255),
            )
        }
        else -> TermColor.Default
    }
}
