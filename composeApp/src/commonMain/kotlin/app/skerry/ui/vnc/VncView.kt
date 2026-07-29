package app.skerry.ui.vnc

import app.skerry.ui.remote.remoteKeyEvent
import app.skerry.ui.remote.PanelReopenHandle
import app.skerry.ui.remote.RemoteDesktopPanel
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.remote.RemoteDesktopUiState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_no_session
import app.skerry.ui.generated.resources.rd_pick_to_connect
import app.skerry.ui.generated.resources.vnc_connecting
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_quality
import app.skerry.ui.generated.resources.vnc_quality_auto
import app.skerry.ui.generated.resources.vnc_quality_high
import app.skerry.ui.generated.resources.vnc_quality_low
import app.skerry.ui.generated.resources.vnc_quality_medium
import app.skerry.ui.generated.resources.vnc_resize_to_window
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.generated.resources.vnc_view_only
import app.skerry.ui.design.EmptyState
import app.skerry.ui.host.HostSection
import app.skerry.ui.terminal.HostsSidebar
import app.skerry.ui.terminal.SidebarReopenHandle
import app.skerry.ui.terminal.plainTextClipEntry
import app.skerry.ui.terminal.readPlainText
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * The remote-desktop section: its own host sidebar (the desktops catalog) beside the work area,
 * mirroring how the terminal section is laid out. With no desktop session open the area explains
 * what to do instead of sitting blank — the sidebar is the only way into one.
 */
@Composable
fun RemoteDesktopsView(state: DesktopDesignState) {
    Row(Modifier.fillMaxSize()) {
        // Same collapse behaviour as the terminal sidebar (shared [DesktopDesignState.sidebarHidden]),
        // so hiding the panel in one section hides it in both — one shell, one preference.
        AnimatedVisibility(
            visible = !state.sidebarHidden,
            enter = expandHorizontally(expandFrom = Alignment.End),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End),
        ) { HostsSidebar(state, HostSection.RemoteDesktops) }
        AnimatedVisibility(
            visible = state.sidebarHidden,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) { SidebarReopenHandle(onClick = state::toggleSidebar) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (LocalSessions.current?.activeDesktop != null) {
                VncView(state)
            } else {
                EmptyState(
                    icon = "desktop_windows",
                    title = stringResource(Res.string.rd_no_session),
                    subtitle = stringResource(Res.string.rd_pick_to_connect),
                    tint = Skerry.colors.dim,
                )
            }
        }
    }
}

/**
 * The VNC tab's work area. Renders the active session's [RemoteDesktopController] state: connecting /
 * live framebuffer / error / disconnected. The framebuffer sibling of `TerminalView`, rendered by
 * [RemoteDesktopsView] beside the desktops sidebar.
 */
@Composable
fun VncView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    val vnc = sessions?.activeDesktop?.focusedPane?.vncController ?: return
    Box(Modifier.fillMaxSize()) {
        when (val ui = vnc.uiState) {
            is RemoteDesktopUiState.Connecting -> CenterNotice("hourglass_empty", stringResource(Res.string.vnc_connecting))
            is RemoteDesktopUiState.Connected -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { VncSurface(ui.screen) }
                AnimatedVisibility(
                    visible = !state.remotePanelHidden,
                    enter = expandHorizontally(expandFrom = Alignment.Start),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
                ) { RemoteDesktopPanel(ui.screen, onHide = state::toggleRemotePanel) }
                AnimatedVisibility(
                    visible = state.remotePanelHidden,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End),
                ) { PanelReopenHandle(onClick = state::toggleRemotePanel) }
            }
            is RemoteDesktopUiState.Error -> CenterNotice(
                "error",
                vncFailureText(ui.failure),
                color = Skerry.colors.sunset,
            )
            is RemoteDesktopUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                VncSurface(ui.screen, interactive = false)
                CenterNotice(
                    "link_off",
                    stringResource(if (ui.cleanExit) Res.string.vnc_session_closed else Res.string.vnc_connection_lost),
                    color = Skerry.colors.sunset,
                )
            }
        }
    }
}

/**
 * Draws the remote framebuffer scaled to fit and, when [interactive], forwards pointer and keyboard
 * input. Reads [RemoteDesktopScreenState.frame] so it redraws on every applied update. Pointer coordinates are
 * mapped back through the same [fitGeometry] the draw uses.
 */
