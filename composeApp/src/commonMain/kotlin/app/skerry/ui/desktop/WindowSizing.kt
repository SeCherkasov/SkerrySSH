package app.skerry.ui.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Minimum window size keeping the rail, hosts sidebar, and terminal/panels readable. */
val MIN_WINDOW: DpSize = DpSize(1100.dp, 720.dp)

/** Maximum window size; the layout targets a working size, not a fullscreen 4K/ultrawide stretch. */
val MAX_WINDOW: DpSize = DpSize(1680.dp, 1050.dp)

/**
 * Floor a resize may reach on the available [screen] area: [MIN_WINDOW], capped by the screen
 * itself — a floor larger than the display would push the window past its edges instead of
 * letting it shrink. Fed to the window manager as a size hint, so a WM-driven resize (keyboard,
 * tiling, super+drag) is bounded the same way the app's own resize handles are.
 */
fun minimumWindowSize(screen: DpSize): DpSize = DpSize(
    width = MIN_WINDOW.width.coerceAtMost(screen.width),
    height = MIN_WINDOW.height.coerceAtMost(screen.height),
)

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
