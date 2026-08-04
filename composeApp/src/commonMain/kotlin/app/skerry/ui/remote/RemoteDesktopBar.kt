package app.skerry.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_audio
import app.skerry.ui.generated.resources.rd_audio_device_lost
import app.skerry.ui.generated.resources.rd_bar_hide
import app.skerry.ui.generated.resources.rd_bar_pin
import app.skerry.ui.generated.resources.rd_bar_unpin
import app.skerry.ui.generated.resources.rd_clipboard
import app.skerry.ui.generated.resources.rd_ctrl_alt_del
import app.skerry.ui.generated.resources.rd_ctrl_alt_del_view_only
import app.skerry.ui.generated.resources.rd_disconnect
import app.skerry.ui.generated.resources.rd_display
import app.skerry.ui.generated.resources.rd_fullscreen
import app.skerry.ui.generated.resources.rd_fullscreen_exit
import app.skerry.ui.generated.resources.rd_screenshot
import app.skerry.ui.theme.Skerry
import org.jetbrains.compose.resources.stringResource

/** How close to the top of the picture the pointer has to come to summon a hidden bar. */
val REMOTE_BAR_EDGE: Dp = 12.dp

/** How long the bar waits, untouched, before it slides back up. */
const val REMOTE_BAR_AUTO_HIDE_MS = 3500L

/**
 * The action bar of a live remote desktop: a floating strip of icons over the top of the picture,
 * holding what belongs to the session rather than to the image — the secure attention sequence, the
 * clipboard, sound, the display settings, a screenshot, the full-window mode and the disconnect.
 *
 * It floats rather than taking a column beside the desktop: a remote screen is a screen, and every
 * dp of chrome around it is a dp the user is not working in. It slides away on its own and comes
 * back when the pointer reaches the top edge — [RemoteBarState] owns those rules.
 */
@Composable
fun RemoteDesktopBar(
    screen: RemoteDesktopScreenState,
    bar: RemoteBarState,
    // Remembered by the screen: the bar slides away on a timer, and a save launched in its scope
    // would be cancelled mid-write with the file deleted and nothing said.
    screenshot: ScreenshotAction,
    clipboardActions: ClipboardActions,
    immersive: Boolean,
    onToggleImmersive: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayOpen by remember { mutableStateOf(false) }
    var clipboardOpen by remember { mutableStateOf(false) }

    // The bar must outlive the pointer that opened a menu: the menu is a popup, so it is not part of
    // the bar's own hover area, and a bar that slid away under an open menu would take it along.
    // Cleared on the way out as well — a bar that left the screen while held would come back with
    // its auto-hide timer disarmed and then sit there for the rest of the session.
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    DisposableEffect(hovered, displayOpen, clipboardOpen) {
        bar.setHeld(hovered || displayOpen || clipboardOpen)
        onDispose { bar.setHeld(false) }
    }

    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val below = remember(gap) { belowAnchor(gap) }

    Row(
        modifier.padding(top = 12.dp).shadow(10.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Skerry.colors.surfaceDeep.copy(alpha = 0.95f))
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
                position = below,
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

        if (screen.capabilities.audio) {
            // A device that died mid-session says so on the button itself: the sound switch is out
            // here now, so there is no menu row left to carry the explanation.
            val lost = screen.audioFailed && !screen.audioMuted
            RemoteIconButton(
                if (screen.audioMuted) "volume_off" else "volume_up",
                if (lost) stringResource(Res.string.rd_audio_device_lost) else stringResource(Res.string.rd_audio),
                active = !screen.audioMuted && !lost,
                tint = if (lost) Skerry.colors.sunset else null,
                onClick = screen::toggleAudioMuted,
            )
        }

        BarDivider()

        RemoteMenuHost(
            expanded = displayOpen,
            onDismiss = { displayOpen = false },
            onToggle = { displayOpen = !displayOpen },
            position = below,
            trigger = { toggle ->
                RemoteIconButton(
                    "desktop_windows",
                    stringResource(Res.string.rd_display),
                    active = displayOpen,
                    onClick = toggle,
                )
            },
        ) {
            // No reset-zoom row and no sound row: the desktop has no pinch zoom, and sound is a
            // button of its own on this bar.
            DisplayMenu(screen, showResetZoom = false, showAudio = false)
        }

        RemoteIconButton(
            "screenshot_monitor",
            stringResource(Res.string.rd_screenshot),
            forcedTooltip = screenshot.note,
            onClick = screenshot.take,
        )

        RemoteIconButton(
            if (immersive) "fullscreen_exit" else "fullscreen",
            stringResource(if (immersive) Res.string.rd_fullscreen_exit else Res.string.rd_fullscreen),
            active = immersive,
            onClick = onToggleImmersive,
        )

        RemoteIconButton(
            "power_settings_new",
            stringResource(Res.string.rd_disconnect),
            tint = Skerry.colors.sunset,
            onClick = onDisconnect,
        )

        BarDivider()

        RemoteIconButton(
            "push_pin",
            stringResource(if (bar.pinned) Res.string.rd_bar_unpin else Res.string.rd_bar_pin),
            active = bar.pinned,
            size = BAR_SMALL_ICON,
            onClick = bar::togglePin,
        )
        RemoteIconButton(
            "keyboard_arrow_up",
            stringResource(Res.string.rd_bar_hide),
            size = BAR_SMALL_ICON,
            onClick = bar::hide,
        )
    }
}

/** Drops a menu straight below its button, centered on it and kept inside the window. */
internal fun belowAnchor(gap: Int): PopupPositionProvider = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (anchorBounds.left + anchorBounds.width / 2 - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        y = (anchorBounds.bottom + gap)
            .coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0)),
    )
}

@Composable
private fun BarDivider() {
    Box(Modifier.padding(horizontal = 5.dp).height(18.dp).width(1.dp).background(Skerry.colors.lineStrong))
}

private val BAR_SMALL_ICON = 28.dp
