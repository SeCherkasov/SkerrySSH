package app.skerry.ui.session

import androidx.compose.ui.graphics.Color
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.D

/**
 * Цвет статус-точки сессии (вкладка titlebar, строка хоста в сайдбаре) по состоянию соединения:
 * подключено — зелёный, идёт connect — янтарный, ошибка — закатный, иначе (форма/нет сессии) —
 * приглушённый. Палитра — токены [D] макета.
 */
fun sessionDotColor(state: ConnectionUiState?): Color = when (state) {
    is ConnectionUiState.Connected -> D.moss
    ConnectionUiState.Connecting -> D.amber
    is ConnectionUiState.Error -> D.sunset
    // Закрытие сессии: штатный выход shell (`exit`) — приглушённый (это не ошибка); пока идёт
    // авто-реконнект — янтарный (как Connecting, «работаем над этим»); когда попытки исчерпаны —
    // закатный, как ошибка (живой сессии нет).
    is ConnectionUiState.Disconnected -> when {
        state.cleanExit -> D.faint
        state.reconnecting -> D.amber
        else -> D.sunset
    }
    else -> D.faint
}
