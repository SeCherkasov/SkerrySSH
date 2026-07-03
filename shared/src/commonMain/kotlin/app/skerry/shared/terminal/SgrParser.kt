package app.skerry.shared.terminal

/**
 * Разбор и применение SGR (`CSI ... m`) — чистые функции над [TermStyle], без состояния эмулятора;
 * тестируются напрямую. Эмулятор держит текущий стиль и переприсваивает его результатом [apply].
 */
internal object SgrParser {

    /**
     * Разбор SGR-параметров с сохранением структуры субпараметров: каждый `;`-параметр → массив его
     * `:`-частей (modern colon-форма). Пустые части → 0 (например, поле colorspace в `58:2::r:g:b`).
     */
    fun parseParams(raw: String): List<IntArray> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { part ->
            part.split(':').map { it.toIntOrNull() ?: 0 }.toIntArray()
        }
    }

    /**
     * Применяет SGR к [start] и возвращает новый стиль. [params] — список параметров, разделённых `;`;
     * каждый параметр — массив своих `:`-субпараметров (modern colon-форма: `4:3`, `38:2::r:g:b`,
     * `58:5:n`). Расширенные цвета (38/48/58) понимают обе формы: colon-субпараметры внутри одного
     * параметра ИЛИ legacy-форму с `;`, где тип и компоненты идут отдельными параметрами (тогда они
     * доедаются из хвоста списка).
     */
    fun apply(params: List<IntArray>, start: TermStyle): TermStyle {
        if (params.isEmpty()) return TermStyle()
        var style = start
        var i = 0
        while (i < params.size) {
            val param = params[i]
            when (val p = param.firstOrNull() ?: 0) {
                0 -> style = TermStyle() // пустые субпараметры parseParams даёт как 0, не -1
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
        else -> UnderlineStyle.Single // 1 и неизвестные подстили
    }

    /**
     * Разбор расширенного цвета (38/48/58) в обеих формах. Возвращает цвет и число ДОПОЛНИТЕЛЬНЫХ
     * `;`-параметров, съеденных из [params] начиная с [at] (для colon-формы — 0, цвет уже внутри параметра).
     */
    private fun extendedColor(params: List<IntArray>, at: Int): Pair<TermColor, Int> {
        val param = params[at]
        // Colon-форма: тип и компоненты — субпараметры внутри одного параметра (38:2:..., 38:5:n).
        if (param.size > 1) return colonColor(param) to 0
        // Legacy `;`-форма: следующий параметр — тип, далее компоненты отдельными параметрами.
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
     * Цвет из colon-субпараметров одного параметра: `[2, cs?, r, g, b]` → Rgb (необязательное поле
     * colorspace при 6+ элементах пропускаем), `[5, n]` → Indexed. Первый элемент — селектор 38/48/58.
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
