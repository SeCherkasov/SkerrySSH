package app.skerry.ui.terminal

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
