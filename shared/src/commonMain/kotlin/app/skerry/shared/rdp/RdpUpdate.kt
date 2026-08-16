package app.skerry.shared.rdp

/**
 * A decoded server→client event, the RDP sibling of `VncUpdate`. [Region] means the framebuffer
 * changed in the listed rectangles; [Resize] means the desktop size changed (the buffer has already
 * been reallocated); [PointerShape]/[PointerPosition]/[PointerVisible] carry the remote cursor;
 * [Frame] brackets a set of updates the server wants acknowledged; [Closed] ends the session.
 */
sealed interface RdpUpdate {
    data class Region(val rects: List<RdpRect>) : RdpUpdate

    data class Resize(val width: Int, val height: Int) : RdpUpdate

    /**
     * The device playing the session's sound stopped taking blocks ([failing] true), or started
     * again. Not a session failure: the picture and the input are unaffected, only the sound is
     * gone, so it is reported rather than thrown.
     */
    data class AudioPlayback(val failing: Boolean) : RdpUpdate

    /**
     * The server opened the display control channel and stated its limits, so resolution requests
     * are worth making (MS-RDPEDISP). A server without that channel never emits this, and the
     * session keeps the size it connected with.
     */
    data object ResizeSupported : RdpUpdate

    /**
     * A cursor sprite. [argb] is [width]×[height] row-major with alpha already applied from the
     * AND/XOR masks; ([hotspotX], [hotspotY]) is the pixel that sits under the pointer. [invert]
     * carries the shape's screen-inverting pixels (the text I-beam) as a plane of their own —
     * opaque white where the screen underneath flips, transparent elsewhere, null when the shape
     * has none — because inversion is a blend, not a colour, and cannot ride in [argb].
     */
    data class PointerShape(
        val argb: IntArray,
        val width: Int,
        val height: Int,
        val hotspotX: Int,
        val hotspotY: Int,
        val invert: IntArray? = null,
    ) : RdpUpdate {
        override fun equals(other: Any?): Boolean =
            other is PointerShape && width == other.width && height == other.height &&
                hotspotX == other.hotspotX && hotspotY == other.hotspotY &&
                argb.contentEquals(other.argb) && invert.contentEquals(other.invert)

        override fun hashCode(): Int =
            ((((width * 31 + height) * 31 + hotspotX) * 31 + hotspotY) * 31 + argb.contentHashCode()) * 31 +
                invert.contentHashCode()
    }

    /** The server moved the pointer itself (a program warped it, not the user). */
    data class PointerPosition(val x: Int, val y: Int) : RdpUpdate

    /**
     * A System Pointer Update: false is SYSPTR_NULL, the hidden pointer; true is SYSPTR_DEFAULT, the
     * ordinary arrow — a shape the server never sends, so the client's own pointer stands in for it.
     */
    data class PointerVisible(val visible: Boolean) : RdpUpdate

    /**
     * Frame boundary of a surface-command stream. [begin] false marks the end of frame [frameId],
     * which is what the client acknowledges so the server knows how far behind the display is.
     */
    data class Frame(val frameId: Int, val begin: Boolean) : RdpUpdate

    /** Text the remote clipboard now holds; the view mirrors it into the system clipboard. */
    data class ClipboardText(val text: String) : RdpUpdate

    /** The server asked the client to sound the bell / flash. */
    data object Bell : RdpUpdate

    /** Session ended. [cleanExit] true = the server closed it in an orderly way. */
    data class Closed(val cleanExit: Boolean, val reason: String = "") : RdpUpdate
}

/**
 * A rectangle in framebuffer coordinates. The same shape as the UI contract's rectangle on
 * purpose (an alias, since PR 6's allocation pass): `RdpUpdate.Region`'s list previously got
 * copied element by element into `RemoteRect`s on every frame just to cross the naming boundary.
 */
typealias RdpRect = app.skerry.shared.graphics.RemoteRect
