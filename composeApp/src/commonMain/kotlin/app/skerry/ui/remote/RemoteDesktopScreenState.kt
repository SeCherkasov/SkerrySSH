package app.skerry.ui.remote

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * UI-side state for one live remote desktop, whichever protocol serves it: bridges the raw
 * framebuffer into a Compose [ImageBitmap] and forwards input to the session. Collecting
 * [RemoteDesktopSession.updates] runs the session's read loop, so this owns that collection on
 * [scope] (the session's scope, cancelled by the controller on disconnect).
 *
 * [frame] is a snapshot counter the draw pass reads to pick up the latest [imageBitmap]; it is
 * bumped by [publishFrame] on the view's frame clock, so however many updates arrive within one
 * display frame the canvas invalidates once. [desktopSize] tracks the remote resolution for
 * coordinate mapping.
 */
@Stable
class RemoteDesktopScreenState(
    private val session: RemoteDesktopSession,
    private val scope: CoroutineScope,
    private val onClipboard: (String) -> Unit = {},
    remoteResizeInitial: Boolean = false,
    private val onRemoteResizeChanged: (Boolean) -> Unit = {},
    /** The profile's remembered quality (V-03); applied at connect, changes reported outward. */
    private val qualityInitial: RemoteDesktopQuality = RemoteDesktopQuality.Auto,
    private val onQualityChanged: (RemoteDesktopQuality) -> Unit = {},
    clipboardSharedInitial: Boolean = true,
    private val viewOnlyInitial: Boolean = false,
) {
    private val image = FramebufferImage(
        session.framebuffer.width.coerceAtLeast(1),
        session.framebuffer.height.coerceAtLeast(1),
    )

    /** Bumped on each published frame; read it in a draw pass to redraw with the latest pixels. */
    var frame by mutableStateOf(0)
        private set

    // A region update writes pixels at once but does not invalidate the canvas: a server sends many
    // small updates inside one logical frame, and a redraw per update multiplied the whole draw cost
    // by their count (F-02). The view drains [frameRequests] on its own frame clock instead.
    private val frameSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * One element per batch of applied updates since the last [publishFrame], conflated: however
     * many arrive within a display frame, the view redraws once. Collected by the one live surface.
     */
    val frameRequests: Flow<Unit> = frameSignal.receiveAsFlow()

    /** Publish the pixels written so far to the canvas — called by the view, on its frame clock. */
    fun publishFrame() {
        frame++
    }

    // Declared above the init block on purpose: the actor launched there reads it, and on an
    // eager dispatcher it does so before any property declared below the block exists.
    private val lifetime = RemoteDesktopLifetime(session, scope) { frameSignal.close() }
    private val inputActor = lifetime.input

    @Volatile
    private var lastLockKeys: LockKeys? = null

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
        userOffset = clampPan(offset, viewport.size, desktopSize.width, desktopSize.height, userScale)
    }

    /** Reset zoom/pan back to plain fit-to-window. */
    fun resetZoom() {
        userScale = 1f
        userOffset = Offset.Zero
    }

    /** Current image quality/compression preference (Graphics settings), seeded from the profile. */
    var quality by mutableStateOf(qualityInitial)
        private set

    /** The session's protocol-side counters, shown by the diagnostics overlay. */
    val diagnostics = session.diagnostics

    /** The render-side counters (pixel bridge, draw), filled in here and by the draw pass. */
    val renderStats = RemoteRenderStats()

    /** Whether the diagnostics overlay is shown over the picture. */
    var showStats by mutableStateOf(false)
        private set

    fun toggleStats() {
        showStats = !showStats
    }

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

    // Last known viewport (canvas) size in physical pixels and the display scaling it was measured
    // at — the resize target when [remoteResize] is on. One value, not two fields: the size and the
    // scale describe one DPI together, and a debounce firing between two separate writes would send
    // a layout whose millimetres and scale factor disagree.
    // @Volatile: written by the UI thread, read by [scheduleRemoteResize] when the session's read
    // loop reacts to RemoteResizeSupported — without it that reader can see a stale Zero and skip
    // the seeded resize.
    @Volatile
    private var viewport = RemoteViewport.None

    // The layout the server was last asked for, seeded with the first measurement so a session that
    // opens already the right size owes nothing. Deduping against [desktopSize] alone would compare
    // pixels only, and the display scaling can change while they do not — Android's "Display size",
    // a maximised window on Windows — leaving the session at the DPI it connected with.
    @Volatile
    private var sentLayout: RemoteViewport? = null

    // Guarded by [resizeLock]: the debounce job is cancelled-and-replaced from both the UI thread
    // and the read loop, and an unguarded swap can leave two jobs alive with the stale size
    // landing last.
    private var resizeJob: Job? = null
    private val resizeLock = Mutex()

    /** Toggle following the viewport; turning it on resizes to the current viewport right away. */
    fun toggleRemoteResize() {
        remoteResize = !remoteResize
        onRemoteResizeChanged(remoteResize)
        if (remoteResize) {
            scheduleRemoteResize()
        } else {
            scope.launch {
                resizeLock.withLock {
                    resizeJob?.cancel()
                    resizeJob = null
                }
            }
        }
    }

    /**
     * The drawing surface reports itself here (every layout change, cheap when idle): its size in
     * physical pixels together with the scaling they are drawn at ([RemoteViewport.scale]). The
     * scale is what keeps the remote desktop at this machine's DPI instead of filling those pixels
     * with a 96 dpi desktop half the size of the local UI.
     */
    fun onViewportSize(viewport: RemoteViewport) {
        this.viewport = viewport
        if (sentLayout == null) sentLayout = viewport
        if (remoteResize) scheduleRemoteResize()
    }

    /**
     * Debounced resize request: a window drag spews sizes many times a second, and each server-side
     * resize costs a full-screen retransmit — so only the size the user settles on is sent. Same
     * failure handling as [RemoteDesktopLifetime.send].
     */
    private fun scheduleRemoteResize() {
        if (close.value != null || !canResizeRemote) return
        scope.launch {
            resizeLock.withLock {
                resizeJob?.cancel()
                resizeJob = scope.launch {
                    delay(RESIZE_DEBOUNCE_MS)
                    // Read at fire time, not capture time: the wrappers race across pool threads,
                    // and a wrapper carrying a stale captured size could win the lock last. The
                    // volatile [viewport] is always the freshest, and re-checking [remoteResize]
                    // honours a toggle-off that landed while this debounce was pending.
                    if (close.value != null || !remoteResize) return@launch
                    val requested = viewport
                    val (target, displayScale) = requested
                    if (target.width <= 0 || target.height <= 0) return@launch
                    if (target == desktopSize && requested == sentLayout) return@launch
                    try {
                        session.setDesktopSize(target.width, target.height, displayScale)
                        sentLayout = requested
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        lifetime.markClosed(RemoteDesktopUpdate.Closed(false))
                    }
                }
            }
        }
    }

    /** Change the quality preference; the server applies it on the next update. */
    fun applyQuality(newQuality: RemoteDesktopQuality) {
        quality = newQuality
        onQualityChanged(newQuality)
        lifetime.send { session.setQuality(newQuality) }
    }

    private val peripheral = RemoteDesktopPeripheralState(onClipboard, clipboardSharedInitial)

    /** Remote cursor sprite; null when hidden or the server paints it. */
    val cursor: VncCursorImage? get() = peripheral.cursor
    /** Server-reported pointer position, superseded by local pointer input. */
    val serverPointer: IntOffset? get() = peripheral.serverPointer
    /** True when the server asks for the ordinary system pointer. */
    val systemCursor: Boolean get() = peripheral.systemCursor

    /** View-only: when true, pointer/key input is not forwarded (look, don't touch). */
    var viewOnly by mutableStateOf(viewOnlyInitial)
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
        // No handover, nothing to hand: on RDP setLocalCursor is a documented no-op and the
        // cursor stays the client's to draw — the sprite keeps showing at the server-reported
        // position — so the full repaint bought nothing (F-27).
        if (!capabilities.cursorHandover) return
        val localCursor = !viewOnly
        lifetime.send {
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
        // A hidden session takes no more key events, so whatever is down now stays down on the
        // server until it comes back — release it on the way out, like a focus loss (F-12).
        if (!visible) releaseHeldKeys()
        val previous = visibilityJob
        visibilityJob = lifetime.send {
            previous?.join()
            session.setOutputVisible(visible)
        }
    }

    /** Sound from the remote machine is silenced locally; the channel itself stays open. */
    var audioMuted by mutableStateOf(false)
        private set

    fun toggleAudioMuted() {
        audioMuted = !audioMuted
        lifetime.send { session.setAudioMuted(audioMuted) }
    }

    /** Whether the local audio output stopped taking blocks. */
    val audioFailed: Boolean get() = peripheral.audioFailed

    /** Controls clipboard sharing in both directions. */
    val clipboardShared: Boolean get() = peripheral.clipboardShared

    fun toggleClipboardShared() = peripheral.toggleClipboardShared()

    /**
     * The secure attention sequence. It cannot be typed: the local OS takes Ctrl+Alt+Del for itself
     * before any application sees it, which is the entire point of the sequence — so on the remote
     * machine it can only arrive as keys the client synthesizes.
     */
    fun sendCtrlAltDel() {
        if (viewOnly || close.value != null) return
        // Through the actor like every other key, so the sequence cannot interleave with typing.
        val keys = CTRL_ALT_DEL.mapNotNull { remoteKeyEvent(it, 0) }
        keys.forEach { inputActor.submit(RemoteInputActor.KeyWrite(it, down = true)) }
        keys.asReversed().forEach { inputActor.submit(RemoteInputActor.KeyWrite(it, down = false)) }
    }

    /**
     * The close, once the session ended on its own (server drop / EOF); null while it is live. It
     * carries `cleanExit` (a clean peer exit vs a transport drop) and the server's own explanation
     * where it gave one ("the account may not log on remotely").
     *
     * A flow rather than snapshot state: the watcher is a coroutine on the session scope, not a
     * composition, and snapshot reads only reach one through the process-wide apply-observer
     * registry — delivered by whatever frame happens to run next. The terminal side watches
     * `TerminalState` the same way.
     */
    val close: StateFlow<RemoteDesktopUpdate.Closed?> = lifetime.close

    /** Latest clipboard text from the remote host; the view mirrors it into the system clipboard. */
    val serverClipboard: String? get() = peripheral.serverClipboard

    val serverName: String get() = session.title

    /** The current frame image for drawing. */
    val imageBitmap: ImageBitmap get() = image.bitmap

    init {
        if (viewOnlyInitial && capabilities.cursorHandover) {
            lifetime.send {
                session.setLocalCursor(false)
                session.requestFullUpdate()
            }
        }
        // The profile's remembered quality (V-03). Auto is the wire default — announcing it would
        // be noise — and seeding is not a change, so onQualityChanged stays silent here.
        if (qualityInitial != RemoteDesktopQuality.Auto) {
            lifetime.send { session.setQuality(qualityInitial) }
        }
        lifetime.start(::onUpdate)
    }

    private fun onUpdate(update: RemoteDesktopUpdate) {
        if (close.value != null) return
        when (update) {
            is RemoteDesktopUpdate.Region -> {
                // An empty region is a protocol event with no pixels behind it (an RDP frame
                // marker); redrawing on it would burn a frame for nothing.
                if (update.rects.isNotEmpty()) {
                    val started = TimeSource.Monotonic.markNow()
                    val current = session.framebuffer.snapshot
                    image.writeRects(update.rects, current.pixels, current.width)
                    renderStats.bridgeTime(started.elapsedNow().inWholeNanoseconds)
                    frameSignal.trySend(Unit)
                }
            }

            is RemoteDesktopUpdate.Resize -> {
                image.resize(update.width, update.height)
                desktopSize = IntSize(update.width, update.height)
                frame++
                // An RDP resize can be a reactivation, which resets the server's input state —
                // resend the lock keys so Caps/Num survive it (F-13).
                lastLockKeys?.let { inputActor.submit(RemoteInputActor.LockWrite(it)) }
            }

            is RemoteDesktopUpdate.RemoteResizeSupported -> {
                canResizeRemote = true
                // A restored-from-profile flag is already on before support is known — apply it now.
                if (remoteResize) scheduleRemoteResize()
            }

            is RemoteDesktopUpdate.Closed -> lifetime.markClosed(update)

            else -> peripheral.onUpdate(update)
        }
    }

    /**
     * Forward a pointer event (framebuffer coordinates + button mask). No-op in view-only mode.
     * [wheel] marks the two masks of a wheel notch, which the actor must not pace or collapse the
     * way it does a move — see [RemoteInputActor].
     */
    fun onPointer(x: Int, y: Int, buttonMask: Int, wheel: Boolean = false) {
        if (viewOnly || close.value != null) return
        // The local mouse speaking takes the cursor back from a server-side warp (F-21).
        peripheral.serverPointer = null
        inputActor.submit(RemoteInputActor.PointerWrite(x, y, buttonMask, wheel))
    }

    /**
     * Forward a key event. No-op in view-only mode. [modifier] names the modifier this key is, so
     * [syncModifiers] can lift it again if the local machine lets go of it without telling us.
     */
    fun onKey(event: RemoteKeyEvent, down: Boolean, modifier: RemoteModifier? = null) {
        if (viewOnly || close.value != null) return
        held.record(event, down, modifier)
        inputActor.submit(RemoteInputActor.KeyWrite(event, down))
    }

    /**
     * Reconcile the modifiers the server is holding with the ones the local machine actually has
     * down. Every input event carries that state, and it is the only way to notice a key-up that
     * never arrived — the window manager takes Alt+Tab and the Super key for itself, keeps the
     * release, and the server is left with the modifier stuck down. From there every click reads as
     * Alt+click or Win+click and the desktop stops answering the mouse.
     */
    fun syncModifiers(local: RemoteModifiers, except: RemoteModifier? = null) {
        if (viewOnly || close.value != null) return
        for (event in held.outOfStep(local, except)) {
            inputActor.submit(RemoteInputActor.KeyWrite(event, down = false))
        }
    }

    // What the server is holding; written from the UI thread only.
    private val held = HeldKeys()

    private fun releaseHeldKeys() {
        for (event in held.releaseAll()) inputActor.submit(RemoteInputActor.KeyWrite(event, false))
    }


    /**
     * The surface gained or lost keyboard focus. Losing it releases everything held (F-12): the
     * key-up for an Alt+Tab goes to the local desktop, so without this the server keeps Alt down
     * for the rest of the session. Gaining it re-syncs the lock keys (F-13) — while the session was
     * in the background the user may have toggled one, and only this side can notice.
     */
    fun notifyFocus(focused: Boolean) {
        if (close.value != null) return
        if (focused) {
            lastLockKeys?.let { inputActor.submit(RemoteInputActor.LockWrite(it)) }
        } else {
            releaseHeldKeys()
        }
    }

    /**
     * The platform's current lock-key state, read where the UI can see it; null = unknown. Synced
     * to the server when it changes — the remote session keeps its own Caps/Num/Scroll and drifts
     * apart silently otherwise (F-13).
     */
    fun onLockKeys(keys: LockKeys?) {
        if (close.value != null || keys == null || keys == lastLockKeys) return
        lastLockKeys = keys
        inputActor.submit(RemoteInputActor.LockWrite(keys))
    }


    /** Send local clipboard text to the server. */
    fun onLocalClipboard(text: String) {
        if (close.value != null || !clipboardShared) return
        lifetime.send { session.sendClipboardText(text) }
    }

    private companion object {
        const val RESIZE_DEBOUNCE_MS = 400L

        val CTRL_ALT_DEL = listOf(Key.CtrlLeft, Key.AltLeft, Key.Delete)
    }
}
