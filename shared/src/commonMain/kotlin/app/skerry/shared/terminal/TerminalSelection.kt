package app.skerry.shared.terminal

/** Позиция ячейки в сетке экрана: строка сверху вниз, колонка слева направо (обе с нуля). */
data class TerminalPos(val row: Int, val col: Int) : Comparable<TerminalPos> {
    override fun compareTo(other: TerminalPos): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/**
 * Линейное выделение текста на экране терминала — как в обычных эмуляторах: от точки `anchor`
 * (где нажали) до `focus` (где сейчас курсор мыши), с переносом через концы строк (не блочное).
 * Чистая модель без Compose: оперирует сеткой `List<List<TermCell>>`, поэтому тестируется напрямую.
 *
 * Диапазон полуоткрытый: начальная ячейка включается, конечная — нет (как выделение текста, где
 * курсор стоит «перед» символом). Направление перетаскивания не важно — границы нормализуются.
 */
data class TerminalSelection(val anchor: TerminalPos, val focus: TerminalPos) {

    /** Верхняя-левая граница (включительно). */
    val start: TerminalPos get() = minOf(anchor, focus)

    /** Нижняя-правая граница (эксклюзивно). */
    val end: TerminalPos get() = maxOf(anchor, focus)

    /** Пусто, когда якорь и фокус совпали — выделять нечего. */
    val isEmpty: Boolean get() = start == end

    /** Входит ли ячейка (row, col) в выделение по линейной семантике. */
    fun contains(row: Int, col: Int): Boolean {
        if (isEmpty) return false
        val s = start
        val e = end
        if (row < s.row || row > e.row) return false
        val fromOk = row > s.row || col >= s.col
        val toOk = row < e.row || col < e.col
        return fromOk && toOk
    }

    /**
     * Текст выделения: на каждой охваченной строке берётся её отрезок, хвостовые пробелы
     * обрезаются (как в терминалах при копировании), строки соединяются `\n`. Колонки за концом
     * строки безопасно поджимаются к её длине.
     */
    fun extract(screen: List<List<TermCell>>): String {
        if (isEmpty) return ""
        val s = start
        val e = end
        val firstRow = s.row.coerceAtLeast(0)
        val lastRow = e.row.coerceAtMost(screen.lastIndex)
        if (firstRow > lastRow) return ""
        return (firstRow..lastRow).joinToString("\n") { r ->
            val row = screen[r]
            val from = (if (r == s.row) s.col else 0).coerceIn(0, row.size)
            val to = (if (r == e.row) e.col else row.size).coerceIn(from, row.size)
            buildString { for (c in from until to) append(row[c].char) }.trimEnd()
        }
    }
}
