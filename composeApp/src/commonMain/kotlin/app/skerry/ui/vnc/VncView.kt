package app.skerry.ui.vnc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import app.skerry.shared.vnc.VncQuality
import app.skerry.ui.app.DesktopDesignState
import app.skerry.ui.app.LocalSessions
import app.skerry.ui.design.AnchoredDropdown
import app.skerry.ui.design.D
import app.skerry.ui.design.HLine
import app.skerry.ui.design.Sym
import app.skerry.ui.design.Txt
import app.skerry.ui.terminal.HostsSidebar
import app.skerry.ui.terminal.plainTextClipEntry
import app.skerry.ui.terminal.readPlainText
import kotlin.math.roundToInt

/**
 * The VNC tab's work area. Renders the active session's [VncSessionController] state: connecting /
 * live framebuffer / error / disconnected. The framebuffer sibling of `TerminalView`; it is its own
 * top-level view (Viewport routes [app.skerry.ui.session.SessionView.Vnc] here).
 */
@Composable
fun VncView(state: DesktopDesignState) {
    val sessions = LocalSessions.current
    val vnc = sessions?.active?.vncController ?: return
    Box(Modifier.fillMaxSize()) {
        when (val ui = vnc.uiState) {
            is VncUiState.Connecting -> CenterNotice("hourglass_empty", "Connecting…")
            is VncUiState.Connected -> Box(Modifier.fillMaxSize()) {
                VncSurface(ui.screen)
                Box(Modifier.align(Alignment.TopEnd)) { VncGraphicsBar(ui.screen) }
            }
            is VncUiState.Error -> CenterNotice("error", ui.message, color = D.sunset)
            is VncUiState.Disconnected -> Box(Modifier.fillMaxSize()) {
                VncSurface(ui.screen, interactive = false)
                CenterNotice(
                    "link_off",
                    if (ui.cleanExit) "Session closed" else "Connection lost",
                    color = D.sunset,
                )
            }
        }
        // Slide-over hosts drawer (rail chevron): overlays the framebuffer instead of shrinking it —
        // a layout resize would ripple to the remote desktop when "Resize to window" is on.
        // clipToBounds: slide transitions draw outside the node's bounds, so without it the panel
        // rides in from off-screen across the icon rail instead of emerging at the work-area edge.
        AnimatedVisibility(
            visible = state.vncSidebar,
            modifier = Modifier.align(Alignment.CenterStart).clipToBounds(),
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
        ) { HostsSidebar(state) }
    }
}

/**
 * Draws the remote framebuffer scaled to fit and, when [interactive], forwards pointer and keyboard
 * input. Reads [VncScreenState.frame] so it redraws on every applied update. Pointer coordinates are
 * mapped back through the same [fitGeometry] the draw uses.
 */
@Composable
fun VncSurface(screen: VncScreenState, interactive: Boolean = true) {
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
                val sym = keySymFor(ev.key, ev.utf16CodePoint)
                if (sym == 0L) return@onPreviewKeyEvent false
                screen.onKey(sym, down)
                true
            }
            .focusable()
    }

    // The sprite is ours to draw only while we're the ones moving the remote cursor; in view-only the
    // server paints it into the framebuffer instead. See [VncScreenState.toggleViewOnly].
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
        val clipboard = LocalClipboard.current
        LaunchedEffect(screen) {
            focus.requestFocus()
            // Local → server: push the current system clipboard once when the session opens, so a
            // paste on the remote host has our text (RFB has no clipboard-request, only cut-text).
            runCatching { clipboard.getClipEntry()?.readPlainText() }.getOrNull()
                ?.let { screen.onLocalClipboard(it) }
        }
        // Server → local: mirror ServerCutText into the system clipboard as it arrives.
        LaunchedEffect(screen.serverClipboard) {
            val text = screen.serverClipboard ?: return@LaunchedEffect
            runCatching { clipboard.setClipEntry(plainTextClipEntry(text)) }
        }
    }
}

/**
 * Fit-to-window draw: preserve aspect ratio, center, filter per [framebufferFilterQuality] — crisp
 * nearest-neighbor at 1:1/integer zoom, bilinear at fractional scales.
 */
private fun DrawScope.drawFramebuffer(screen: VncScreenState) {
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
 * [VncScreenState.desktopSize] (not the bitmap) so this layer never touches — and never invalidates
 * on — the framebuffer image itself.
 */
private fun DrawScope.drawCursor(screen: VncScreenState, sprite: VncCursorImage, pointerPos: Offset) {
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

/**
 * Compact graphics-settings control in the corner of a live VNC tab: a gear that opens a menu for
 * image quality (Auto/Low/Medium/High → [VncScreenState.setQuality]), view-only, and reset zoom.
 */
@Composable
private fun VncGraphicsBar(screen: VncScreenState) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(8.dp)) {
        AnchoredDropdown(
            expanded = open,
            onDismiss = { open = false },
            // The menu must not steal the keyboard from the VNC surface — remote typing would die
            // until the user clicks the picture again.
            focusable = false,
            trigger = {
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f))
                        .clickable { open = !open }.padding(7.dp),
                ) { Sym("tune", size = 18.sp, color = D.text) }
            },
            menu = { width ->
                Column(
                    Modifier.width(width.coerceAtLeast(180.dp)).clip(RoundedCornerShape(9.dp))
                        .background(D.surfaceDeep).border(1.dp, D.cyan14, RoundedCornerShape(9.dp)).padding(vertical = 4.dp),
                ) {
                    Txt("Quality", color = D.faint, size = 10.5.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                    VncQuality.entries.forEach { q ->
                        VncMenuRow(q.name, selected = screen.quality == q) { screen.applyQuality(q) }
                    }
                    HLine(modifier = Modifier.padding(vertical = 4.dp))
                    // No "Reset zoom" here: desktop has no local zoom (the wheel scrolls the remote
                    // desktop instead), so the fit is always 1:1 and the control would be a no-op.
                    // Mobile keeps it — pinch-zoom is real there.
                    VncMenuRow("View only", selected = screen.viewOnly, icon = if (screen.viewOnly) "check_box" else "check_box_outline_blank") { screen.toggleViewOnly() }
                    // Only offered once the server has said it accepts SetDesktopSize.
                    if (screen.canResizeRemote) {
                        VncMenuRow("Resize to window", selected = screen.remoteResize, icon = if (screen.remoteResize) "check_box" else "check_box_outline_blank") { screen.toggleRemoteResize() }
                    }
                }
            },
        )
    }
}

@Composable
private fun VncMenuRow(label: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) D.cyan10 else Color.Transparent).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Sym(icon, size = 15.sp, color = if (selected) D.cyanBright else D.dim)
        Txt(label, color = if (selected) D.cyanBright else D.text, size = 12.5.sp, modifier = Modifier.weight(1f))
        if (selected && icon == null) Sym("check", size = 14.sp, color = D.cyanBright)
    }
}

@Composable
private fun CenterNotice(icon: String, message: String, color: Color = D.dim) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Sym(icon, size = 28.sp, color = color)
            Txt(message, color = color, size = 13.sp)
        }
    }
}
