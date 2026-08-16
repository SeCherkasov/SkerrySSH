package app.skerry.ui.vnc

import app.skerry.ui.remote.readLockKeys
import app.skerry.ui.remote.remoteKeyEvent
import app.skerry.ui.remote.REMOTE_BAR_AUTO_HIDE_MS
import app.skerry.ui.remote.REMOTE_BAR_EDGE
import app.skerry.ui.remote.RemoteBarState
import app.skerry.ui.remote.RemoteDesktopBar
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.rememberClipboardActions
import app.skerry.ui.remote.rememberScreenshotAction
import app.skerry.ui.remote.RemoteDesktopController
import app.skerry.ui.remote.RemoteDesktopUiState
import app.skerry.ui.remote.RemoteStatsOverlay
import app.skerry.ui.remote.ReportOutputVisibility
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_no_session
import app.skerry.ui.generated.resources.rd_pick_to_connect
import app.skerry.ui.generated.resources.shell_tip_hide_hosts
import app.skerry.ui.generated.resources.shell_tip_show_hosts
import app.skerry.ui.generated.resources.vnc_connecting
import app.skerry.ui.generated.resources.vnc_connection_lost
import app.skerry.ui.generated.resources.vnc_quality_auto
import app.skerry.ui.generated.resources.vnc_quality_high
import app.skerry.ui.generated.resources.vnc_quality_low
import app.skerry.ui.generated.resources.vnc_quality_medium
import app.skerry.ui.generated.resources.vnc_session_closed
import app.skerry.ui.design.EmptyState
import app.skerry.ui.terminal.HostsSidebar
import app.skerry.ui.terminal.plainTextClipEntry
import app.skerry.ui.terminal.readPlainText
import app.skerry.ui.app.remoteChromeHidden
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.compose.resources.stringResource
import app.skerry.ui.theme.Skerry

/**
 * The remote-desktop section: its own host sidebar (the desktops catalog) beside the work area,
 * mirroring how the terminal section is laid out. With no desktop session open the area explains
 * what to do instead of sitting blank — the sidebar is the only way into one.
 */
