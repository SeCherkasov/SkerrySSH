package app.skerry.ui.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.ui.design.HLine
import app.skerry.ui.design.HoverTooltip
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_audio
import app.skerry.ui.generated.resources.rd_clipboard
import app.skerry.ui.generated.resources.rd_clipboard_copy_here
import app.skerry.ui.generated.resources.rd_clipboard_empty
import app.skerry.ui.generated.resources.rd_clipboard_from_remote
import app.skerry.ui.generated.resources.rd_clipboard_send
import app.skerry.ui.generated.resources.rd_clipboard_share
import app.skerry.ui.generated.resources.rd_ctrl_alt_del
import app.skerry.ui.generated.resources.rd_panel_hide
import app.skerry.ui.generated.resources.rd_screenshot
import app.skerry.ui.generated.resources.rd_screenshot_failed
import app.skerry.ui.generated.resources.rd_screenshot_saved
import app.skerry.ui.generated.resources.rd_settings
import app.skerry.ui.generated.resources.vnc_quality
import app.skerry.ui.generated.resources.vnc_reset_zoom
import app.skerry.ui.generated.resources.vnc_resize_to_window
import app.skerry.ui.generated.resources.vnc_view_only
import app.skerry.ui.terminal.fetchSystemClipboardText
import app.skerry.ui.terminal.plainTextClipEntry
import app.skerry.ui.theme.Skerry
import app.skerry.ui.vnc.label
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Width of the session panel: a strip of icons, the same figure on both platforms. */
val REMOTE_PANEL_WIDTH: Dp = 44.dp

/**
 * The session panel beside a live remote desktop: the actions that belong to the session rather than
 * to the picture — a screenshot, the secure attention sequence, the clipboard, and the settings that
 * used to sit in the floating gear.
 *
 * Icons only, each naming itself on hover (desktop) or on a long press (touch). A column of labels
 * would cost the desktop a fifth of its width for text the user reads once.
 *
 * [showResetZoom] is for the touch platform only: pinch-zoom is real on a phone, while the desktop
 * wheel scrolls the remote desktop instead, so there the control would be a no-op.
 */
@Composable
fun RemoteDesktopPanel(
    screen: RemoteDesktopScreenState,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    showResetZoom: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var settingsOpen by remember { mutableStateOf(false) }
    var clipboardOpen by remember { mutableStateOf(false) }
    // Where the last screenshot went. Shown as a tooltip on its own button and cleared on a timer:
    // the strip has no room for a line of text, and the answer is only interesting for a moment.
    var shotPath by remember { mutableStateOf<String?>(null) }
    var shotFailed by remember { mutableStateOf(false) }
    LaunchedEffect(shotPath, shotFailed) {
        if (shotPath != null || shotFailed) {
            delay(SHOT_NOTE_MS)
            shotPath = null
            shotFailed = false
        }
    }
    val shotNote = when {
        shotFailed -> stringResource(Res.string.rd_screenshot_failed)
        else -> shotPath?.let { stringResource(Res.string.rd_screenshot_saved, it) }
    }

    Column(
        modifier.width(REMOTE_PANEL_WIDTH).fillMaxHeight().background(Skerry.colors.surface2)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PanelIcon("chevron_right", stringResource(Res.string.rd_panel_hide), onClick = onHide)
        HLine(modifier = Modifier.padding(vertical = 2.dp))

        PanelIcon("photo_camera", stringResource(Res.string.rd_screenshot), forcedTooltip = shotNote) {
            scope.launch {
                val saved = saveRemoteScreenshot(screen.imageBitmap, screen.serverName)
                shotPath = saved
                shotFailed = saved == null
            }
        }

        // The keyboard cannot produce this one: the local OS takes Ctrl+Alt+Del before any app sees it.
        PanelIcon("keyboard", stringResource(Res.string.rd_ctrl_alt_del), enabled = !screen.viewOnly) {
            screen.sendCtrlAltDel()
        }

        if (screen.capabilities.clipboard) {
            if (!screen.clipboardShared) clipboardOpen = false
            PanelPopup(
                expanded = clipboardOpen,
                onDismiss = { clipboardOpen = false },
                onToggle = { clipboardOpen = !clipboardOpen },
                trigger = { toggle ->
                    PanelIcon(
                        "content_paste",
                        stringResource(Res.string.rd_clipboard),
                        // Nothing here works while sharing is off, and a button that answers a press
                        // with silence reads as a broken one.
                        enabled = screen.clipboardShared,
                        active = clipboardOpen,
                        onClick = toggle,
                    )
                },
            ) {
                ClipboardMenu(
                    screen,
                    onCopyHere = { text -> scope.launch { clipboard.setClipEntry(plainTextClipEntry(text)) } },
                    onSendMine = {
                        scope.launch { fetchSystemClipboardText(clipboard)?.let(screen::onLocalClipboard) }
                    },
                )
            }
        }

        PanelPopup(
            expanded = settingsOpen,
            onDismiss = { settingsOpen = false },
            onToggle = { settingsOpen = !settingsOpen },
            trigger = { toggle ->
                PanelIcon("tune", stringResource(Res.string.rd_settings), active = settingsOpen, onClick = toggle)
            },
        ) {
            SettingsMenu(screen, showResetZoom)
        }
    }
}

