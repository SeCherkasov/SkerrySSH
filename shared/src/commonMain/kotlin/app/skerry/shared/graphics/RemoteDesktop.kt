package app.skerry.shared.graphics

import kotlinx.coroutines.flow.Flow

/**
 * What a live remote desktop looks like to the UI, whichever protocol is underneath. VNC and RDP
 * differ in almost every wire detail and in none of the things a screen needs: pixels, a stream of
 * changes, and somewhere to send input.
 *
 * The adapters live next to their protocols (`VncRemoteDesktop`, `RdpRemoteDesktop`) so this
 * interface stays free of either one's vocabulary — no keysyms, no scancodes, no encodings.
 */
interface RemoteDesktopSession {
    /** The remote screen's pixels; read by the UI, written only by the read loop. */
    val framebuffer: RemoteFramebuffer

    /** Cold, single-collector stream of changes. Collecting it runs the session. */
    val updates: Flow<RemoteDesktopUpdate>

    /** Name for the tab: the desktop name a VNC server reports, or the RDP host. */
    val title: String

    /** Which of the optional controls this protocol actually has (see [RemoteDesktopCapabilities]). */
    val capabilities: RemoteDesktopCapabilities

    /**
     * Live counters for the diagnostics overlay, written by the read loop. The default is the inert
     * [RemoteDesktopDiagnostics.NONE] so a test double needs nothing; a real session overrides it.
     */
    val diagnostics: RemoteDesktopDiagnostics get() = RemoteDesktopDiagnostics.NONE

    /**
     * Pointer state in framebuffer coordinates. [buttonMask] follows the RFB convention, which is
     * the more expressive of the two: bit 0 left, 1 middle, 2 right, 3/4 wheel up/down, 5/6 wheel
     * left/right, 7/8 the extended buttons. Protocols that send *transitions* rather than state
     * (RDP) derive them from the change since the previous call.
     */
    suspend fun sendPointer(x: Int, y: Int, buttonMask: Int)

    /** A key press or release; each protocol picks the field it needs out of [RemoteKeyEvent]. */
    suspend fun sendKey(event: RemoteKeyEvent, down: Boolean)

    /**
     * Report the local Caps/Num/Scroll state so the remote session does not drift out of step. A
     * no-op where the protocol keeps no lock state of its own (RFB sends keysyms and has none).
     */
    suspend fun syncLockKeys(scroll: Boolean, num: Boolean, caps: Boolean) = Unit

    /** Send local clipboard text to the remote machine. */
    suspend fun sendClipboardText(text: String)

    /** Ask for a full repaint (after a resize, or when the window comes back into view). */
    suspend fun requestFullUpdate()

    /** Quality/compression preference; a no-op where the protocol has no such knob. */
    suspend fun setQuality(quality: RemoteDesktopQuality)

    /** Ask the server to resize its desktop to [width]×[height]; a no-op where unsupported. */
    suspend fun setDesktopSize(width: Int, height: Int)

    /**
     * Choose who draws the cursor: true = we do (the server sends its shape and keeps the
     * framebuffer clean), false = the server paints it into the picture.
     */
    suspend fun setLocalCursor(enabled: Boolean)

    /** Tell the server whether anyone is looking; a hidden window need not be rendered. */
    suspend fun setOutputVisible(visible: Boolean)

    /** Silence the sound coming from the remote machine, or let it through; no-op without audio. */
    suspend fun setAudioMuted(muted: Boolean)

    /** Close the session. Idempotent. */
    suspend fun close()
}

/**
 * Which optional controls a protocol has, so the UI can hide what would do nothing rather than
 * offer a menu item that silently no-ops.
 */
data class RemoteDesktopCapabilities(
    /** The quality/compression menu means something (RFB encodings; RDP negotiates its own). */
    val adjustableQuality: Boolean,
    /** The server can be asked to change its desktop size to match the window. */
    val remoteResize: Boolean,
    /** The cursor can be handed back and forth between client and server. */
    val cursorHandover: Boolean,
    /** The session carries sound, so muting it means something (RDP with a device; never RFB). */
    val audio: Boolean = false,
    /** The session carries a clipboard, so sharing it can be turned off mid-session. */
    val clipboard: Boolean = true,
)