@Composable
fun VncSurface(screen: RemoteDesktopScreenState, interactive: Boolean = true) {
    val frame = screen.frame
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }
    // Tracks whether the mouse is over the drawn image rather than the letterbox around it — the
    // pointer loop below sets it from the same geometry the draw uses. See [shouldHideLocalCursor].
    var pointerOverImage by remember { mutableStateOf(false) }
    // Last pointer position INSIDE the image, where the remote cursor therefore is. Deliberately not
    // cleared when the pointer leaves: the remote cursor stays put, so the sprite does too — exactly
    // what a server-painted cursor looks like when you move off the framebuffer.
    var pointerPos by remember { mutableStateOf<Offset?>(null) }

    // clipToBounds: a zoomed framebuffer must never draw outside its own area onto the app chrome.
    var mod = Modifier.fillMaxSize().clipToBounds().background(Color.Black).onSizeChanged {
        canvasSize = it
        screen.onViewportSize(it)
    }
    // Something remote already tracks the mouse — our sprite, or a cursor the server painted into the
    // framebuffer — so the OS pointer on top would be a second one. See [shouldHideLocalCursor].
    if (shouldHideLocalCursor(interactive, screen.viewOnly, pointerOverImage)) {
        hiddenPointerIcon()?.let { mod = mod.pointerHoverIcon(it) }
    }
    if (interactive) {
        mod = mod
            .pointerInput(screen) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        // Leaving the surface restores the OS pointer explicitly (as TerminalScreen
                        // does): the exit coordinate isn't guaranteed to land outside the image, and
                        // inferring it from geometry alone could strand the cursor hidden app-wide.
                        if (event.type == PointerEventType.Exit) {
                            pointerOverImage = false
                            continue
                        }
                        // Reclaim the keyboard on any click into the surface (as TerminalScreen
                        // does): the graphics menu / other chrome may have taken focus, and the
                        // one-shot requestFocus at session start never runs again.
                        if (event.type == PointerEventType.Press) focus.requestFocus()
                        val geom = fitGeometry(
                            canvasSize.width.toFloat(), canvasSize.height.toFloat(),
                            screen.desktopSize.width, screen.desktopSize.height,
                            screen.userScale, screen.userOffset.x, screen.userOffset.y,
                        )
                        val fb = geom.toFramebuffer(change.position.x, change.position.y)
                        // Set before the null-check below: leaving the image (onto the letterbox) is
                        // exactly when the local pointer has to come back.
                        pointerOverImage = fb != null
                        if (fb == null) { continue }
                        pointerPos = change.position
                        if (event.type == PointerEventType.Scroll) {
                            // Wheel goes to the server (scroll inside the remote desktop). No local
                            // zoom on desktop: without panning it only shows the center, and the fit
                            // already fills the tab.
                            val dy = change.scrollDelta.y
                            if (dy != 0f) {
                                val bit = if (dy < 0f) VncButton.WHEEL_UP else VncButton.WHEEL_DOWN
                                screen.onPointer(fb.x, fb.y, bit)   // wheel = press+release
                                screen.onPointer(fb.x, fb.y, 0)
                            }
                        } else {
                            var mask = 0
                            if (event.buttons.isPrimaryPressed) mask = mask or VncButton.LEFT
                            if (event.buttons.isTertiaryPressed) mask = mask or VncButton.MIDDLE
                            if (event.buttons.isSecondaryPressed) mask = mask or VncButton.RIGHT
                            screen.onPointer(fb.x, fb.y, mask)
                        }
                        change.consume()
                    }
                }
            }
            .focusRequester(focus)
            // onPreviewKeyEvent MUST sit before focusable(): preview key events are dispatched from
            // the focus root down TO the focused node and stop there. Placed after focusable() this
            // handler is a descendant of the focus target and never sees a key — the terminal's
            // TerminalScreen keeps the same order for the same reason.
            .onPreviewKeyEvent { ev ->
                val down = when (ev.type) {
                    KeyEventType.KeyDown -> true
                    KeyEventType.KeyUp -> false
                    else -> return@onPreviewKeyEvent false
                }
                val event = remoteKeyEvent(ev.key, ev.utf16CodePoint)
                if (event == null) return@onPreviewKeyEvent false
                screen.onKey(event, down)
                true
            }
            .focusable()
    }

    // The sprite is ours to draw only while we're the ones moving the remote cursor; in view-only the
    // server paints it into the framebuffer instead. See [RemoteDesktopScreenState.toggleViewOnly].
    val sprite = if (interactive && !screen.viewOnly) screen.cursor else null

    Box(mod) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION") frame // captured so the draw invalidates when it changes
            drawFramebuffer(screen)
        }
        // The cursor sprite lives on its OWN canvas: pointerPos changes on every raw mouse move, and
        // only this layer reads it (inside the draw block), so a move redraws just the small sprite.
        // In one canvas with the framebuffer, every mouse-pixel step re-filtered the whole frame at
        // canvas resolution — enough to pin a core at fullscreen on a software-Skia backend.
        if (sprite != null) {
            Canvas(Modifier.fillMaxSize()) {
                pointerPos?.let { drawCursor(screen, sprite, it) }
            }
        }
    }

    if (interactive) {
        LaunchedEffect(screen) { focus.requestFocus() }
        VncClipboardBridge(screen)
    }
}

