package app.skerry.ui.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Window
import java.awt.geom.AffineTransform
import kotlin.math.roundToLong

/**
 * Hands an interactive window drag to the X11 window manager via `_NET_WM_MOVERESIZE`, instead of
 * repositioning the window frame-by-frame from the app thread (what Compose's `WindowDraggableArea`
 * does). Once the undecorated main window dropped its server-side titlebar, the manual drag lost the
 * compositor's smoothing and felt laggy under GNOME/Mutter; letting the WM move the window restores
 * the native "liquid" feel.
 *
 * Linux/X11 only (works under XWayland, which is where a stock-JDK AWT app runs on a Wayland
 * session). On every other platform, or if the X11 bindings can't be initialised, [isAvailable] is
 * false and the caller keeps using the manual drag.
 */
object NativeWindowMove {
    // From X11/Xutil.h: the direction telling the WM to start a plain move (not a resize).
    private const val NET_WM_MOVERESIZE_MOVE = 8
    // SubstructureRedirectMask (1<<20) | SubstructureNotifyMask (1<<19): the mask the WM listens on
    // for client messages posted to the root window.
    private val ROOT_EVENT_MASK = NativeLong((1L shl 20) or (1L shl 19))

    private class X11Session(
        val x11: X11,
        val display: X11.Display,
        val root: X11.Window,
        val moveResizeAtom: X11.Atom,
    )

    // Resolved once. null = unavailable (non-Linux, no DISPLAY, or the native lookup failed).
    private val session: X11Session? by lazy { initSession() }

    private fun initSession(): X11Session? {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        if (!os.contains("linux")) return null
        if (System.getenv("DISPLAY").isNullOrEmpty()) return null
        return try {
            val x11 = X11.INSTANCE
            // Our own connection to $DISPLAY. The XID from Native.getWindowID is server-wide, so a
            // client message sent from this connection to the root window reaches the WM fine.
            val display = x11.XOpenDisplay(null) ?: return null
            val root = x11.XDefaultRootWindow(display)
            val atom = x11.XInternAtom(display, "_NET_WM_MOVERESIZE", false)
            X11Session(x11, display, root, atom)
        } catch (_: Throwable) {
            // UnsatisfiedLinkError (no libX11), NoClassDefFoundError, etc. — fall back to manual drag.
            null
        }
    }

    /** True when a native move can be started; checked once per session and cached. */
    fun isAvailable(): Boolean = session != null

    /**
     * Asks the WM to start moving [window], following the pointer, from AWT screen coordinates
     * [screenX]/[screenY] (where the drag began). [button] is the mouse button held (1 = left).
     * Returns false when the request never reached the WM — the gesture is then over either way,
     * since the caller commits to this path once, on [isAvailable], not per drag.
     */
    fun startMove(window: Window, screenX: Int, screenY: Int, button: Int = 1): Boolean {
        val s = session ?: return false
        val request = moveRequest(Point(screenX, screenY), screenTransform(window), button)
        val xid = try {
            Native.getWindowID(window)
        } catch (_: Throwable) {
            return false
        }
        if (xid == 0L) return false
        return try {
            val win = X11.Window(xid)
            val event = X11.XEvent()
            event.setType(X11.XClientMessageEvent::class.java)
            event.xclient.type = X11.ClientMessage
            event.xclient.serial = NativeLong(0)
            event.xclient.send_event = 1
            event.xclient.display = s.display
            event.xclient.window = win
            event.xclient.message_type = s.moveResizeAtom
            event.xclient.format = 32
            event.xclient.data.setType("l")
            request.words().forEachIndexed { i, word -> event.xclient.data.l[i] = NativeLong(word) }
            event.write()
            // Under XWayland the real pointer belongs to the compositor, so the WM starts the move
            // without us having to release an X pointer grab first.
            val sent = s.x11.XSendEvent(s.display, s.root, 0, ROOT_EVENT_MASK, event)
            s.x11.XFlush(s.display)
            sent != 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * The window's user-space→device transform, or null when it can't be determined. A window that
     * isn't on a screen yet has no [java.awt.GraphicsConfiguration] of its own, so fall back to the
     * default screen's rather than assuming an unscaled session.
     */
    private fun screenTransform(window: Window): AffineTransform? = try {
        (
            window.graphicsConfiguration
                ?: GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration
            )?.defaultTransform
    } catch (_: Throwable) {
        null
    }

    /** The `_NET_WM_MOVERESIZE` payload, in the order the protocol lays out `data.l`. */
    internal data class MoveResizeRequest(
        val rootX: Long,
        val rootY: Long,
        val direction: Long,
        val button: Long,
        val source: Long,
    ) {
        fun words(): List<Long> = listOf(rootX, rootY, direction, button, source)
    }

    /**
     * Builds the request that moves the window the pointer is over.
     *
     * [pointer] is an AWT screen position, which under HiDPI is in user space — Compose Desktop
     * turns AWT's ui scaling on, so `MouseInfo` and window bounds are scaled down by [transform].
     * The protocol is specified in X11 pixels, and a point in the wrong space lands far from the
     * real pointer: Mutter looks for one within 64 px before it starts the grab, finds nothing, and
     * drops the request without a word. Hence the conversion back to device pixels here.
     */
    internal fun moveRequest(pointer: Point, transform: AffineTransform?, button: Int) =
        MoveResizeRequest(
            rootX = (pointer.x * usableScale(transform?.scaleX)).roundToLong(),
            rootY = (pointer.y * usableScale(transform?.scaleY)).roundToLong(),
            direction = NET_WM_MOVERESIZE_MOVE.toLong(),
            button = button.toLong(),
            source = 1L, // source indication: normal application
        )

    // A scale that isn't a positive finite number (0, NaN, a mirrored display's negative factor)
    // would throw the drag onto 0,0 or the wrong side of the screen, nowhere near the pointer. An
    // unscaled point at least still works on an unscaled session.
    private fun usableScale(scale: Double?): Double =
        if (scale != null && scale.isFinite() && scale > 0.0) scale else 1.0
}
