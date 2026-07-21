package app.skerry.ui.immersive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.skerry.ui.secure.WindowBridge

/**
 * Android: hide the system bars, leaving them reachable by a swipe from the edge
 * (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) so the user is never trapped in the VNC screen. The window
 * comes from [WindowBridge], installed by MainActivity; without it there is nothing to do (silently
 * — unlike FLAG_SECURE, a missing immersive mode costs pixels, not safety).
 */
actual fun applyPlatformImmersive(hidden: Boolean) {
    val window = WindowBridge.window() ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (hidden) {
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
        controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * The *IgnoringVisibility* insets are exactly the sizes the bars would have if shown, which is what
 * this needs — the live insets are zero while they are hidden. The display cutout is not part of
 * them and is added separately: in landscape it is a notch on the side, not under a bar.
 *
 * Only the requested sides are padded: a bar pinned to the top has no use for the navigation bar's
 * height at its bottom, and vice versa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
actual fun Modifier.hiddenSystemBarsPadding(top: Boolean, bottom: Boolean): Modifier {
    var sides = WindowInsetsSides.Horizontal
    if (top) sides += WindowInsetsSides.Top
    if (bottom) sides += WindowInsetsSides.Bottom
    return windowInsetsPadding(
        WindowInsets.statusBarsIgnoringVisibility
            .union(WindowInsets.navigationBarsIgnoringVisibility)
            .union(WindowInsets.displayCutout)
            .only(sides),
    )
}
