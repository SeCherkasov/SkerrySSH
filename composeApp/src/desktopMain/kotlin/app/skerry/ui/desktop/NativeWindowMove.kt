package app.skerry.ui.desktop

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import java.awt.Window

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
     * Asks the WM to start moving [window], following the pointer, from screen coordinates
     * [screenX]/[screenY] (where the drag began). Returns false if unavailable or the window has no
     * X11 id yet, so the caller can fall back. [button] is the mouse button held (1 = left).
     */
    fun startMove(window: Window, screenX: Int, screenY: Int, button: Int = 1): Boolean {
        val s = session ?: return false
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
            event.xclient.data.l[0] = NativeLong(screenX.toLong())
            event.xclient.data.l[1] = NativeLong(screenY.toLong())
            event.xclient.data.l[2] = NativeLong(NET_WM_MOVERESIZE_MOVE.toLong())
            event.xclient.data.l[3] = NativeLong(button.toLong())
            event.xclient.data.l[4] = NativeLong(1) // source indication: normal application
            event.write()
            // Under XWayland the real pointer belongs to the compositor, so the WM starts the move
            // without us having to release an X pointer grab first.
            s.x11.XSendEvent(s.display, s.root, 0, ROOT_EVENT_MASK, event)
            s.x11.XFlush(s.display)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
