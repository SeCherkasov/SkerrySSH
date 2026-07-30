package app.skerry.ui.remote

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntSize
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.ui.vnc.FramebufferImage
import app.skerry.ui.vnc.VncCursorImage
import app.skerry.ui.vnc.clampPan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * UI-side state for one live remote desktop, whichever protocol serves it: bridges the raw
 * framebuffer into a Compose [ImageBitmap] and forwards input to the session. Collecting
 * [RemoteDesktopSession.updates] runs the session's read loop, so this owns that collection on
 * [scope] (the session's scope, cancelled by the controller on disconnect).
 *
 * [frame] is a snapshot counter bumped on every applied update; a composable that reads it redraws
 * with the latest [imageBitmap]. [desktopSize] tracks the remote resolution for coordinate mapping.
 */
@Stable
class RemoteDesktopScreenState(
    private val session: RemoteDesktopSession,
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

    /** Which optional controls the underlying protocol has, so the UI hides the rest. */
    val capabilities = session.capabilities

    /**
     * Apply a zoom+pan (from touch gestures); clamps the zoom to a sane range and the pan to what
     * still keeps the picture over the viewport (see [clampPan]).
     */
    fun setZoom(scale: Float, offset: Offset) {
        userScale = scale.coerceIn(1f, 8f)
        userOffset = clampPan(offset, viewport, desktopSize.width, desktopSize.height, userScale)
    }

    /** Reset zoom/pan back to plain fit-to-window. */
    fun resetZoom() {
        userScale = 1f
        userOffset = Offset.Zero
    }

    /** Current image quality/compression preference (Graphics settings). */
    var quality by mutableStateOf(RemoteDesktopQuality.Auto)
        private set

    /** True once the server has said it accepts resize requests. */
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
     * Debounced resize request: a window drag spews sizes many times a second, and each server-side
     * resize costs a full-screen retransmit — so only the size the user settles on is sent. Same
     * swallow-the-write discipline as [send].
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

    /** Change the quality preference; the server applies it on the next update. */
    fun applyQuality(newQuality: RemoteDesktopQuality) {
        quality = newQuality
        send { session.setQuality(newQuality) }
    }

    /**
     * The remote cursor sprite, drawn at our own pointer. Null while the server hasn't sent a shape
     * — either it paints the cursor into the framebuffer, or it is telling us the cursor is hidden.
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
            session.requestFullUpdate()
        }
    }

    // What the server was last told about whether anyone is looking. A session starts as visible
    // because that is the state a server begins in, so the first report worth sending is the one
    // that changes it — see [setVisible].
    private var outputVisible = true
    private var visibilityJob: Job? = null

    /**
     * Report whether this session is on screen at all. Off screen the server is asked to stop
     * rendering and streaming a desktop nobody is looking at (RDP's Suppress Output; a no-op where
     * the protocol has no such PDU), and the picture is asked for again on the way back.
     *
     * Unlike the other writes here these have to reach the server in the order they were made:
     * minimise-and-restore fires two of them, and a "back on screen" that overtakes the "hidden"
     * would leave the server suppressed while this side has already recorded the session as
     * visible — with that record then swallowing every later report. So each one waits for the
     * previous one instead of racing it.
     *
     * Called from the platform's window/app lifecycle — see [ReportOutputVisibility].
     */
    fun setVisible(visible: Boolean) {
        if (visible == outputVisible) return
        outputVisible = visible
        val previous = visibilityJob
        visibilityJob = send {
            previous?.join()
            session.setOutputVisible(visible)
        }
    }

    /** Sound from the remote machine is silenced locally; the channel itself stays open. */
    var audioMuted by mutableStateOf(false)
        private set

    fun toggleAudioMuted() {
        audioMuted = !audioMuted
        send { session.setAudioMuted(audioMuted) }
    }

    /**
     * The local device stopped taking sound: the session is mute for a reason that has nothing to do
     * with [audioMuted] and that nothing else on screen would show. Cleared by the same report when
     * a device takes blocks again.
     */
    var audioFailed by mutableStateOf(false)
        private set

    /**
     * Whether the clipboard travels at all. Enforced here rather than on the channel: both protocols
     * settle their clipboard at connect time, so this is the only place a running session can stop
     * text from crossing — in either direction, since the risk is symmetric.
     */
    var clipboardShared by mutableStateOf(true)
        private set

    fun toggleClipboardShared() {
        clipboardShared = !clipboardShared
        // Text that crossed while sharing was on is retracted with the switch: leaving it on screen
        // would keep offering the remote machine's clipboard after the user said it should stay there.
        if (!clipboardShared) serverClipboard = null
    }

    /**
     * The secure attention sequence. It cannot be typed: the local OS takes Ctrl+Alt+Del for itself
     * before any application sees it, which is the entire point of the sequence — so on the remote
     * machine it can only arrive as keys the client synthesizes.
     */
    fun sendCtrlAltDel() {
        if (viewOnly) return
        val keys = CTRL_ALT_DEL.mapNotNull { remoteKeyEvent(it, 0) }
        send {
            keys.forEach { session.sendKey(it, down = true) }
            keys.asReversed().forEach { session.sendKey(it, down = false) }
        }
    }

    /** True once the session has closed (server drop / EOF); the controller reacts to this. */
    var closed by mutableStateOf(false)
        private set

    /** Whether the last close was a clean peer exit (vs a transport drop). */
    var cleanExit: Boolean = false
        private set

    /**
     * The server's own explanation of the close, where it gave one ("the account may not log on
     * remotely"). Empty when the session simply dropped.
     */
    var closeReason: String = ""
        private set

    /** Latest clipboard text from the remote host; the view mirrors it into the system clipboard. */
    var serverClipboard: String? by mutableStateOf(null)
        private set

    val serverName: String get() = session.title

    /** The current frame image for drawing. */
    val imageBitmap: ImageBitmap get() = image.bitmap

    init {
        scope.launch {
            // The transports already turn a decode failure into a Closed update; this is the
            // belt-and-braces net, so a throwing session surfaces as a dropped session (the UI shows
            // "Connection lost") instead of an uncaught exception that would kill the collector
            // silently on desktop and the whole process on Android.
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

    private fun onUpdate(update: RemoteDesktopUpdate) {
        when (update) {
            is RemoteDesktopUpdate.Region -> {
                // An empty region is a protocol event with no pixels behind it (an RDP frame
                // marker); redrawing on it would burn a frame for nothing.
                if (update.rects.isNotEmpty()) {
                    image.writeRects(update.rects, session.framebuffer.pixels, session.framebuffer.width)
                    frame++
                }
            }

            is RemoteDesktopUpdate.Resize -> {
                image.resize(update.width, update.height)
                desktopSize = IntSize(update.width, update.height)
                frame++
            }

            is RemoteDesktopUpdate.RemoteResizeSupported -> {
                canResizeRemote = true
                // A restored-from-profile flag is already on before support is known — apply it now.
                if (remoteResize) scheduleRemoteResize()
            }

            is RemoteDesktopUpdate.Closed -> {
                cleanExit = update.cleanExit
                closeReason = update.reason
                closed = true
            }

            else -> onPeripheralUpdate(update)
        }
    }

    /**
     * Everything that touches neither the picture nor the session's life: cursor, clipboard, sound.
     *
     * Exhaustive on purpose — the members [onUpdate] handles are listed here as no-ops rather than
     * swept up by an `else`. The compiler refusing to build until a new [RemoteDesktopUpdate] is
     * handled somewhere is the only thing that kept every update reaching the screen; an `else` in
     * both halves would have let the next one be dropped in silence, which is exactly the bug this
     * split was made to accommodate.
     */
    private fun onPeripheralUpdate(update: RemoteDesktopUpdate) {
        when (update) {
            is RemoteDesktopUpdate.CursorShape -> cursor = VncCursorImage.of(update)
            // The pointer the server warps is not ours to move: the sprite tracks the local pointer,
            // and jumping it would desynchronise the two. The position is still applied by the
            // server to its own screen, which is what the user sees.
            is RemoteDesktopUpdate.CursorPosition -> Unit
            is RemoteDesktopUpdate.CursorVisible -> if (!update.visible) cursor = null
            is RemoteDesktopUpdate.ClipboardText -> if (clipboardShared) {
                serverClipboard = update.text
                onClipboard(update.text)
            }

            is RemoteDesktopUpdate.AudioPlaybackFailing -> audioFailed = update.failing
            is RemoteDesktopUpdate.Bell -> {}

            // Handled by the caller; named so this `when` stays exhaustive.
            is RemoteDesktopUpdate.Region,
            is RemoteDesktopUpdate.Resize,
            is RemoteDesktopUpdate.RemoteResizeSupported,
            is RemoteDesktopUpdate.Closed,
            -> Unit
        }
    }

    /** Forward a pointer event (framebuffer coordinates + button mask). No-op in view-only mode. */
    fun onPointer(x: Int, y: Int, buttonMask: Int) {
        if (viewOnly) return
        send { session.sendPointer(x, y, buttonMask) }
    }

    /** Forward a key event. No-op in view-only mode. */
    fun onKey(event: RemoteKeyEvent, down: Boolean) {
        if (viewOnly) return
        send { session.sendKey(event, down) }
    }

    /** Send local clipboard text to the server. */
    fun onLocalClipboard(text: String) {
        if (!clipboardShared) return
        send { session.sendClipboardText(text) }
    }

    /**
     * Fire-and-forget a write to the server. Every caller is a UI event (a mouse move, a menu click)
     * racing the read loop, so the socket can already be dead when the write lands — and an exception
     * escaping a bare `launch` isn't merely lost, it reaches the default handler and takes the whole
     * process down on Android. The dropped session surfaces through [closed] instead, which is the
     * read loop's job; there is nothing a failed input write can tell the user that the imminent
     * "Connection lost" doesn't.
     *
     * The job is returned for the one caller that has to order its writes ([setVisible]); the rest
     * drop it.
     */
    private fun send(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 400L

        val CTRL_ALT_DEL = listOf(Key.CtrlLeft, Key.AltLeft, Key.Delete)
    }
}
