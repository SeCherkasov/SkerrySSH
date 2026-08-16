package app.skerry.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.ui.design.HLine
import app.skerry.ui.design.HoverTooltip
import app.skerry.ui.design.MenuPanel
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_audio
import app.skerry.ui.generated.resources.rd_audio_device_lost
import app.skerry.ui.generated.resources.rd_screenshot_failed
import app.skerry.ui.generated.resources.rd_screenshot_saved
import app.skerry.ui.generated.resources.rd_stats
import app.skerry.ui.generated.resources.vnc_quality
import app.skerry.ui.generated.resources.vnc_reset_zoom
import app.skerry.ui.generated.resources.vnc_resize_to_window
import app.skerry.ui.generated.resources.vnc_view_only
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vnc.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The controls a live remote desktop offers, shared by the two shapes they are shown in: the
 * desktop's floating bar over the picture ([RemoteDesktopBar]) and the strip beside it on a phone
 * ([RemoteDesktopPanel]). Both drive the same [RemoteDesktopScreenState]; only the layout differs.
 */

/** One icon button. [forcedTooltip] shows a message the user did not ask to hover and wins while it lasts. */
@Composable
internal fun RemoteIconButton(
    icon: String,
    tooltip: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    tint: Color? = null,
    size: Dp = REMOTE_ICON_BOX,
    forcedTooltip: String? = null,
    /** Shown in place of [tooltip] while the button is disabled: a dimmed icon alone says nothing. */
    disabledTooltip: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Touch has no hover: a long press is how a phone asks what a button is.
    var pressedLong by remember { mutableStateOf(false) }
    LaunchedEffect(pressedLong) {
        if (pressedLong) {
            delay(SHOT_NOTE_MS)
            pressedLong = false
        }
    }

    Box(
        modifier.size(size).clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Skerry.colors.cyan10
                    hovered -> Skerry.colors.hover
                    else -> Color.Transparent
                },
            )
            // Hoverable even when disabled: that is exactly when the tooltip has something to say.
            .hoverable(interaction)
            .pointerInput(enabled) {
                detectTapGestures(
                    onLongPress = { pressedLong = true },
                    onTap = { if (enabled) onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Sym(
            icon,
            size = 18.sp,
            color = when {
                !enabled -> Skerry.colors.faint
                tint != null -> tint
                active -> Skerry.colors.cyanBright
                else -> Skerry.colors.dim
            },
        )
        val label = if (enabled) tooltip else disabledTooltip ?: tooltip
        val shown = forcedTooltip ?: label.takeIf { hovered || pressedLong }
        if (shown != null) HoverTooltip(shown)
    }
}

/**
 * A menu hung off one of those buttons. The caller supplies [position] because the two layouts open
 * in different directions — down from the floating bar, left from the strip at the right edge.
 */
@Composable
internal fun RemoteMenuHost(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    position: PopupPositionProvider,
    trigger: @Composable (toggle: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    // A press on the button of an open menu dismisses the popup and then reaches the button, which
    // would open it straight back up. See [PopupToggleGuard].
    val guard = remember { PopupToggleGuard() }
    Box {
        trigger { if (guard.opensOnClick()) onToggle() }
        if (expanded) {
            Popup(
                popupPositionProvider = position,
                onDismissRequest = {
                    guard.onDismissed()
                    onDismiss()
                },
                // Focusable would take the keyboard off the remote surface, and remote typing would
                // die until the user clicked the picture again.
                properties = PopupProperties(focusable = false),
            ) {
                // Fixed width rather than the panel's own measure: these menus hang off buttons
                // side by side, so a panel that sized itself to its rows would open at a different
                // width from one button to the next — and one of them holds a preview of whatever
                // text the remote machine put on its clipboard, which cannot be measured at all.
                MenuPanel(width = REMOTE_MENU_WIDTH) { content() }
            }
        }
    }
}

/**
 * What the picture looks like: quality, view-only, following the window. [showResetZoom] is for the
 * touch platform only — pinch-zoom is real on a phone, while the desktop wheel scrolls the remote
 * desktop instead, so there the control would be a no-op. [showAudio] likewise: the floating bar
 * has a sound button of its own, the strip does not.
 */
@Composable
internal fun DisplayMenu(
    screen: RemoteDesktopScreenState,
    showResetZoom: Boolean,
    showAudio: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        if (screen.capabilities.adjustableQuality) {
            Txt(
                stringResource(Res.string.vnc_quality),
                color = Skerry.colors.faint,
                size = 10.5.sp,
                modifier = Modifier.padding(start = ROW_PADDING, top = 6.dp, bottom = 2.dp),
            )
            RemoteDesktopQuality.entries.forEach { q ->
                MenuRow(q.label(), selected = screen.quality == q) { screen.applyQuality(q) }
            }
            HLine(modifier = Modifier.padding(vertical = 4.dp))
        }
        CheckRow(stringResource(Res.string.vnc_view_only), screen.viewOnly, screen::toggleViewOnly)
        // Only offered once the server has said it accepts a resize request.
        if (screen.canResizeRemote) {
            CheckRow(stringResource(Res.string.vnc_resize_to_window), screen.remoteResize, screen::toggleRemoteResize)
        }
        if (showAudio && screen.capabilities.audio) {
            CheckRow(stringResource(Res.string.rd_audio), !screen.audioMuted, screen::toggleAudioMuted)
            if (screen.audioFailed && !screen.audioMuted) AudioLostNote()
        }
        CheckRow(stringResource(Res.string.rd_stats), screen.showStats, screen::toggleStats)
        if (showResetZoom) {
            MenuRow(stringResource(Res.string.vnc_reset_zoom), selected = false) { screen.resetZoom() }
        }
    }
}

/**
 * The switch says sound is on while nothing comes out: without this line the only witness to a
 * device that died mid-session is a trace nobody has turned on. Never shown while muted — muting
 * stops the writes, so the player can never see a device take blocks again, and the line would sit
 * under an off switch for the rest of the session telling the user to reconnect.
 */
@Composable
internal fun AudioLostNote() {
    Txt(
        stringResource(Res.string.rd_audio_device_lost),
        color = Skerry.colors.sunset,
        size = 10.5.sp,
        // Indented past the checkbox of the switch it belongs to, not past the panel edge.
        modifier = Modifier.padding(start = ROW_PADDING + 22.dp, end = ROW_PADDING, bottom = 4.dp),
    )
}

/**
 * Taking a screenshot, and the answer to the last one. Where the file went is a tooltip on the
 * button rather than a line of text — neither layout has room for one, and the answer is only
 * interesting for a moment, so it clears itself on a timer.
 */
@Immutable
class ScreenshotAction(val note: String?, val take: () -> Unit)

/**
 * Remembered by the screen rather than by the bar or the panel: both of those come and go (the bar
 * slides away on a timer), and a save launched in a scope that leaves the composition mid-write is
 * cancelled with the file deleted and nothing said.
 */
@Composable
fun rememberScreenshotAction(screen: RemoteDesktopScreenState?): ScreenshotAction {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(path, failed) {
        if (path != null || failed) {
            delay(SHOT_NOTE_MS)
            path = null
            failed = false
        }
    }
    val note = when {
        failed -> stringResource(Res.string.rd_screenshot_failed)
        else -> path?.let { stringResource(Res.string.rd_screenshot_saved, it) }
    }
    // The lambda is memoized: it flows into a button's onClick, and a fresh instance on every
    // recomposition (the note clears itself on a timer) would defeat that button's skipping.
    val take = remember(screen, scope) {
        {
            // Nullable so the caller can remember the action unconditionally: a screen that comes
            // and goes must not make this a conditional composable call.
            if (screen != null) {
                scope.launch {
                    val saved = saveRemoteScreenshot(screen.imageBitmap, screen.serverName)
                    path = saved
                    failed = saved == null
                }
            }
            Unit
        }
    }
    return remember(note, take) { ScreenshotAction(note, take) }
}

@Composable
internal fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Skerry.colors.surfaceDeep)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Txt(label, color = Skerry.colors.cyanBright, size = 11.sp)
    }
}

@Composable
internal fun MenuRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        // Clipped like every other menu row: the panel insets its rows, so a selected one would
        // otherwise draw a square-cornered bar inside a rounded panel.
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp))
            .background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = ROW_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            label,
            color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text,
            size = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) Sym("check", size = 14.sp, color = Skerry.colors.cyanBright)
    }
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(5.dp)).clickable(onClick = onClick)
            .padding(horizontal = ROW_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(
            if (checked) "check_box" else "check_box_outline_blank",
            size = 15.sp,
            color = if (checked) Skerry.colors.cyanBright else Skerry.colors.dim,
        )
        Txt(
            label,
            color = if (checked) Skerry.colors.cyanBright else Skerry.colors.text,
            size = 12.5.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

internal val REMOTE_ICON_BOX = 34.dp
internal val REMOTE_MENU_WIDTH = 220.dp

/** Inside a row, so a row and the panel's own 4dp inset together read as the 12dp of a menu. */
internal val ROW_PADDING = 8.dp
internal const val SHOT_NOTE_MS = 2500L
