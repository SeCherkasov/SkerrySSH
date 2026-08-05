package app.skerry.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import app.skerry.ui.connection.ConnectionUiState
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.term_status_connected
import app.skerry.ui.generated.resources.term_status_connecting
import app.skerry.ui.generated.resources.term_status_disconnected
import app.skerry.ui.generated.resources.term_status_no_session
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

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

/**
 * The same status in words. The dot says it in colour alone, which is nothing to a screen reader and
 * little to a red-green colourblind eye comparing "live" against "failed" down a list; attaching
 * this as the dot's content description folds the state into the row's own announcement without
 * adding a focus stop. Reuses the terminal header's vocabulary so one session never reads as two
 * different things in two places.
 */
@Composable
fun sessionStatusText(status: SessionStatus): String = stringResource(
    when (status) {
        SessionStatus.Live -> Res.string.term_status_connected
        SessionStatus.Connecting -> Res.string.term_status_connecting
        SessionStatus.Failed -> Res.string.term_status_disconnected
        SessionStatus.Idle -> Res.string.term_status_no_session
    },
)
