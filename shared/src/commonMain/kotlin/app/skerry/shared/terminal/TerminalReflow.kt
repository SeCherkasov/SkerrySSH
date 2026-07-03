package app.skerry.shared.terminal

/**
 * Reflow основного буфера при ресайзе — чистые функции над списками [TermRow], без состояния
 * эмулятора: мягко-перенесённые (`wrapped`) физические строки склеиваются в логические и
 * переразбиваются по новой ширине, курсор едет со своим текстом. Тестируется напрямую и через
 * resize-тесты эмулятора.
 */
internal object TerminalReflow {

    /** Результат reflow: новые scrollback/grid и позиция курсора в координатах нового grid. */
    class Result(
        val scrollback: List<TermRow>,
        val grid: MutableList<TermRow>,
        val cursorRow: Int,
        val cursorCol: Int,
    )

    /**
     * Переукладка основного буфера под ширину [nc]/высоту [nr]. [src] — все физические строки
     * (scrollback + экран); нижние [nr] результата идут в grid, остальные — в scrollback (с обрезкой
     * по [maxScrollback]). [cursorAbs]/[cursorCol] — позиция курсора в [src] (значима при
     * [trackCursor]); [rowsBelowCursor] — строк видимого экрана ниже курсора ДО ресайза (пустое
     * место под курсором — содержимое, см. шаг 4).
     */
    fun reflow(
        src: List<TermRow>,
        nc: Int,
        nr: Int,
        maxScrollback: Int,
        cursorAbs: Int,
        cursorCol: Int,
        rowsBelowCursor: Int,
        trackCursor: Boolean,
    ): Result {
        val blank = TermCell(" ")

        // 1-2. Группируем физические строки в логические (цепочки по wrapped) и находим логический
        //      индекс/колонку курсора.
        val logicals = ArrayList<MutableList<TermCell>>()
        var curLogIndex = 0
        var curLogCol = cursorCol
        run {
            var i = 0
            var abs = 0
            while (i < src.size) {
                val cells = ArrayList<TermCell>()
                while (true) {
                    val row = src[i]
                    if (trackCursor && abs == cursorAbs) {
                        curLogIndex = logicals.size
                        curLogCol = cells.size + cursorCol
                    }
                    cells.addAll(row)
                    val wrapped = row.wrapped
                    i++; abs++
                    if (!wrapped || i >= src.size) break
                }
                logicals.add(cells)
            }
        }

        // 3. Каждую логическую тримим по хвостовым дефолтным пробелам и переразбиваем по nc.
        val out = ArrayList<TermRow>(logicals.size)
        var cursorAbsNew = 0
        var cursorColNew = 0
        logicals.forEachIndexed { idx, cells ->
            var len = cells.size
            while (len > 0 && cells[len - 1] == blank) len--
            val isCursorLine = trackCursor && idx == curLogIndex
            // Гарантируем, что колонка курсора достижима после трима (курсор может стоять за текстом).
            if (isCursorLine) len = maxOf(len, curLogCol + 1)
            val logical = if (len == cells.size) cells else cells.subList(0, len.coerceAtMost(cells.size))
            val base = out.size
            // Позицию курсора берём из реального прохода разбиения (а не из curLogCol/nc): wide-символы
            // могут давать строки с nc-1 видимыми ячейками, и арифметика бы съезжала.
            val cursorOut = IntArray(2) { -1 }
            out.addAll(splitLogical(logical, nc, blank, if (isCursorLine) curLogCol else -1, cursorOut))
            if (isCursorLine && cursorOut[0] >= 0) {
                cursorAbsNew = base + cursorOut[0]
                cursorColNew = cursorOut[1]
            }
        }

        // 4. Отбрасываем незначимый пустой хвост (неиспользованное место внизу экрана не должно уходить
        //    в scrollback): держим строки до последней непустой и до строки курсора включительно. Но
        //    пустое пространство ВИДИМОГО экрана под курсором — это содержимое (после `clear`/`Ctrl+L`
        //    под prompt'ом лежит пустой экран, а история уже в scrollback): его сохраняем, иначе reflow
        //    схлопнет хвост и втянет историю обратно на видимый экран. Кап nr-1 — курсор не уедет за низ.
        var significant = out.size
        while (significant > 0 && out[significant - 1].all { it == blank }) significant--
        if (trackCursor) {
            val belowCursorInScreen = rowsBelowCursor.coerceIn(0, nr - 1)
            significant = maxOf(significant, cursorAbsNew + 1 + belowCursorInScreen)
        }
        significant = significant.coerceAtLeast(1).coerceAtMost(out.size)
        val kept = out.subList(0, significant)

        // 5. Делим на grid (нижние nr) и scrollback (верх), дополняя пустыми при нехватке.
        val gridStart: Int
        val newGrid: MutableList<TermRow>
        if (kept.size >= nr) {
            gridStart = kept.size - nr
            newGrid = ArrayList(kept.subList(gridStart, kept.size))
        } else {
            gridStart = 0
            newGrid = ArrayList(kept)
            repeat(nr - kept.size) { newGrid.add(TermRow(MutableList(nc) { blank })) }
        }
        val newScroll = if (gridStart > 0) kept.subList(0, gridStart) else emptyList()
        val drop = (newScroll.size - maxScrollback).coerceAtLeast(0)

        return Result(
            scrollback = if (drop > 0) newScroll.subList(drop, newScroll.size) else newScroll,
            grid = newGrid,
            cursorRow = cursorAbsNew - gridStart,
            cursorCol = cursorColNew,
        )
    }

    /**
     * Разбивает логическую строку [cells] на физические шириной [nc], не разрывая широкие символы
     * (Wide+Continuation не должны попадать в разные строки). Все, кроме последней, помечаются
     * `wrapped = true`; каждая дополняется до [nc] нейтральным [pad]. Пустая логическая → одна пустая строка.
     *
     * Если [cursorCol] >= 0, в [cursorOut] кладётся позиция курсора в результате: `[0]` — индекс строки
     * (внутри возвращённого списка), `[1]` — колонка в ней. Позиция берётся прямым проходом, поэтому
     * корректна и при early-break на широком символе (когда строка несёт nc-1 видимых ячеек).
     */
    private fun splitLogical(
        cells: List<TermCell>,
        nc: Int,
        pad: TermCell,
        cursorCol: Int = -1,
        cursorOut: IntArray? = null,
    ): List<TermRow> {
        if (cells.isEmpty()) {
            if (cursorCol >= 0 && cursorOut != null) { cursorOut[0] = 0; cursorOut[1] = cursorCol.coerceIn(0, nc - 1) }
            return listOf(TermRow(MutableList(nc) { pad }))
        }
        val out = ArrayList<TermRow>()
        var idx = 0
        while (idx < cells.size) {
            val chunk = ArrayList<TermCell>(nc)
            while (idx < cells.size && chunk.size < nc) {
                val cell = cells[idx]
                // Широкий символ не делим: если пара не влезает в остаток непустого chunk — оборвать раньше.
                if (cell.width == CellWidth.Wide && chunk.isNotEmpty() && chunk.size + 2 > nc) break
                if (idx == cursorCol && cursorOut != null) { cursorOut[0] = out.size; cursorOut[1] = chunk.size }
                chunk.add(cell); idx++
            }
            while (chunk.size < nc) chunk.add(pad)
            out.add(TermRow(chunk, wrapped = idx < cells.size))
        }
        return out
    }
}
