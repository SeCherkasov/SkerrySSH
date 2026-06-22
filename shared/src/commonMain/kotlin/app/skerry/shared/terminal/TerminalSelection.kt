package app.skerry.shared.terminal

/** Пробельная ли ячейка для выделения слова: пустой текст (continuation-клетка) или пробелы. */
private fun TermCell.isBlank(): Boolean = text.isBlank()

/** Позиция ячейки в сетке экрана: строка сверху вниз, колонка слева направо (обе с нуля). */
data class TerminalPos(val row: Int, val col: Int) : Comparable<TerminalPos> {
    override fun compareTo(other: TerminalPos): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/**
 * Выделение «целого слова» под позицией [pos] — для long-press (как в мессенджерах): на строке
 * берётся непрерывный пробег ячеек того же класса, что и под пальцем (слово = непробельные подряд,
 * либо пробельные подряд, если палец на пробеле). Конечная граница эксклюзивна. Пустую строку или
 * выход за сетку отдаёт пустым выделением в [pos].
 */
fun wordSelectionAt(screen: List<List<TermCell>>, pos: TerminalPos): TerminalSelection {
    if (screen.isEmpty()) return TerminalSelection(pos, pos)
    val row = pos.row.coerceIn(0, screen.lastIndex)
    val line = screen[row]
    if (line.isEmpty()) return TerminalSelection(TerminalPos(row, 0), TerminalPos(row, 0))
    val col = pos.col.coerceIn(0, line.lastIndex)
    val wordClass = !line[col].isBlank()
    var start = col
    while (start > 0 && !line[start - 1].isBlank() == wordClass) start--
    var end = col
    while (end < line.lastIndex && !line[end + 1].isBlank() == wordClass) end++
    return TerminalSelection(TerminalPos(row, start), TerminalPos(row, end + 1))
}

/**
 * Выделение целой строки под позицией [pos] — для тройного клика: от первой до последней колонки
 * строки (конец эксклюзивен). Выход за сетку поджимается; пустую сетку отдаёт пустым выделением.
 */
fun lineSelectionAt(screen: List<List<TermCell>>, pos: TerminalPos): TerminalSelection {
    if (screen.isEmpty()) return TerminalSelection(pos, pos)
    val row = pos.row.coerceIn(0, screen.lastIndex)
    return TerminalSelection(TerminalPos(row, 0), TerminalPos(row, screen[row].size))
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
            buildString { for (c in from until to) append(row[c].text) }.trimEnd()
        }
    }
}
