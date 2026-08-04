package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.design.HLine
import app.skerry.ui.design.VLine
import app.skerry.ui.theme.Skerry

/**
 * The window's own furniture around the work area: titlebar → rail + viewport → status bar.
 *
 * With [bare] it draws none of it and hands the whole window to [Viewport] — a live remote desktop
 * shown full-window, where the only chrome left is the session's own floating bar
 * ([app.skerry.ui.remote.RemoteDesktopBar]). The OS window follows the same flag through
 * [WindowChrome.setFullscreen]: a stripped app window still sitting inside a desktop would be half
 * the mode. Leaving the composition (the vault locking) takes the window back out of full screen —
 * an undecorated fullscreen window with the unlock screen in it could be neither moved nor closed.
 */
@Composable
internal fun DesktopShell(
    state: DesktopDesignState,
    onLock: (() -> Unit)?,
    windowChrome: WindowChrome?,
    bare: Boolean,
) {
    DisposableEffect(bare, windowChrome) {
        windowChrome?.setFullscreen(bare)
        onDispose { windowChrome?.setFullscreen(false) }
    }
    Column(Modifier.fillMaxSize()) {
        if (!bare) {
            TitleBar(state, onLock, windowChrome)
            HLine()
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (!bare) {
                IconRail(state)
                VLine(Skerry.colors.line)
            }
            Box(Modifier.weight(1f).fillMaxHeight()) { Viewport(state) }
        }
        if (!bare) {
            HLine()
            StatusBar()
        }
    }
}
