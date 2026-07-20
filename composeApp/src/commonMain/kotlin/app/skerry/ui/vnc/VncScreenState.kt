package app.skerry.ui.vnc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import app.skerry.shared.vnc.VncPointerEvent
import app.skerry.shared.vnc.VncQuality
import app.skerry.shared.vnc.VncSession
import app.skerry.shared.vnc.VncUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * UI-side state for one live VNC session: bridges the codec's raw framebuffer into a Compose
 * [ImageBitmap] and forwards input to the session. Collecting [VncSession.updates] runs the
 * session's read loop, so this owns that collection on [scope] (the session's scope, cancelled by
 * the controller on disconnect).
 *
 * [frame] is a snapshot counter bumped on every applied update; a composable that reads it redraws
 * with the latest [imageBitmap]. [desktopSize] tracks the remote resolution for coordinate mapping.
 */
@Stable
class VncScreenState(
    private val session: VncSession,
    private val scope: CoroutineScope,
    private val onClipboard: (String) -> Unit = {},
    remoteResizeInitial: Boolean = false,
    private val onRemoteResizeChanged: (Boolean) -> Unit = {},
) {
    private val image = FramebufferImage(
        session.framebuffer.width.coerceAtLeast(1),
        session.framebuffer.height.coerceAtLeast(1),
    )

    /** Bumped on each applied framebuffer/resize update; read it in a composable to trigger redraw. */
    var frame by mutableStateOf(0)
        private set

    /** Remote desktop resolution (updates on server resize). */
    var desktopSize by mutableStateOf(IntSize(session.framebuffer.width, session.framebuffer.height))
        private set

    /** User zoom factor on top of the fit-to-window scale (1f = plain fit); set via [setZoom]. */
    var userScale by mutableStateOf(1f)
        private set

    /** User pan offset in canvas pixels (added after centering); set via [setZoom]. */
    var userOffset by mutableStateOf(Offset.Zero)
        private set

    /** Apply a zoom+pan (from touch/scroll gestures); clamps the zoom to a sane range. */
    fun setZoom(scale: Float, offset: Offset) {
        userScale = scale.coerceIn(1f, 8f)
        userOffset = offset
    }

    /** Reset zoom/pan back to plain fit-to-window. */
    fun resetZoom() {
        userScale = 1f
        userOffset = Offset.Zero
    }

    /** Current image quality/compression preference (Graphics settings). */
    var quality by mutableStateOf(VncQuality.Auto)
        private set

    /** True once the server has advertised SetDesktopSize support (ExtendedDesktopSize). */
    var canResizeRemote by mutableStateOf(false)
        private set

    /**
     * User flag: keep the remote desktop resized to the viewport instead of scaling to fit. Seeded
     * from the saved per-host value; changes are reported through [onRemoteResizeChanged] so the
     * host profile remembers them.
     */
    var remoteResize by mutableStateOf(remoteResizeInitial)
        private set

    // Last known viewport (canvas) size in pixels — the resize target when [remoteResize] is on.
    private var viewport = IntSize.Zero
    private var resizeJob: Job? = null

    /** Toggle following the viewport; turning it on resizes to the current viewport right away. */
    fun toggleRemoteResize() {
        remoteResize = !remoteResize
        onRemoteResizeChanged(remoteResize)
        if (remoteResize) {
            scheduleRemoteResize()
        } else {
            resizeJob?.cancel()
            resizeJob = null
        }
    }

    /** The drawing surface reports its size here (every layout change, cheap when idle). */
    fun onViewportSize(size: IntSize) {
        viewport = size
        if (remoteResize) scheduleRemoteResize()
    }

    /**
     * Debounced SetDesktopSize: a window drag-resize spews sizes many times a second, and each
     * server-side resize costs a full-screen retransmit — so only the size the user settles on is
     * sent. Same swallow-the-write discipline as [send].
     */
    private fun scheduleRemoteResize() {
        val target = viewport
        if (!canResizeRemote || target.width <= 0 || target.height <= 0) return
        resizeJob?.cancel()
        resizeJob = scope.launch {
            delay(RESIZE_DEBOUNCE_MS)
            if (target == desktopSize) return@launch
            try {
                session.setDesktopSize(target.width, target.height)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Change the quality preference; the server applies it on the next framebuffer update. */
    fun applyQuality(newQuality: VncQuality) {
        quality = newQuality
        send { session.setQuality(newQuality) }
    }

    /**
     * The remote cursor sprite (Cursor pseudo-encoding), drawn at our own pointer. Null while the
     * server hasn't sent a shape — either it ignores the encoding and paints the cursor into the
     * framebuffer, or it is telling us the cursor is hidden.
     */
    var cursor: VncCursorImage? by mutableStateOf(null)
        private set

    /** View-only: when true, pointer/key input is not forwarded (look, don't touch). */
    var viewOnly by mutableStateOf(false)
        private set

    /**
     * Toggle view-only, and hand the cursor back to the server while it's on: with nothing driving
     * our pointer, a sprite under it would claim the remote cursor is somewhere it isn't. The full
     * update is what makes the switch visible — re-advertising only governs what the server sends
     * next, so without it the cursor the server last painted would stay burnt into the framebuffer
     * next to the sprite.
     */
    fun toggleViewOnly() {
        viewOnly = !viewOnly
        val localCursor = !viewOnly
        send {
            session.setLocalCursor(localCursor)
            session.requestUpdate(incremental = false)
        }
    }

    /** True once the session has closed (server drop / EOF); the controller reacts to this. */
    var closed by mutableStateOf(false)
        private set

    /** Whether the last close was a clean peer exit (vs a transport drop). */
    var cleanExit: Boolean = false
        private set

    /** Latest ServerCutText from the remote host; the view mirrors it into the system clipboard. */
    var serverClipboard: String? by mutableStateOf(null)
        private set

    val serverName: String get() = session.serverName

    /** The current frame image for drawing. */
    val imageBitmap: ImageBitmap get() = image.bitmap

    init {
        scope.launch {
            // The transport already turns any decode failure into VncUpdate.Closed; this is the
            // belt-and-braces net, so a throwing session implementation surfaces as a dropped
            // session (UI shows "Connection lost") instead of an uncaught exception that would kill
            // the collector silently on desktop and the whole process on Android.
            try {
                session.updates.collect { onUpdate(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                cleanExit = false
                closed = true
            }
        }
    }

    private fun onUpdate(update: VncUpdate) {
        when (update) {
            is VncUpdate.Region -> {
                image.writeRects(update.rects, session.framebuffer.pixels, session.framebuffer.width)
                frame++
            }
            is VncUpdate.Resize -> {
                image.resize(update.width, update.height)
                desktopSize = IntSize(update.width, update.height)
                frame++
            }
            is VncUpdate.SetDesktopSizeSupported -> {
                canResizeRemote = true
                // A restored-from-profile flag is already on before support is known — apply it now.
                if (remoteResize) scheduleRemoteResize()
            }
            is VncUpdate.CursorShape -> cursor = VncCursorImage.of(update)
            is VncUpdate.ClipboardText -> {
                serverClipboard = update.text
                onClipboard(update.text)
            }
            is VncUpdate.Bell -> {}
            is VncUpdate.Closed -> {
                cleanExit = update.cleanExit
                closed = true
            }
        }
    }

    /** Forward a pointer event (framebuffer coordinates + RFB button mask). No-op in view-only mode. */
    fun onPointer(x: Int, y: Int, buttonMask: Int) {
        if (viewOnly) return
        send { session.sendPointer(VncPointerEvent(x, y, buttonMask)) }
    }

    /** Forward a key event (X11 keysym). No-op in view-only mode. */
    fun onKey(keySym: Long, down: Boolean) {
        if (viewOnly) return
        send { session.sendKey(keySym, down) }
    }

    /** Send local clipboard text to the server. */
    fun onLocalClipboard(text: String) {
        send { session.sendClientCutText(text) }
    }

    /**
     * Fire-and-forget a write to the server. Every caller is a UI event (a mouse move, a menu click)
     * racing the read loop, so the socket can already be dead when the write lands — and an exception
     * escaping a bare `launch` isn't merely lost, it reaches the default handler and takes the whole
     * process down on Android. The dropped session surfaces through [closed] instead, which is the
     * read loop's job; there is nothing a failed input write can tell the user that the imminent
     * "Connection lost" doesn't.
     */
    private fun send(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 400L
    }
}
