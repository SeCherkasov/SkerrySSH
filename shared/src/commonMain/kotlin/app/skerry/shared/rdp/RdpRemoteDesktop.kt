package app.skerry.shared.rdp

import app.skerry.shared.graphics.IdentityCache
import app.skerry.shared.graphics.RemoteDesktopCapabilities
import app.skerry.shared.graphics.RemoteDesktopQuality
import app.skerry.shared.graphics.RemoteDesktopSession
import app.skerry.shared.graphics.RemoteDesktopUpdate
import app.skerry.shared.graphics.RemoteFramebuffer
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.shared.graphics.RemoteRect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Presents an [RdpSession] as a [RemoteDesktopSession].
 *
 * The one real translation is the pointer. The shared contract carries pointer *state* (a button
 * mask, as RFB does), while RDP wants *transitions* — so this keeps the last mask and derives the
 * presses and releases from the difference. Wheel bits are edges rather than state and are handled
 * on their own, or a scroll would arrive as a button that is never let go.
 */
class RdpRemoteDesktop(
    private val session: RdpSession,
    /**
     * Defaults to the host the session is really on, so a connection a farm broker sent elsewhere
     * does not keep displaying the address that was dialled.
     */
    override val title: String = session.connectedHost,
) : RemoteDesktopSession {

    // Written from the UI's single input actor; the mask-to-transition translation needs no lock
    // once callers are serialised, and they are — see RemoteDesktopScreenState's input actor (F-10).
    private var lastButtons = 0

    override val framebuffer: RemoteFramebuffer get() = session.framebuffer

    override val diagnostics get() = session.diagnostics

    override val capabilities = RemoteDesktopCapabilities(
        // RDP negotiates its own codecs at connect time and has no per-session quality knob, and the
        // cursor is always drawn by the client, so neither control would do anything. Resizing is a
        // channel of its own, and the UI waits for the server to open it before offering the toggle.
        adjustableQuality = false,
        remoteResize = true,
        cursorHandover = false,
        // Both were settled by the connection request: a session that did not ask for sound has no
        // device to mute, and one that did not ask for the clipboard has no channel to share.
        audio = session.audioAvailable,
        clipboard = session.clipboardAvailable,
    )

    override val updates: Flow<RemoteDesktopUpdate> = session.updates.map { update ->
        when (update) {
            is RdpUpdate.Region -> RemoteDesktopUpdate.Region(
                update.rects.map { RemoteRect(it.x, it.y, it.width, it.height) },
            )

            is RdpUpdate.Resize -> RemoteDesktopUpdate.Resize(update.width, update.height)
            is RdpUpdate.ResizeSupported -> RemoteDesktopUpdate.RemoteResizeSupported
            is RdpUpdate.PointerShape -> mappedShape(update)

            is RdpUpdate.PointerPosition -> RemoteDesktopUpdate.CursorPosition(update.x, update.y)
            is RdpUpdate.PointerVisible -> RemoteDesktopUpdate.CursorVisible(update.visible)
            is RdpUpdate.ClipboardText -> RemoteDesktopUpdate.ClipboardText(update.text)
            is RdpUpdate.AudioPlayback -> RemoteDesktopUpdate.AudioPlaybackFailing(update.failing)
            is RdpUpdate.Bell -> RemoteDesktopUpdate.Bell
            is RdpUpdate.Closed -> RemoteDesktopUpdate.Closed(update.cleanExit, update.reason)
            // Frame markers pace the protocol and are acknowledged inside the codec; the screen has
            // nothing to do with them.
            is RdpUpdate.Frame -> RemoteDesktopUpdate.Region(emptyList())
        }
    }

    override suspend fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        val wheelBits = buttonMask and WHEEL_MASK
        if (wheelBits != 0) {
            // A wheel "click" arrives as a press of one of these bits; the release that follows
            // carries no information and would scroll a second time if it were forwarded.
            when {
                wheelBits and WHEEL_UP != 0 -> session.sendWheel(1, RdpWheelAxis.Vertical, x, y)
                wheelBits and WHEEL_DOWN != 0 -> session.sendWheel(-1, RdpWheelAxis.Vertical, x, y)
                wheelBits and WHEEL_LEFT != 0 -> session.sendWheel(-1, RdpWheelAxis.Horizontal, x, y)
                wheelBits and WHEEL_RIGHT != 0 -> session.sendWheel(1, RdpWheelAxis.Horizontal, x, y)
            }
            return
        }

        val buttons = buttonMask and BUTTON_MASK
        if (buttons == lastButtons) {
            session.sendPointerMove(x, y)
            return
        }
        for ((bit, button) in BUTTONS) {
            val wasDown = lastButtons and bit != 0
            val isDown = buttons and bit != 0
            if (wasDown != isDown) session.sendPointerButton(button, isDown, x, y)
        }
        lastButtons = buttons
    }

    // A cached pointer re-announcement is the same PointerShape instance; wrapping it into a fresh
    // CursorShape each time destroyed that identity before the UI could use it, so every arrow ↔
    // I-beam switch rebuilt a sprite (F-26). One wrapper per instance keeps identity end to end.
    private val mappedShapes =
        IdentityCache<RdpUpdate.PointerShape, RemoteDesktopUpdate.CursorShape>(MAPPED_SHAPE_CACHE)

    private fun mappedShape(update: RdpUpdate.PointerShape): RemoteDesktopUpdate.CursorShape =
        mappedShapes.getOrPut(update) {
            RemoteDesktopUpdate.CursorShape(
                argb = update.argb,
                width = update.width,
                height = update.height,
                hotspotX = update.hotspotX,
                hotspotY = update.hotspotY,
                invert = update.invert,
            )
        }

    override suspend fun sendKey(event: RemoteKeyEvent, down: Boolean) {
        // A scancode replays into the remote keyboard driver, which is what makes the remote layout
        // apply. Only when the key has none — a character the local layout composes that no key on
        // the remote one carries — is the code point sent instead.
        when {
            event.scancode != 0 -> session.sendKey(event.scancode, down, event.extended)
            event.codePoint != 0 -> session.sendUnicode(event.codePoint, down)
        }
    }

    override suspend fun syncLockKeys(scroll: Boolean, num: Boolean, caps: Boolean) =
        session.sendLockKeys(scroll, num, caps)

    override suspend fun sendClipboardText(text: String) = session.sendClipboardText(text)

    override suspend fun requestFullUpdate() =
        session.requestRefresh(listOf(RdpRect(0, 0, session.desktopWidth, session.desktopHeight)))

    /** RDP settles its codecs during the capability exchange; there is no per-session quality knob. */
    override suspend fun setQuality(quality: RemoteDesktopQuality) = Unit

    /**
     * A resolution change travels on the display control channel (MS-RDPEDISP), and is a no-op on a
     * server that never opened one — the session then keeps the size it connected with and the view
     * scales to fit.
     */
    override suspend fun setDesktopSize(width: Int, height: Int) = session.setDesktopSize(width, height)

    /** The RDP cursor is always the client's to draw; there is nothing to hand over. */
    override suspend fun setLocalCursor(enabled: Boolean) = Unit

    /**
     * A suppressed server draws nothing, so while the window was away its screen and the last frame
     * held here drifted apart — the picture comes back only if the whole of it is asked for. The
     * server is under no obligation to volunteer that repaint (MS-RDPBCGR 2.2.11.3 says what stops,
     * not what resumes), and an incremental update would leave the stale pixels around whatever
     * happened to change. Which is why a server that cannot be asked for the repaint is not asked
     * to stop either — see [RdpSession.outputSuppressionSupported].
     */
    override suspend fun setOutputVisible(visible: Boolean) {
        if (!session.outputSuppressionSupported) return
        session.setOutputVisible(visible)
        if (visible) requestFullUpdate()
    }

    override suspend fun setAudioMuted(muted: Boolean) = session.setAudioMuted(muted)

    override suspend fun close() = session.close()

    private companion object {
        const val BUTTON_LEFT = 1 shl 0
        const val BUTTON_MIDDLE = 1 shl 1
        const val BUTTON_RIGHT = 1 shl 2
        const val WHEEL_UP = 1 shl 3
        const val WHEEL_DOWN = 1 shl 4
        const val WHEEL_LEFT = 1 shl 5
        const val WHEEL_RIGHT = 1 shl 6
        const val BUTTON_BACK = 1 shl 7
        const val BUTTON_FORWARD = 1 shl 8

        /** The protocol's pointer cache holds 25 slots; a few extra cover uncached shapes. */
        const val MAPPED_SHAPE_CACHE = 32

        const val WHEEL_MASK = WHEEL_UP or WHEEL_DOWN or WHEEL_LEFT or WHEEL_RIGHT
        const val BUTTON_MASK = BUTTON_LEFT or BUTTON_MIDDLE or BUTTON_RIGHT or BUTTON_BACK or BUTTON_FORWARD

        val BUTTONS = listOf(
            BUTTON_LEFT to RdpMouseButton.Left,
            BUTTON_MIDDLE to RdpMouseButton.Middle,
            BUTTON_RIGHT to RdpMouseButton.Right,
            BUTTON_BACK to RdpMouseButton.Extended1,
            BUTTON_FORWARD to RdpMouseButton.Extended2,
        )
    }
}
