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

    /** Change the quality preference; the server applies it on the next framebuffer update. */
    fun applyQuality(newQuality: VncQuality) {
        quality = newQuality
        scope.launch { session.setQuality(newQuality) }
    }

    /** View-only: when true, pointer/key input is not forwarded (look, don't touch). */
    var viewOnly by mutableStateOf(false)
        private set

    fun toggleViewOnly() { viewOnly = !viewOnly }

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
        scope.launch { session.sendPointer(VncPointerEvent(x, y, buttonMask)) }
    }

    /** Forward a key event (X11 keysym). No-op in view-only mode. */
    fun onKey(keySym: Long, down: Boolean) {
        if (viewOnly) return
        scope.launch { session.sendKey(keySym, down) }
    }

    /** Send local clipboard text to the server. */
    fun onLocalClipboard(text: String) {
        scope.launch { session.sendClientCutText(text) }
    }
}
