package app.skerry.ui.vnc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.isForwardPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.skerry.ui.design.ClaimKeyboard
import app.skerry.ui.design.untrustedLabel
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.rd_surface
import app.skerry.ui.remote.RemoteDesktopScreenState
import app.skerry.ui.remote.RemoteStatsOverlay
import app.skerry.ui.remote.readLockKeys
import app.skerry.ui.remote.remoteKeyEvent
import app.skerry.ui.terminal.plainTextClipEntry
import app.skerry.ui.terminal.readPlainText
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.jetbrains.compose.resources.stringResource


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
    // Whether the keyboard is ours right now. [ClaimKeyboard] reads it to tell "the user moved the
    // caret to a field beside us" from "the window took focus away and gave it to nobody".
    val hasFocus = remember(screen) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
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

    // Every claim above lands the keyboard on this node, and a canvas of remote pixels has nothing
    // to read out: name it, so a screen reader says where focus went.
    // The server picked this name (RFB ServerInit carries it verbatim, control bytes and all), so
    // it is drawn — read out, here — like any other text a peer wrote.
    val surfaceName = stringResource(Res.string.rd_surface, untrustedLabel(screen.serverName))
    // clipToBounds: a zoomed framebuffer must never draw outside its own area onto the app chrome.
    var mod = Modifier.fillMaxSize().clipToBounds().background(Color.Black).semantics {
        contentDescription = surfaceName
    }.onSizeChanged {
        canvasSize = it
        screen.onViewportSize(it)
    }
    // Whether a sprite exists at all changes rarely (null ↔ non-null); WHICH shape it is changes
    // constantly (arrow ↔ I-beam). derivedStateOf keeps the composition subscribed to the former
    // only — the shape itself is read inside the cursor canvas's draw pass, so a shape switch
    // invalidates one draw, not this whole function (the same argument as F-34's frame counter).
    val hasSprite by remember(screen) { derivedStateOf { screen.cursor != null } }
    // Something remote already tracks the mouse — our sprite, or a cursor the server painted into the
    // framebuffer — so the OS pointer on top would be a second one. See [shouldHideLocalCursor].
    if (
        shouldHideLocalCursor(
            interactive = interactive,
            viewOnly = screen.viewOnly,
            pointerOverImage = pointerOverImage,
            systemCursor = screen.systemCursor,
            remoteTracksPointer = hasSprite || screen.capabilities.cursorHandover,
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
                    // Fractional wheel deltas accumulate per axis instead of rounding to one notch
                    // or nothing (F-14).
                    val wheelX = WheelCarry()
                    val wheelY = WheelCarry()
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
                            if (fb == null) {
                                // Hypothesis 3 of issue #265: near the edge these drops can read
                                // as "scrolling sometimes works" — the trace makes them visible.
                                wheelTrace {
                                    formatWheelDrop(WheelSample(change.type, change.scrollDelta.x, change.scrollDelta.y))
                                }
                                continue
                            }
                            latestPointer.value = change.position
                            pointerTick.trySend(Unit)
                            // Wheel goes to the server (scroll inside the remote desktop). No local
                            // zoom on desktop: without panning it only shows the center, and the fit
                            // already fills the tab. Magnitude counts — a three-line step is three
                            // notches, a trackpad's fractions accumulate (F-14) — and the buttons a
                            // drag is holding ride on every mask (F-38).
                            val held = buttonsOf(event.buttons)
                            val notchesY = wheelY.add(change.scrollDelta.y)
                            val notchesX = wheelX.add(change.scrollDelta.x)
                            val vertical = wheelMasks(
                                held, notchesY,
                                negative = VncButton.WHEEL_UP, positive = VncButton.WHEEL_DOWN,
                            )
                            val horizontal = wheelMasks(
                                held, notchesX,
                                negative = VncButton.WHEEL_LEFT, positive = VncButton.WHEEL_RIGHT,
                            )
                            wheelTrace {
                                formatWheelTrace(
                                    WheelSample(change.type, change.scrollDelta.x, change.scrollDelta.y),
                                    notchesX, notchesY, vertical.size + horizontal.size, held,
                                )
                            }
                            for (mask in vertical + horizontal) screen.onPointer(fb.x, fb.y, mask)
                            change.consume()
                            continue
                        }
                        val mask = buttonsOf(event.buttons)
                        // A move on the letterbox is nothing to the server, and a fresh press there
                        // is the user clicking dead space — clamping it would deliver a real click
                        // onto the desktop's edge (taskbar, a maximised app's close button). But a
                        // button change while something was already held is the tail of a drag that
                        // started on the image, and dropping it leaves the server holding the
                        // button for the rest of the session (F-37) — that one is clamped onto the
                        // nearest edge.
                        val target = fb
                            ?: if (lastMask != 0 && mask != lastMask) {
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
                hasFocus.value = state.isFocused
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
                // The way out. Every other key is forwarded to the guest — Tab and Escape included,
                // which a remote desktop needs and which is exactly what makes this surface a
                // keyboard trap otherwise: once it has focus, nothing keyboard-only takes it back.
                // Listed in Settings → Keyboard.
                if (isKeyboardRelease(ev)) {
                    if (down) {
                        // Everything held goes up on the remote side first: the chord's own
                        // modifiers were forwarded on the way down and would stay pressed there.
                        screen.notifyFocus(false)
                        focusManager.clearFocus()
                    }
                    return@onPreviewKeyEvent true
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

    // The sprite is ours to draw while we're the ones moving the remote cursor — and also in
    // view-only on a protocol whose cursor is always client-side (RDP): there the sprite at the
    // server-reported position is the only remote pointer there is (F-27). On RFB view-only hands
    // the cursor back and the server paints it into the framebuffer instead.
    val spriteVisible =
        interactive && hasSprite && (!screen.viewOnly || !screen.capabilities.cursorHandover)

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
        if (spriteVisible) {
            Canvas(Modifier.fillMaxSize()) {
                val sprite = screen.cursor ?: return@Canvas
                // A server-side warp wins until the local mouse next speaks (F-21). In view-only
                // the server position is the ONLY truth — no events are sent, so a sprite at the
                // local mouse would lie about where the remote pointer is (same rule as the touch
                // surface's drawTouchCursor).
                val warped = screen.serverPointer
                when {
                    warped != null -> drawCursorAtCell(screen, sprite, warped)
                    screen.viewOnly -> Unit
                    else -> pointerPos?.let { drawCursor(screen, sprite, it) }
                }
            }
        }
        if (screen.showStats) RemoteStatsOverlay(screen, Modifier.align(Alignment.TopStart))
    }

    if (interactive) {
        ClaimKeyboard(focus, screen, focused = hasFocus)
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
        // Not runCatching: these are suspending calls, and swallowing the cancellation of a torn
        // down session would break structured concurrency (guidelines §3).
        val local = try {
            clipboard.getClipEntry()?.readPlainText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        local?.let { screen.onLocalClipboard(it) }
    }
    LaunchedEffect(screen.serverClipboard) {
        val text = screen.serverClipboard ?: return@LaunchedEffect
        try {
            clipboard.setClipEntry(plainTextClipEntry(text))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
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
    val geom = cursorGeometry(screen) ?: return
    val at = cursorTopLeft(geom, pointerPos.x, pointerPos.y, sprite.hotspotX, sprite.hotspotY) ?: return
    drawCursorSprite(sprite, at, geom.scale)
}

/** The sprite at a framebuffer cell the server named (a warp, or view-only's only source; F-21). */
internal fun DrawScope.drawCursorAtCell(screen: RemoteDesktopScreenState, sprite: VncCursorImage, cell: IntOffset) {
    val geom = cursorGeometry(screen) ?: return
    val at = Offset(
        geom.offsetX + (cell.x - sprite.hotspotX) * geom.scale,
        geom.offsetY + (cell.y - sprite.hotspotY) * geom.scale,
    )
    drawCursorSprite(sprite, at, geom.scale)
}

private fun DrawScope.cursorGeometry(screen: RemoteDesktopScreenState): FitGeometry? {
    val geom = fitGeometry(
        size.width, size.height, screen.desktopSize.width, screen.desktopSize.height,
        screen.userScale, screen.userOffset.x, screen.userOffset.y,
    )
    return geom.takeIf { it.scale > 0f }
}

internal fun DrawScope.drawCursorSprite(sprite: VncCursorImage, at: Offset, scale: Float) {
    val dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt())
    val dstSize = IntSize((sprite.width * scale).roundToInt(), (sprite.height * scale).roundToInt())
    // Scaled and filtered like the framebuffer it sits on: a cursor is remote pixels too, so under
    // zoom it grows with them rather than staying a lone sharp sprite on a blown-up screen.
    drawImage(
        image = sprite.bitmap,
        dstOffset = dstOffset,
        dstSize = dstSize,
        filterQuality = framebufferFilterQuality(scale),
    )
    // The inverting pixels (the I-beam) flip what is underneath: difference against white is an
    // inversion, which keeps the caret visible on any background (F-20).
    val invert = sprite.invertBitmap ?: return
    drawImage(
        image = invert,
        dstOffset = dstOffset,
        dstSize = dstSize,
        filterQuality = framebufferFilterQuality(scale),
        blendMode = BlendMode.Difference,
    )
}

/** Holder for the raw pointer position between frame ticks; deliberately not snapshot state. */
private class LatestPointer {
    var value: Offset? = null
}

/** Every mouse button the shared mask carries — the navigation pair included (F-15). */
private fun buttonsOf(buttons: PointerButtons): Int {
    var mask = 0
    if (buttons.isPrimaryPressed) mask = mask or VncButton.LEFT
    if (buttons.isTertiaryPressed) mask = mask or VncButton.MIDDLE
    if (buttons.isSecondaryPressed) mask = mask or VncButton.RIGHT
    if (buttons.isBackPressed) mask = mask or VncButton.BACK
    if (buttons.isForwardPressed) mask = mask or VncButton.FORWARD
    return mask
}

/**
 * The chord that hands the keyboard back to the app: `Ctrl+Alt+Shift+K`. No desktop OS claims it, so
 * nothing on the remote side loses a binding, and it is the one chord this surface swallows instead
 * of forwarding — see the handler above and Settings → Keyboard.
 */
internal fun isKeyboardRelease(event: KeyEvent): Boolean =
    event.isCtrlPressed && event.isAltPressed && event.isShiftPressed && event.key == Key.K
