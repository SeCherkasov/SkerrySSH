package app.skerry.ui.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.ui.design.IconBtn
import app.skerry.ui.theme.Skerry

/**
 * Hooks into the undecorated desktop window: the OS titlebar is replaced by the app's own chrome,
 * so dragging and the minimize/maximize/close buttons are drawn by the UI itself ([WindowButtons]
 * in the titlebar, [LockWindowChrome] over the vault gate screens). Constructed by desktop `main`
 * from the real `WindowState`; `null` — a decorated window (previews, offscreen render).
 */
class WindowChrome(
    /** Reads snapshot state (WindowState.placement), so callers recompose on change. */
    val isMaximized: @Composable () -> Boolean,
    val onMinimize: () -> Unit,
    val onToggleMaximize: () -> Unit,
    val onClose: () -> Unit,
    /** Wraps [content] in a window-drag area (empty space drags, double-click toggles maximize). */
    val dragArea: @Composable (content: @Composable () -> Unit) -> Unit,
)

/** Minimize / maximize-restore / close buttons drawn in the app palette. */
@Composable
fun WindowButtons(chrome: WindowChrome, modifier: Modifier = Modifier) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconBtn("remove", onClick = chrome.onMinimize, icon = 15.sp, hoverTint = Skerry.colors.text)
        IconBtn(if (chrome.isMaximized()) "filter_none" else "crop_square", onClick = chrome.onToggleMaximize, icon = 15.sp, hoverTint = Skerry.colors.text)
        // The close button warns with the storm accent on hover, like native chrome does.
        IconBtn("close", onClick = chrome.onClose, icon = 15.sp, hoverBg = Skerry.colors.storm, hoverTint = Skerry.colors.white)
    }
}

/**
 * Window chrome for full-window screens outside the main titlebar (vault gate: create/unlock/
 * corrupted/reset/sync-onboarding): the whole screen becomes the drag area and the window buttons
 * float in the top-right corner — otherwise an undecorated window could be neither moved nor
 * closed while locked. With `chrome == null` (decorated window) it renders [content] as-is.
 */
@Composable
fun LockWindowChrome(chrome: WindowChrome?, content: @Composable () -> Unit) {
    if (chrome == null) {
        content()
        return
    }
    chrome.dragArea {
        Box(Modifier.fillMaxSize()) {
            content()
            WindowButtons(chrome, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
    }
}