@Composable
fun RemoteDesktopsView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    // Full-window mode takes the catalog with the rest of the chrome; the desktop's floating bar is
    // the way back out.
    val immersive = remoteChromeHidden(
        immersive = state.remoteImmersive,
        desktopSession = sessions?.activeDesktop != null,
        overlayOpen = state.appOverlay != null,
    )
    Row(Modifier.fillMaxSize()) {
        // Same collapse behaviour as the terminal sidebar (shared [DesktopDesignState.sidebarHidden]),
        // so hiding the panel in one section hides it in both — one shell, one preference.
        AnimatedVisibility(
            visible = !state.sidebarHidden && !immersive,
            enter = expandHorizontally(expandFrom = Alignment.End),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End),
        ) {
            // Same as the terminal side: the catalog follows the rail, the framebuffer follows the
            // selected tab (see workAreaSection).
            HostsSidebar(state, state.section)
        }
        // The section renders no work bar, so this strip is its only sidebar control — present
        // whether the panel is open or shut, one click either way (issue #178). Only full-window
        // mode takes it off screen, along with the rest of the chrome.
        AnimatedVisibility(
            visible = !immersive,
            enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
            exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start),
        ) { SidebarToggleHandle(hidden = state.sidebarHidden, onClick = state::toggleSidebar) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (sessions?.activeDesktop != null) {
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
 * Slim strip on the hosts panel's edge, painted in the panel's own surface so it reads as the panel
 * peeking out. The chevron points the way the panel will travel, like the work bar's toggle does in
 * the terminal section — and the strip is all chevron, so it carries the action's name itself.
 */
@Composable
private fun SidebarToggleHandle(hidden: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(16.dp).fillMaxHeight().background(Skerry.colors.surface2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Sym(
            if (hidden) "chevron_right" else "chevron_left",
            contentDescription = stringResource(
                if (hidden) Res.string.shell_tip_show_hosts else Res.string.shell_tip_hide_hosts,
            ),
            size = 16.sp,
            color = Skerry.colors.faint,
        )
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
    val tab = sessions?.activeDesktop ?: return
    val vnc = tab.focusedPane.vncController ?: return
    Box(Modifier.fillMaxSize()) {
        when (val ui = vnc.uiState) {
            is RemoteDesktopUiState.Connecting -> CenterNotice("hourglass_empty", stringResource(Res.string.vnc_connecting))
            // key(tab.id): switching between two connected desktops keeps this same branch, so
            // without it Compose reuses the slot and the next session would inherit the previous
            // one's bar — hidden or pinned, its menus open — and the full-window mode with it.
            is RemoteDesktopUiState.Connected -> androidx.compose.runtime.key(tab.id) {
                // clipToBounds: the bar slides out through the top edge, and without a clip it would
                // be drawn over the chrome above the work area on its way out.
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    // A minimised window, or another tab taking the screen, stops the server drawing.
                    ReportOutputVisibility(ui.screen)
                    val bar = remember { RemoteBarState() }
                    val screenshot = rememberScreenshotAction(ui.screen)
                    val clipboardActions = rememberClipboardActions(ui.screen)
                    val edge = with(LocalDensity.current) { REMOTE_BAR_EDGE.toPx() }
                    // The full-window mode belongs to this session: leaving it (another tab, a
                    // closed desktop) must not leave the window stripped of its chrome.
                    DisposableEffect(Unit) { onDispose { state.exitRemoteImmersive() } }
                    // Auto-hide, restarted by every reveal (revealCount) and disarmed while the
                    // pointer is on the bar, one of its menus is open, or it is pinned.
                    LaunchedEffect(bar.autoHides, bar.revealCount) {
                        if (bar.autoHides) {
                            delay(REMOTE_BAR_AUTO_HIDE_MS)
                            bar.hide()
                        }
                    }
                    // The screenshot answers on the bar itself, so a failure has to bring the bar
                    // back — otherwise the only word about an unwritten file lands off screen.
                    LaunchedEffect(screenshot.note) { if (screenshot.note != null) bar.reveal() }
                    // The reveal zone is read off the framebuffer's own pointer stream rather than
                    // from a strip laid over the top of it: a strip would swallow every mouse move
                    // in its band, and the remote cursor would stop dead a few pixels below the edge.
                    VncSurface(ui.screen, onPointerY = { y -> bar.onPointerY(y, edge) })
                    AnimatedVisibility(
                        visible = bar.visible,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut(),
                    ) {
                        RemoteDesktopBar(
                            screen = ui.screen,
                            bar = bar,
                            screenshot = screenshot,
                            clipboardActions = clipboardActions,
                            immersive = state.remoteImmersive,
                            onToggleImmersive = state::toggleRemoteImmersive,
                            onDisconnect = { state.requestCloseSession(tab.id) },
                        )
                    }
                }
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
 * input. The framebuffer draw reads [RemoteDesktopScreenState.frame] inside the draw pass — never
 * in the composable body, where every applied update would recompose this whole function (F-34) —
 * and frames are published here, on this composition's frame clock, so a burst of server updates
 * costs one redraw (F-02). Pointer coordinates are mapped back through the same [fitGeometry] the
 * draw uses.
 */
@Composable
fun VncSurface(
    screen: RemoteDesktopScreenState,
    interactive: Boolean = true,
    // Every pointer position over the surface, in pixels from its top edge. The floating bar reads
    // it to know when the pointer has come up to summon it.
    onPointerY: (Float) -> Unit = {},
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val focus = remember { FocusRequester() }
    // Tracks whether the mouse is over the drawn image rather than the letterbox around it — the
    // pointer loop below sets it from the same geometry the draw uses. See [shouldHideLocalCursor].
    var pointerOverImage by remember { mutableStateOf(false) }
    // Last pointer position INSIDE the image, where the remote cursor therefore is. Deliberately not
    // cleared when the pointer leaves: the remote cursor stays put, so the sprite does too — exactly
    // what a server-painted cursor looks like when you move off the framebuffer. Snapshot state is
    // written only on the frame clock below (F-08): a 1000 Hz mouse otherwise invalidates the
    // sprite canvas per sample, and the sprite cannot be drawn more often than once a frame anyway.
    var pointerPos by remember { mutableStateOf<Offset?>(null) }
    val latestPointer = remember(screen) { LatestPointer() }
    val pointerTick = remember(screen) { Channel<Unit>(Channel.CONFLATED) }
    // The frame pump: server updates land in the pixel mirror as they arrive, and this publishes
    // them to the canvas at most once per display frame.
    LaunchedEffect(screen) {
        screen.frameRequests.collect {
            withFrameNanos { }
            screen.publishFrame()
        }
    }
    LaunchedEffect(screen) {
        pointerTick.receiveAsFlow().collect {
            withFrameNanos { }
            pointerPos = latestPointer.value
        }
    }

    // clipToBounds: a zoomed framebuffer must never draw outside its own area onto the app chrome.
    var mod = Modifier.fillMaxSize().clipToBounds().background(Color.Black).onSizeChanged {
        canvasSize = it
        screen.onViewportSize(it)
    }
    // Something remote already tracks the mouse — our sprite, or a cursor the server painted into the
    // framebuffer — so the OS pointer on top would be a second one. See [shouldHideLocalCursor].
    if (
        shouldHideLocalCursor(
            interactive = interactive,
            viewOnly = screen.viewOnly,
            pointerOverImage = pointerOverImage,
            systemCursor = screen.systemCursor,
        )
    ) {
        hiddenPointerIcon()?.let { mod = mod.pointerHoverIcon(it) }
    }
    // pointerInput is only recreated by the screen key, so the callback is read through
    // rememberUpdatedState — a captured lambda would go stale on the first recomposition.
    val reportPointerY = rememberUpdatedState(onPointerY)
    if (interactive) {
        mod = mod
            .pointerInput(screen) {
                awaitPointerEventScope {
                    // The button state last forwarded, so a release over the letterbox is
                    // recognisable as a state change worth clamping onto the image (F-37).
                    var lastMask = 0
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        reportPointerY.value(change.position.y)
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
                        if (event.type == PointerEventType.Scroll) {
                            if (fb == null) { continue }
                            latestPointer.value = change.position
                            pointerTick.trySend(Unit)
                            // Wheel goes to the server (scroll inside the remote desktop). No local
                            // zoom on desktop: without panning it only shows the center, and the fit
                            // already fills the tab.
                            val dy = change.scrollDelta.y
                            if (dy != 0f) {
                                val bit = if (dy < 0f) VncButton.WHEEL_UP else VncButton.WHEEL_DOWN
                                screen.onPointer(fb.x, fb.y, bit)   // wheel = press+release
                                screen.onPointer(fb.x, fb.y, 0)
                            }
                            change.consume()
                            continue
                        }
                        var mask = 0
                        if (event.buttons.isPrimaryPressed) mask = mask or VncButton.LEFT
                        if (event.buttons.isTertiaryPressed) mask = mask or VncButton.MIDDLE
                        if (event.buttons.isSecondaryPressed) mask = mask or VncButton.RIGHT
                        // A move on the letterbox is nothing to the server, but a button CHANGE
                        // there is a press or release that must not be dropped — the server would
                        // keep the button held for the rest of the session (F-37). Clamp it onto
                        // the nearest edge of the image instead.
                        val target = fb
                            ?: if (mask != lastMask) {
                                geom.toFramebufferClamped(change.position.x, change.position.y)
                            } else {
                                null
                            }
                        if (target == null) { continue }
                        if (fb != null) {
                            latestPointer.value = change.position
                            pointerTick.trySend(Unit)
                        }
                        screen.onPointer(target.x, target.y, mask)
                        lastMask = mask
                        change.consume()
                    }
                }
            }
            .focusRequester(focus)
            // Focus loss releases whatever keys are held (F-12), the same way the terminal reports
            // its focus; focus gain re-syncs the lock keys (F-13).
            .onFocusChanged { state ->
                if (state.isFocused) screen.onLockKeys(readLockKeys(null))
                screen.notifyFocus(state.isFocused)
            }
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
                // The lock keys ride on every event, so a Caps toggled mid-session syncs too.
                screen.onLockKeys(readLockKeys(ev))
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
            // Read in the DRAW pass, deliberately: a composition-scope read would recompose the
            // whole surface — modifier chain and all — on every published frame (F-34).
            @Suppress("UNUSED_EXPRESSION") screen.frame
            val started = TimeSource.Monotonic.markNow()
            drawFramebuffer(screen)
            // On desktop this includes the pixel-bridge bitmap rebuild — the draw is where it runs.
            screen.renderStats.drawTime(started.elapsedNow().inWholeNanoseconds)
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
        if (screen.showStats) RemoteStatsOverlay(screen, Modifier.align(Alignment.TopStart))
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
internal fun DrawScope.drawCursor(screen: RemoteDesktopScreenState, sprite: VncCursorImage, pointerPos: Offset) {
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

/** Holder for the raw pointer position between frame ticks; deliberately not snapshot state. */
private class LatestPointer {
    var value: Offset? = null
}

@Composable
private fun CenterNotice(icon: String, message: String, color: Color = Skerry.colors.dim) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym(icon, size = 28.sp, color = color)
            Txt(message, color = color, size = 13.sp)
        }
    }
}
