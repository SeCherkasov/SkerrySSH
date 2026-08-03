package app.skerry.ui.desktop

import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/**
 * Reads the session's X resource database (the root window's `RESOURCE_MANAGER`, what `xrdb -query`
 * prints) over its own X11 connection.
 *
 * Opened before AWT so that reading it can't be confused with AWT's own display: the point is to
 * observe the property as it changes, which `XResourceManagerString` cannot do — Xlib caches that
 * string when the connection opens.
 */
internal class XResourceDatabase private constructor(
    private val connection: X11Connection,
    private val root: X11.Window,
    private val atom: X11.Atom,
) : AutoCloseable {

    private val x11 get() = connection.x11
    private val display get() = connection.display

    /** The database as a single string, or null when the property is absent or unreadable. */
    fun read(): String? = try {
        val type = X11.AtomByReference()
        val format = IntByReference()
        val items = NativeLongByReference()
        val remaining = NativeLongByReference()
        val data = PointerByReference()
        val status = x11.XGetWindowProperty(
            display,
            root,
            atom,
            NativeLong(0),
            // In 32-bit words: the database is a few hundred bytes, this is room to spare.
            NativeLong(MAX_LENGTH_WORDS),
            false,
            X11.Atom(X11.AnyPropertyType.toLong()),
            type,
            format,
            items,
            remaining,
            data,
        )
        if (status != X11.Success) return null
        // Whatever the format, a property that exists came with a buffer that has to be released —
        // so take it first, and let the format decide only whether it is worth reading.
        val pointer = data.value ?: return null
        try {
            // 8-bit string data is the only shape we know how to read; anything else (a property
            // some other client wrote in another format) counts as unreadable, not parsed blind.
            if (format.value != BYTE_FORMAT) return null
            pointer.getString(0).takeIf { it.isNotEmpty() }
        } finally {
            x11.XFree(pointer)
        }
    } catch (_: Throwable) {
        // The connection can go away under us (XWayland restart); an unreadable database is the
        // same answer as an empty one — the caller keeps waiting or gives up on its budget.
        null
    }

    override fun close() = connection.close()

    companion object {
        private const val MAX_LENGTH_WORDS = 65_536L

        /** `format` for 8-bit data, the only property shape this reads. */
        private const val BYTE_FORMAT = 8

        /**
         * Opens a connection to `$DISPLAY`, or returns null when there is none (non-Linux, headless,
         * no X server) or the native bindings are missing. Opening is what starts XWayland on a
         * Wayland session with no X11 client yet, which is exactly the case this guards.
         */
        fun open(): XResourceDatabase? {
            val connection = X11Connection.open() ?: return null
            return try {
                XResourceDatabase(
                    connection,
                    connection.x11.XDefaultRootWindow(connection.display),
                    connection.x11.XInternAtom(connection.display, "RESOURCE_MANAGER", false),
                )
            } catch (_: Throwable) {
                // The connection is live at this point, so it has to be released here — the object
                // that would have owned it was never built.
                connection.close()
                null
            }
        }
    }
}
