package app.skerry.ui.terminal

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.skerry.shared.terminal.TerminalPos
import app.skerry.shared.terminal.TerminalSelection

/**
 * Размеры моноширинной ячейки терминала в пикселях. Замеряются один раз на стороне UI
 * (advance моно-символа + lineHeight) и передаются сюда, чтобы перевод координат был чистой
 * тестируемой арифметикой.
 */
data class TerminalMetrics(
    val cellWidth: Float,
    val cellHeight: Float,
)

/**
 * Перевод позиции указателя в ячейку сетки. Координаты приходят уже в системе координат контента
 * терминала: `pointerInput` стоит в цепочке модификаторов после `verticalScroll` и `padding`,
 * поэтому Compose отдаёт offset относительно текста (с учётом прокрутки и без отступа) — остаётся
 * только разделить на размер ячейки. Строка/колонка берутся floor'ом; отрицательные координаты
 * поджимаются к началу. Строка сверху не ограничивается — вызывающий сопоставляет её с экраном
 * (extract сам поджимает выход за последнюю строку).
 */
fun cellAtOffset(x: Float, y: Float, metrics: TerminalMetrics): TerminalPos {
    val col = (x / metrics.cellWidth).toInt().coerceAtLeast(0)
    val row = (y / metrics.cellHeight).toInt().coerceAtLeast(0)
    return TerminalPos(row, col)
}

/**
 * Прямоугольник стартовой ячейки выделения в пикселях контента — якорь для системного текстового
 * меню (`LocalTextToolbar.showMenu` ждёт rect, над которым показать «Copy»). Берётся нормализованная
 * верхняя-левая граница [TerminalSelection.start], UI мапит этот rect в координаты окна.
 */
fun selectionAnchorRect(selection: TerminalSelection, metrics: TerminalMetrics): Rect {
    val s = selection.start
    val left = s.col * metrics.cellWidth
    val top = s.row * metrics.cellHeight
    return Rect(left = left, top = top, right = left + metrics.cellWidth, bottom = top + metrics.cellHeight)
}

/** Какую границу выделения тянет тач-маркер: верхнюю-левую (start) или нижнюю-правую (end). */
enum class SelectionHandle { START, END }

/**
 * Якоря двух тач-маркеров выделения в пикселях контента — точки, к которым «подвешиваются»
 * перетаскиваемые «капли» (как в мессенджерах). Берутся нижние углы нормализованных границ:
 * start — нижний-левый угол первой ячейки, end — нижний-правый край последнего символа
 * ([TerminalSelection.end] эксклюзивен, поэтому его колонка и есть правая грань). UI рисует «каплю»
 * под якорем и мапит координату в окно с учётом прокрутки.
 */
fun selectionHandleAnchors(selection: TerminalSelection, metrics: TerminalMetrics): Pair<Offset, Offset> {
    val s = selection.start
    val e = selection.end
    val start = Offset(s.col * metrics.cellWidth, (s.row + 1) * metrics.cellHeight)
    val end = Offset(e.col * metrics.cellWidth, (e.row + 1) * metrics.cellHeight)
    return start to end
}

/**
 * Попал ли палец в один из тач-маркеров выделения. Сравнивает [point] (координаты контента) с
 * якорями ручек ([selectionHandleAnchors]) в радиусе [radiusPx]; если в радиусе обе — возвращает
 * ближайшую, иначе ту, что в радиусе, иначе `null` (жест уходит в long-press/прокрутку).
 */
fun hitTestSelectionHandle(
    point: Offset,
    selection: TerminalSelection,
    metrics: TerminalMetrics,
    radiusPx: Float,
): SelectionHandle? {
    if (selection.isEmpty) return null
    val (start, end) = selectionHandleAnchors(selection, metrics)
    val dStart = (point - start).getDistance()
    val dEnd = (point - end).getDistance()
    val startHit = dStart <= radiusPx
    val endHit = dEnd <= radiusPx
    return when {
        startHit && endHit -> if (dStart <= dEnd) SelectionHandle.START else SelectionHandle.END
        startHit -> SelectionHandle.START
        endHit -> SelectionHandle.END
        else -> null
    }
}
