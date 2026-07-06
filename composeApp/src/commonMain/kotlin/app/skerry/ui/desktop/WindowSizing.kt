package app.skerry.ui.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Minimum window size keeping the rail, hosts sidebar, and terminal/panels readable. */
val MIN_WINDOW: DpSize = DpSize(1100.dp, 720.dp)

/** Maximum window size; the layout targets a working size, not a fullscreen 4K/ultrawide stretch. */
val MAX_WINDOW: DpSize = DpSize(1680.dp, 1050.dp)

/** Default fraction of the available screen area the window occupies. */
private const val SCREEN_FRACTION = 0.9f

/**
 * Computes a window size for the available [screen] area: targets [SCREEN_FRACTION] of the screen,
 * clamped to [MIN_WINDOW]..[MAX_WINDOW], never exceeding the screen itself.
 */
fun optimalWindowSize(screen: DpSize): DpSize = DpSize(
    width = (screen.width * SCREEN_FRACTION)
        .coerceIn(MIN_WINDOW.width, MAX_WINDOW.width)
        .coerceAtMost(screen.width),
    height = (screen.height * SCREEN_FRACTION)
        .coerceIn(MIN_WINDOW.height, MAX_WINDOW.height)
        .coerceAtMost(screen.height),
)
