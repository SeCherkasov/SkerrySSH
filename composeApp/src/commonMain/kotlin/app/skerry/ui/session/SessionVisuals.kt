package app.skerry.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.theme.Skerry

/**
 * Session status-dot color (titlebar tab, sidebar host row) by connection state: connected -
 * green, connecting - amber, error - sunset, otherwise (form/no session) - faint. Palette from
 * [D] design tokens.
 */
@Composable
@ReadOnlyComposable
fun sessionDotColor(state: ConnectionUiState?): Color = when (state) {
    is ConnectionUiState.Connected -> Skerry.colors.moss
    ConnectionUiState.Connecting -> Skerry.colors.amber
    is ConnectionUiState.Error -> Skerry.colors.sunset
    // Clean shell exit is faint (not an error); auto-reconnect in progress is amber (like
    // Connecting); exhausted retries are sunset, same as an error (no live session).
    is ConnectionUiState.Disconnected -> when {
        state.cleanExit -> Skerry.colors.faint
        state.reconnecting -> Skerry.colors.amber
        else -> Skerry.colors.sunset
    }
    else -> Skerry.colors.faint
}
