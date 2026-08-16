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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
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
    private val input = Channel<InputWrite>(Channel.UNLIMITED)

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

    /**
     * Where the server last put the pointer itself (SetCursorPos — installers, remote apps that
     * recentre the mouse). Drawn instead of the local position until the local mouse next speaks,
     * so the user is not aiming at one place while clicking in another (F-21). In view-only it is
     * the only pointer source there is, since no local events are sent.
     */
    var serverPointer: IntOffset? by mutableStateOf(null)
        private set

    // Sprites already built, keyed by the shape *instance*: a cached pointer re-announcement is
    // the same object end to end, so switching arrow ↔ I-beam costs a list scan, not a bitmap
    // rebuild (F-26). Content-equal duplicates miss and rebuild, which is only a small waste.
    private val spriteCache = ArrayDeque<Pair<RemoteDesktopUpdate.CursorShape, VncCursorImage?>>()

    /**
     * The server asked for the ordinary system pointer instead of a shape of its own (RDP's
     * SYSPTR_DEFAULT). There is nothing to draw, so the local pointer is shown rather than hidden —
     * distinct from a hidden cursor, where neither is drawn.
     */
    var systemCursor by mutableStateOf(false)
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
        // No handover, nothing to hand: on RDP setLocalCursor is a documented no-op and the
        // cursor stays the client's to draw — the sprite keeps showing at the server-reported
        // position — so the full repaint bought nothing (F-27).
        if (!capabilities.cursorHandover) return
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
        // A hidden session takes no more key events, so whatever is down now stays down on the
        // server until it comes back — release it on the way out, like a focus loss (F-12).
        if (!visible) releaseHeldKeys()
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
        // Through the actor like every other key, so the sequence cannot interleave with typing.
        val keys = CTRL_ALT_DEL.mapNotNull { remoteKeyEvent(it, 0) }
        keys.forEach { input.trySend(KeyWrite(it, down = true)) }
        keys.asReversed().forEach { input.trySend(KeyWrite(it, down = false)) }
    }

    private val _close = MutableStateFlow<RemoteDesktopUpdate.Closed?>(null)

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
    val close: StateFlow<RemoteDesktopUpdate.Closed?> = _close.asStateFlow()

    /** Latest clipboard text from the remote host; the view mirrors it into the system clipboard. */
    var serverClipboard: String? by mutableStateOf(null)
        private set

    val serverName: String get() = session.title

    /** The current frame image for drawing. */
    val imageBitmap: ImageBitmap get() = image.bitmap

    init {
        scope.launch { runInputActor() }
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
                // Only if nothing closed the session already: a read loop that blows up behind an
                // orderly close must not replace the server's own words with a bare drop.
                _close.compareAndSet(null, RemoteDesktopUpdate.Closed(cleanExit = false))
            }
        }
    }

    private fun onUpdate(update: RemoteDesktopUpdate) {
        when (update) {
            is RemoteDesktopUpdate.Region -> {
                // An empty region is a protocol event with no pixels behind it (an RDP frame
                // marker); redrawing on it would burn a frame for nothing.
                if (update.rects.isNotEmpty()) {
                    val started = TimeSource.Monotonic.markNow()
                    image.writeRects(update.rects, session.framebuffer.pixels, session.framebuffer.width)
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
                lastLockKeys?.let { input.trySend(LockWrite(it)) }
            }

            is RemoteDesktopUpdate.RemoteResizeSupported -> {
                canResizeRemote = true
                // A restored-from-profile flag is already on before support is known — apply it now.
                if (remoteResize) scheduleRemoteResize()
            }

            is RemoteDesktopUpdate.Closed -> _close.value = update

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
            is RemoteDesktopUpdate.CursorShape -> {
                val hit = spriteCache.firstOrNull { it.first === update }
                cursor = if (hit != null) {
                    hit.second
                } else {
                    VncCursorImage.of(update).also { sprite ->
                        spriteCache.addFirst(update to sprite)
                        if (spriteCache.size > SPRITE_CACHE_SIZE) spriteCache.removeLast()
                    }
                }
                systemCursor = false
            }

            is RemoteDesktopUpdate.CursorPosition -> serverPointer = IntOffset(update.x, update.y)
            // "Visible" here is the server asking for its default pointer, not for the shape it sent
            // last: the sprite goes, and the local pointer takes over. Hidden drops both.
            is RemoteDesktopUpdate.CursorVisible -> {
                cursor = null
                systemCursor = update.visible
            }

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
        // The local mouse speaking takes the cursor back from a server-side warp (F-21).
        serverPointer = null
        input.trySend(PointerWrite(x, y, buttonMask))
    }

    /** Forward a key event. No-op in view-only mode. */
    fun onKey(event: RemoteKeyEvent, down: Boolean) {
        if (viewOnly) return
        val id = keyIdentity(event)
        if (down) heldKeys[id] = event else heldKeys.remove(id)
        input.trySend(KeyWrite(event, down))
    }

    /**
     * The surface gained or lost keyboard focus. Losing it releases everything held (F-12): the
     * key-up for an Alt+Tab goes to the local desktop, so without this the server keeps Alt down
     * for the rest of the session. Gaining it re-syncs the lock keys (F-13) — while the session was
     * in the background the user may have toggled one, and only this side can notice.
     */
    fun notifyFocus(focused: Boolean) {
        if (focused) {
            lastLockKeys?.let { input.trySend(LockWrite(it)) }
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
        if (keys == null || keys == lastLockKeys) return
        lastLockKeys = keys
        input.trySend(LockWrite(keys))
    }

    // ---- the input actor (F-10) ----

    private sealed interface InputWrite
    private data class PointerWrite(val x: Int, val y: Int, val mask: Int) : InputWrite
    private data class KeyWrite(val event: RemoteKeyEvent, val down: Boolean) : InputWrite
    private data class LockWrite(val keys: LockKeys) : InputWrite

    // Keys currently down on the server, in press order; written from the UI thread only.
    private val heldKeys = LinkedHashMap<Long, RemoteKeyEvent>()

    private fun releaseHeldKeys() {
        for (event in heldKeys.values.toList().asReversed()) input.trySend(KeyWrite(event, false))
        heldKeys.clear()
    }

    /** What makes a press and its release the same key, whichever field the protocol will use. */
    private fun keyIdentity(event: RemoteKeyEvent): Long = when {
        event.scancode != 0 -> event.scancode.toLong() or (if (event.extended) 1L shl 32 else 0L)
        event.keySym != 0L -> event.keySym or (1L shl 40)
        else -> event.codePoint.toLong() or (1L shl 48)
    }

    /**
     * The one writer (F-10): draining a single channel is what gives input the order it was made
     * in — the fire-and-forget launches this replaces raced each other across dispatcher threads,
     * so a key-up could overtake its key-down and a click could run ahead of the move that aimed
     * it. It is also the only place moves coalesce (F-11): a run of pure moves collapses to the
     * freshest and is paced to [MOVE_INTERVAL], while a queued click, key or wheel is never made
     * to wait behind that pacing — the pending move goes out first, so a click always lands at a
     * fresh position.
     */
    private suspend fun runInputActor() {
        var pending: InputWrite? = null
        while (true) {
            val event = pending ?: input.receive()
            pending = when (event) {
                is PointerWrite ->
                    if (event.mask == actorLastMask) {
                        sendCollapsedMove(event)
                    } else {
                        write { session.sendPointer(event.x, event.y, event.mask) }
                        // Wheel bits are edges, not state: the mask a later move repeats has none.
                        actorLastMask = event.mask and BUTTONS_ONLY
                        null
                    }

                is KeyWrite -> {
                    write { session.sendKey(event.event, event.down) }
                    null
                }

                is LockWrite -> {
                    write { session.syncLockKeys(event.keys.scroll, event.keys.num, event.keys.caps) }
                    null
                }
            }
        }
    }

    // The actor's own state; nothing outside [runInputActor]'s call tree touches these.
    private var actorLastMask = 0
    private var lastMoveAt: TimeSource.Monotonic.ValueTimeMark? = null

    /**
     * Send the freshest of the queued pure moves, pacing the stream to [MOVE_INTERVAL] — but only
     * while nothing else waits. Returns the first non-move it ran into, which the caller handles
     * next, so a click is delivered right after the move that positioned it and is never delayed.
     */
    private suspend fun sendCollapsedMove(first: PointerWrite): InputWrite? {
        var move = first
        var interrupt: InputWrite? = null
        fun collapseQueuedMoves() {
            while (interrupt == null) {
                val queued = input.tryReceive().getOrNull() ?: return
                if (queued is PointerWrite && queued.mask == actorLastMask) move = queued else interrupt = queued
            }
        }
        collapseQueuedMoves()
        if (interrupt == null) {
            val since = lastMoveAt?.elapsedNow()
            if (since != null && since < MOVE_INTERVAL) {
                delay(MOVE_INTERVAL - since)
                collapseQueuedMoves()
            }
        }
        lastMoveAt = TimeSource.Monotonic.markNow()
        write { session.sendPointer(move.x, move.y, move.mask) }
        return interrupt
    }

    /** Same swallow-the-write discipline as [send], for the actor's own writes. */
    private suspend fun write(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
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
     * process down on Android. The dropped session surfaces through [close] instead, which is the
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

        /** Floor between two pure moves: ~120/s, about what a mature client sends (F-11). */
        val MOVE_INTERVAL = 8.milliseconds

        /** The state-carrying bits of the RFB mask; wheel bits (3..6) are edges and never repeat. */
        const val BUTTONS_ONLY = 0b110000111

        /** Matches the RDP pointer cache (25 slots) with room for uncached shapes on top. */
        const val SPRITE_CACHE_SIZE = 32

        val CTRL_ALT_DEL = listOf(Key.CtrlLeft, Key.AltLeft, Key.Delete)
    }
}
