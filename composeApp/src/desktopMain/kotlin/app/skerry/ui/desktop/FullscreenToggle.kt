package app.skerry.ui.desktop

import androidx.compose.ui.window.WindowPlacement

/**
 * Puts the window on the whole screen and takes it back off, remembering where it came from: a
 * remote desktop shown full screen has to end up floating or maximized again exactly as the user
 * left it.
 *
 * Idempotent on purpose — the caller is a Compose effect that can run again for the same value, and
 * a second "go fullscreen" must not record Fullscreen as the placement to return to.
 */
internal class FullscreenToggle(
    private val placement: () -> WindowPlacement,
    private val setPlacement: (WindowPlacement) -> Unit,
) {
    private var restoreTo: WindowPlacement? = null

    fun apply(fullscreen: Boolean) {
        val current = placement()
        if (fullscreen) {
            if (current == WindowPlacement.Fullscreen) return
            restoreTo = current
            setPlacement(WindowPlacement.Fullscreen)
        } else {
            if (current != WindowPlacement.Fullscreen) return
            // Floating when we did not put it there ourselves (the window manager's own fullscreen
            // key): leaving it full screen would strand a window with no titlebar of its own.
            setPlacement(restoreTo ?: WindowPlacement.Floating)
            restoreTo = null
        }
    }
}
