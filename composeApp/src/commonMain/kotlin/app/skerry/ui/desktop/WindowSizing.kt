package app.skerry.ui.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Минимум, ниже которого окно не складывается на нормальных дисплеях: rail + сайдбар хостов +
 * терминал/панели остаются читаемыми. На совсем маленьких экранах
 * окно может оказаться уже минимума — оно подгоняется под экран, чтобы не вылезать за края.
 */
val MIN_WINDOW: DpSize = DpSize(1100.dp, 720.dp)

/**
 * Потолок: на 4K/ультравайде окно не должно растягиваться во весь экран — макет рассчитан на
 * «рабочий» размер, дальше только пустые поля. Базовый дизайн снимается в 1280×820.
 */
val MAX_WINDOW: DpSize = DpSize(1680.dp, 1050.dp)

/** Доля доступной области экрана, которую окно занимает по умолчанию. */
private const val SCREEN_FRACTION = 0.9f

/**
 * Подбирает размер окна под доступную область экрана [screen] (без таскбара): целится в
 * [SCREEN_FRACTION] экрана, зажимает в диапазон [MIN_WINDOW]…[MAX_WINDOW], но никогда не превышает
 * сам экран — на маленьких дисплеях окно сжимается, чтобы помещаться целиком.
 */
fun optimalWindowSize(screen: DpSize): DpSize = DpSize(
    width = (screen.width * SCREEN_FRACTION)
        .coerceIn(MIN_WINDOW.width, MAX_WINDOW.width)
        .coerceAtMost(screen.width),
    height = (screen.height * SCREEN_FRACTION)
        .coerceIn(MIN_WINDOW.height, MAX_WINDOW.height)
        .coerceAtMost(screen.height),
)