/**
 * Keeps the system clipboard and the remote one in step for a live session: our text is pushed once
 * when the session opens (RFB has no clipboard-request, only cut-text, so a paste on the remote host
 * needs it up front), and every ServerCutText is mirrored back.
 */
@Composable
internal fun VncClipboardBridge(screen: RemoteDesktopScreenState) {
    val clipboard = LocalClipboard.current
    LaunchedEffect(screen) {
        runCatching { clipboard.getClipEntry()?.readPlainText() }.getOrNull()
            ?.let { screen.onLocalClipboard(it) }
    }
    LaunchedEffect(screen.serverClipboard) {
        val text = screen.serverClipboard ?: return@LaunchedEffect
        runCatching { clipboard.setClipEntry(plainTextClipEntry(text)) }
    }
}

/**
 * Fit-to-window draw: preserve aspect ratio, center, filter per [framebufferFilterQuality] — crisp
 * nearest-neighbor at 1:1/integer zoom, bilinear at fractional scales.
 */
internal fun DrawScope.drawFramebuffer(screen: RemoteDesktopScreenState) {
    val image = screen.imageBitmap
    val geom = fitGeometry(
        size.width, size.height, image.width, image.height,
        screen.userScale, screen.userOffset.x, screen.userOffset.y,
    )
    if (geom.scale <= 0f) return
    drawImage(
        image = image,
        dstOffset = IntOffset(geom.offsetX.toInt(), geom.offsetY.toInt()),
        dstSize = IntSize(geom.dstWidth, geom.dstHeight),
        filterQuality = framebufferFilterQuality(geom.scale),
    )
}

/**
 * The remote cursor [sprite] at [pointerPos], on the cursor-only layer. Geometry comes from
 * [RemoteDesktopScreenState.desktopSize] (not the bitmap) so this layer never touches — and never invalidates
 * on — the framebuffer image itself.
 */
private fun DrawScope.drawCursor(screen: RemoteDesktopScreenState, sprite: VncCursorImage, pointerPos: Offset) {
    val geom = fitGeometry(
        size.width, size.height, screen.desktopSize.width, screen.desktopSize.height,
        screen.userScale, screen.userOffset.x, screen.userOffset.y,
    )
    if (geom.scale <= 0f) return
    val at = cursorTopLeft(geom, pointerPos.x, pointerPos.y, sprite.hotspotX, sprite.hotspotY) ?: return
    // Scaled and filtered like the framebuffer it sits on: a cursor is remote pixels too, so under
    // zoom it grows with them rather than staying a lone sharp sprite on a blown-up screen.
    drawImage(
        image = sprite.bitmap,
        dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()),
        dstSize = IntSize((sprite.width * geom.scale).roundToInt(), (sprite.height * geom.scale).roundToInt()),
        filterQuality = framebufferFilterQuality(geom.scale),
    )
}

/** Localized label for a quality level in the graphics menu (shared with the mobile VNC screen). */
@Composable
internal fun RemoteDesktopQuality.label(): String = stringResource(
    when (this) {
        RemoteDesktopQuality.Auto -> Res.string.vnc_quality_auto
        RemoteDesktopQuality.Low -> Res.string.vnc_quality_low
        RemoteDesktopQuality.Medium -> Res.string.vnc_quality_medium
        RemoteDesktopQuality.High -> Res.string.vnc_quality_high
    },
)

@Composable
private fun CenterNotice(icon: String, message: String, color: Color = Skerry.colors.dim) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym(icon, size = 28.sp, color = color)
            Txt(message, color = color, size = 13.sp)
        }
    }
}
