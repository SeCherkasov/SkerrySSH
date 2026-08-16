package app.skerry.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import app.skerry.ui.design.HLine
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_clipboard
import app.skerry.ui.generated.resources.rd_ctrl_alt_del
import app.skerry.ui.generated.resources.rd_ctrl_alt_del_view_only
import app.skerry.ui.generated.resources.rd_panel_hide
import app.skerry.ui.generated.resources.rd_screenshot
import app.skerry.ui.generated.resources.rd_settings
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** Width of the session panel: a strip of icons. */
val REMOTE_PANEL_WIDTH: Dp = 44.dp

/**
 * The session panel beside a live remote desktop on a phone: the actions that belong to the session
 * rather than to the picture. Icons only, each naming itself on a long press — a column of labels
 * would leave the desktop a strip.
 *
 * The desktop shows the same actions as a floating bar over the picture ([RemoteDesktopBar]); both
 * open the menus in `RemoteDesktopMenus` and `RemoteClipboardMenu`.
 */
@Composable
fun RemoteDesktopPanel(
    screen: RemoteDesktopScreenState,
    // Remembered by the screen, not by the panel: the panel slides away, a save in its scope would
    // die with it. See [rememberScreenshotAction].
    screenshot: ScreenshotAction,
    clipboardActions: ClipboardActions,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    showResetZoom: Boolean = false,
) {
    var settingsOpen by remember { mutableStateOf(false) }
    var clipboardOpen by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val menuPosition = remember(gap) { leftOfAnchor(gap) }

    Column(
        modifier.width(REMOTE_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        RemoteIconButton("chevron_right", stringResource(Res.string.rd_panel_hide), onClick = onHide)
        HLine(modifier = Modifier.padding(vertical = 2.dp))

        RemoteIconButton(
            "photo_camera",
            stringResource(Res.string.rd_screenshot),
            forcedTooltip = screenshot.note,
            onClick = screenshot.take,
        )

        // The keyboard cannot produce this one: the local OS takes Ctrl+Alt+Del before any app sees it.
        RemoteIconButton(
            "keyboard",
            stringResource(Res.string.rd_ctrl_alt_del),
            enabled = !screen.viewOnly,
            disabledTooltip = stringResource(Res.string.rd_ctrl_alt_del_view_only),
            onClick = screen::sendCtrlAltDel,
        )

        if (screen.capabilities.clipboard) {
            RemoteMenuHost(
                expanded = clipboardOpen,
                onDismiss = { clipboardOpen = false },
                onToggle = { clipboardOpen = !clipboardOpen },
                position = menuPosition,
                trigger = { toggle ->
                    RemoteIconButton(
                        "content_paste",
                        stringResource(Res.string.rd_clipboard),
                        active = clipboardOpen,
                        onClick = toggle,
                    )
                },
            ) {
                ClipboardMenu(screen, clipboardActions)
            }
        }

        RemoteMenuHost(
            expanded = settingsOpen,
            onDismiss = { settingsOpen = false },
            onToggle = { settingsOpen = !settingsOpen },
            position = menuPosition,
            trigger = { toggle ->
                RemoteIconButton("tune", stringResource(Res.string.rd_settings), active = settingsOpen, onClick = toggle)
            },
        ) {
            DisplayMenu(screen, showResetZoom = showResetZoom, showAudio = true)
        }
    }
}

/**
 * Opens a menu to the left of its button. The shared [app.skerry.ui.design.AnchoredDropdown] drops
 * straight down from the anchor's left edge, which against the right screen edge would put the menu
 * off-screen.
 */
internal fun leftOfAnchor(gap: Int): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val left = anchorBounds.left - popupContentSize.width - gap
        return IntOffset(
            // Falls back to the right of the anchor on a window too narrow for either side, where a
            // negative x would clip the menu against the window edge.
            x = if (left >= 0) left else (anchorBounds.right + gap).coerceAtMost(
                (windowSize.width - popupContentSize.width).coerceAtLeast(0),
            ),
            y = anchorBounds.top.coerceAtMost(
                (windowSize.height - popupContentSize.height).coerceAtLeast(0),
            ),
        )
    }
}