/** Quality preference, where the protocol has one. */
enum class RemoteDesktopQuality { Auto, Low, Medium, High }

/**
 * A key event with everything either protocol might need: [keySym] for RFB, [scancode]/[extended]
 * for RDP, and [codePoint] as the fallback for characters no scancode carries.
 *
 * Filled in by the UI, which is the only layer that knows the local keyboard; each protocol then
 * takes the field it speaks. A key with no scancode still has a code point, and vice versa — which
 * is why both travel rather than one being derived from the other.
 */
data class RemoteKeyEvent(
    val keySym: Long = 0,
    val scancode: Int = 0,
    val extended: Boolean = false,
    val codePoint: Int = 0,
)

/**
 * A change to show. The union of what the two protocols report; a protocol that never produces one
 * of these simply never emits it.
 */
sealed interface RemoteDesktopUpdate {
    /** The framebuffer changed in these rectangles. */
    data class Region(val rects: List<RemoteRect>) : RemoteDesktopUpdate

    /** The desktop size changed; the buffer has already been reallocated. */
    data class Resize(val width: Int, val height: Int) : RemoteDesktopUpdate

    /** The server accepts resize requests (RFB's ExtendedDesktopSize; always true for RDP). */
    data object RemoteResizeSupported : RemoteDesktopUpdate

    /**
     * A cursor sprite, ARGB row-major with **straight (non-premultiplied) alpha** — both protocol
     * codecs emit that model and the platform bridges convert where their bitmap wants premul —
     * plus the pixel that sits under the pointer. [invert] is the shape's screen-inverting plane
     * (RDP's text I-beam) — opaque white where the pixels underneath flip, null when the shape has
     * none — drawn with a difference blend over the framebuffer.
     *
     * A re-announced cached shape (RDP's pointer cache) arrives as the *same instance*, which is
     * what lets the view reuse the sprite it already built instead of comparing whole pixel arrays.
     */
    data class CursorShape(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        val hotspotX: Int,
        val hotspotY: Int,
        val invert: IntArray? = null,
    ) : RemoteDesktopUpdate {
        override fun equals(other: Any?): Boolean =
            other is CursorShape && width == other.width && height == other.height &&
                hotspotX == other.hotspotX && hotspotY == other.hotspotY &&
                argb.contentEquals(other.argb) && invert.contentEquals(other.invert)

        override fun hashCode(): Int =
            ((((width * 31 + height) * 31 + hotspotX) * 31 + hotspotY) * 31 + argb.contentHashCode()) * 31 +
                invert.contentHashCode()
    }

    /** The server moved the pointer itself. */
    data class CursorPosition(val x: Int, val y: Int) : RemoteDesktopUpdate

    /**
     * Whether the remote cursor is drawn at all. False hides it; true is the server asking for the
     * ordinary system pointer rather than a shape of its own (RDP's SYSPTR_DEFAULT), which leaves
     * the client's own pointer to stand in — it is not a request to redraw the last shape.
     */
    data class CursorVisible(val visible: Boolean) : RemoteDesktopUpdate

    /** The remote clipboard's new text. */
    data class ClipboardText(val text: String) : RemoteDesktopUpdate

    data object Bell : RemoteDesktopUpdate

    /**
     * The local device playing the session's sound stopped taking blocks ([failing] true), or
     * started again. Only protocols that carry audio ever emit it.
     */
    data class AudioPlaybackFailing(val failing: Boolean) : RemoteDesktopUpdate

    /**
     * The session ended. [cleanExit] true = the peer closed it in an orderly way; [reason] carries
     * the server's own explanation where it gave one (a refused logon, an idle timeout).
     */
    data class Closed(val cleanExit: Boolean, val reason: String = "") : RemoteDesktopUpdate
}

/** A rectangle in framebuffer coordinates. */
data class RemoteRect(val x: Int, val y: Int, val width: Int, val height: Int)
