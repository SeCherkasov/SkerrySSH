package app.skerry.ui.desktop

import com.sun.jna.platform.unix.X11

/**
 * A connection to `$DISPLAY` for the desktop's own X11 work (window moves, reading session
 * settings), separate from the one AWT keeps for itself.
 *
 * One place decides whether talking to X11 is possible at all, so availability doesn't drift
 * between the callers.
 */
internal class X11Connection private constructor(
    val x11: X11,
    val display: X11.Display,
) : AutoCloseable {

    override fun close() {
        try {
            x11.XCloseDisplay(display)
        } catch (_: Throwable) {
            // Best effort: the connection may already be gone, and nothing depends on the release.
        }
    }

    companion object {
        /**
         * Opens a connection, or returns null when there is no X11 to talk to: another platform, no
         * `DISPLAY`, no libX11. On a Wayland session opening one is what starts XWayland, if no
         * other client has yet.
         */
        fun open(): X11Connection? {
            val os = System.getProperty("os.name")?.lowercase().orEmpty()
            if (!os.contains("linux")) return null
            if (System.getenv("DISPLAY").isNullOrEmpty()) return null
            return try {
                val x11 = X11.INSTANCE
                val display = x11.XOpenDisplay(null) ?: return null
                X11Connection(x11, display)
            } catch (_: Throwable) {
                // UnsatisfiedLinkError (no libX11), NoClassDefFoundError — same as having no display.
                null
            }
        }
    }
}
