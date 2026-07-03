package app.skerry.shared.terminal

/**
 * Метрики codepoint'ов для сетки терминала — чистые функции без состояния эмулятора
 * (ширина в колонках, комбинируемость, кодировка в текст клетки). Тестируются напрямую.
 */
internal object CharMetrics {

    /**
     * Ширина символа в колонках по упрощённой таблице East Asian Width + emoji: 2 для CJK/Hangul/
     * kana/fullwidth-форм и эмодзи-блоков, иначе 1. Комбинируемые/нулевой ширины пока считаем за 1
     * (отдельный слой объединения — позже).
     */
    fun charWidth(cp: Int): Int = if (
        cp in 0x1100..0x115F ||              // Hangul Jamo
        cp in 0x2E80..0x303E ||              // CJK радикалы, Kangxi, punctuation
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
        cp in 0x20000..0x3FFFD               // CJK Ext B и далее
    ) 2 else 1

    /**
     * Комбинируемый знак нулевой ширины (упрощённая таблица, как [charWidth]): диакритика, ZWJ,
     * вариационные селекторы, комбинируемые знаки для символов. Полная категория Mn/Me — позже.
     * Hangul Jamo (L/V/T-композиция) намеренно НЕ здесь: его ширину держит [charWidth] (как ncurses).
     */
    fun isCombining(cp: Int): Boolean =
        cp == 0x200D ||                  // ZWJ (склейка emoji)
        cp in 0x0300..0x036F ||          // combining diacritical marks
        cp in 0x0483..0x0489 ||          // комбинируемые (кириллица и пр.)
        cp in 0x0591..0x05BD ||          // иврит (огласовки, часть)
        cp in 0x0610..0x061A ||          // арабский (часть)
        cp in 0x064B..0x065F ||          // арабская диакритика
        cp == 0x0670 ||
        cp in 0x06D6..0x06DC ||
        cp in 0x0E31..0x0E3A ||          // тайский (часть)
        cp in 0x1AB0..0x1AFF ||          // combining diacritical marks extended
        cp in 0x1DC0..0x1DFF ||          // combining diacritical marks supplement
        cp in 0x20D0..0x20FF ||          // combining marks for symbols
        cp in 0xFE00..0xFE0F ||          // variation selectors
        cp in 0xFE20..0xFE2F ||          // combining half marks
        cp in 0xE0100..0xE01EF           // variation selectors supplement

    /** Codepoint → строка: BMP — один Char, астральный — суррогатная пара, невалидный — U+FFFD. */
    fun codePointToString(cp: Int): String = when {
        cp in 0..0xFFFF && cp !in 0xD800..0xDFFF -> cp.toChar().toString()
        cp in 0x10000..0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> "�"
    }
}
