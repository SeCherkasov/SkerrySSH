package app.skerry.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.theme.Skerry

/**
 * Session status-dot color (titlebar tab, sidebar host row): live - green, connecting - amber,
 * failed - sunset, idle (form/clean exit/no session) - faint. Palette from [D] design tokens.
 */
@Composable
@ReadOnlyComposable
fun sessionDotColor(status: SessionStatus): Color = when (status) {
    SessionStatus.Live -> Skerry.colors.moss
    SessionStatus.Connecting -> Skerry.colors.amber
    SessionStatus.Failed -> Skerry.colors.sunset
    SessionStatus.Idle -> Skerry.colors.faint
}

/** Same dot for a terminal connection read straight off its controller (panes, info panel). */
@Composable
@ReadOnlyComposable
fun sessionDotColor(state: ConnectionUiState?): Color = sessionDotColor(state.asSessionStatus())
