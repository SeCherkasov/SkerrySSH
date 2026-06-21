package app.skerry.ui.design

import androidx.compose.ui.graphics.Color
import app.skerry.ui.connection.ConnectionUiState

/**
 * Цвет статус-точки сессии (вкладка titlebar, строка хоста в сайдбаре) по состоянию соединения:
 * подключено — зелёный, идёт connect — янтарный, ошибка — закатный, иначе (форма/нет сессии) —
 * приглушённый. Палитра — токены [D] макета.
 */
fun sessionDotColor(state: ConnectionUiState?): Color = when (state) {
    is ConnectionUiState.Connected -> D.moss
    ConnectionUiState.Connecting -> D.amber
    is ConnectionUiState.Error -> D.sunset
    else -> D.faint
}