/**
 * Slim strip at the right edge while the session panel is collapsed, painted in the panel's own
 * surface so it reads as the panel peeking out. The mirror image of the hosts sidebar's handle.
 */
@Composable
fun PanelReopenHandle(onClick: () -> Unit) {
    Box(
        Modifier.width(16.dp).fillMaxHeight().background(Skerry.colors.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym("chevron_left", size = 16.sp, color = Skerry.colors.faint)
    }
}

/**
 * One button of the strip. [forcedTooltip] shows a message the user did not ask to hover — the
 * screenshot's answer — and takes precedence over [tooltip] while it lasts.
 */
@Composable
private fun PanelIcon(
    icon: String,
    tooltip: String,
    enabled: Boolean = true,
    active: Boolean = false,
    forcedTooltip: String? = null,
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
        Modifier.size(PANEL_ICON_BOX).clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> Skerry.colors.cyan10
                    hovered -> Skerry.colors.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction, enabled = enabled)
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
                active -> Skerry.colors.cyanBright
                else -> Skerry.colors.dim
            },
        )
        val shown = forcedTooltip ?: tooltip.takeIf { hovered || pressedLong }
        if (shown != null) HoverTooltip(shown)
    }
}

/**
 * A menu hung off a button of the strip, opening to its left. The shared [app.skerry.ui.design.AnchoredDropdown]
 * drops straight down from the anchor's left edge, which against the right screen edge would put the
 * menu off-screen.
 */
@Composable
private fun PanelPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    trigger: @Composable (toggle: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    // A press on the button of an open menu dismisses the popup and then reaches the button, which
    // would open it straight back up. See [PopupToggleGuard].
    val guard = remember { PopupToggleGuard() }
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val position = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val left = anchorBounds.left - popupContentSize.width - gap
                return IntOffset(
                    // Falls back to the right of the anchor on a window too narrow for either side,
                    // where a negative x would clip the menu against the window edge.
                    x = if (left >= 0) left else (anchorBounds.right + gap).coerceAtMost(
                        (windowSize.width - popupContentSize.width).coerceAtLeast(0),
                    ),
                    y = anchorBounds.top.coerceAtMost(
                        (windowSize.height - popupContentSize.height).coerceAtLeast(0),
                    ),
                )
            }
        }
    }
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
                Box(
                    Modifier.width(MENU_WIDTH).clip(RoundedCornerShape(9.dp)).background(Skerry.colors.surfaceDeep)
                        .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(9.dp)).padding(vertical = 4.dp),
                ) { content() }
            }
        }
    }
}

/** The settings that used to live in the floating gear, now behind the strip's gear. */
@Composable
private fun SettingsMenu(screen: RemoteDesktopScreenState, showResetZoom: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        if (screen.capabilities.adjustableQuality) {
            Txt(
                stringResource(Res.string.vnc_quality),
                color = Skerry.colors.faint,
                size = 10.5.sp,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
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
        if (screen.capabilities.audio) {
            CheckRow(stringResource(Res.string.rd_audio), !screen.audioMuted, screen::toggleAudioMuted)
        }
        if (screen.capabilities.clipboard) {
            CheckRow(stringResource(Res.string.rd_clipboard_share), screen.clipboardShared, screen::toggleClipboardShared)
        }
        if (showResetZoom) {
            MenuRow(stringResource(Res.string.vnc_reset_zoom), selected = false) { screen.resetZoom() }
        }
    }
}

/** What the remote machine last put on its clipboard, and the two ways to move text across. */
@Composable
private fun ClipboardMenu(
    screen: RemoteDesktopScreenState,
    onCopyHere: (String) -> Unit,
    onSendMine: () -> Unit,
) {
    val remote = screen.serverClipboard
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Txt(stringResource(Res.string.rd_clipboard_from_remote), color = Skerry.colors.faint, size = 10.sp)
        Txt(
            remote?.take(CLIPBOARD_PREVIEW) ?: stringResource(Res.string.rd_clipboard_empty),
            color = if (remote == null) Skerry.colors.dim else Skerry.colors.text,
            size = 11.5.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (remote != null) SmallButton(stringResource(Res.string.rd_clipboard_copy_here)) { onCopyHere(remote) }
            SmallButton(stringResource(Res.string.rd_clipboard_send), onClick = onSendMine)
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Skerry.colors.surfaceDeep)
            .border(1.dp, Skerry.colors.cyan14, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Txt(label, color = Skerry.colors.cyanBright, size = 11.sp)
    }
}

@Composable
private fun MenuRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Skerry.colors.cyan10 else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, color = if (selected) Skerry.colors.cyanBright else Skerry.colors.text, size = 12.5.sp, modifier = Modifier.weight(1f))
        if (selected) Sym("check", size = 14.sp, color = Skerry.colors.cyanBright)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sym(
            if (checked) "check_box" else "check_box_outline_blank",
            size = 15.sp,
            color = if (checked) Skerry.colors.cyanBright else Skerry.colors.dim,
        )
        Txt(label, color = if (checked) Skerry.colors.cyanBright else Skerry.colors.text, size = 12.5.sp, modifier = Modifier.weight(1f))
    }
}

private val PANEL_ICON_BOX = 34.dp
private val MENU_WIDTH = 220.dp
private const val CLIPBOARD_PREVIEW = 400
private const val SHOT_NOTE_MS = 2500L
