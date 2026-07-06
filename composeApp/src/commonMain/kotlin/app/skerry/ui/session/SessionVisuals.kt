package app.skerry.ui.session

import androidx.compose.ui.graphics.Color
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.design.D

/**
 * Session status-dot color (titlebar tab, sidebar host row) by connection state: connected -
 * green, connecting - amber, error - sunset, otherwise (form/no session) - faint. Palette from
 * [D] design tokens.
 */
fun sessionDotColor(state: ConnectionUiState?): Color = when (state) {
    is ConnectionUiState.Connected -> D.moss
    ConnectionUiState.Connecting -> D.amber
    is ConnectionUiState.Error -> D.sunset
    // Clean shell exit is faint (not an error); auto-reconnect in progress is amber (like
    // Connecting); exhausted retries are sunset, same as an error (no live session).
    is ConnectionUiState.Disconnected -> when {
        state.cleanExit -> D.faint
        state.reconnecting -> D.amber
        else -> D.sunset
    }
    else -> D.faint
}
