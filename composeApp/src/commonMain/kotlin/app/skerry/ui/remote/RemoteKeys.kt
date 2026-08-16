package app.skerry.ui.remote

import androidx.compose.ui.input.key.Key
import app.skerry.shared.graphics.RemoteKeyEvent
import app.skerry.ui.rdp.scancodeFor
import app.skerry.ui.vnc.keySymFor

/**
 * Build the protocol-neutral key event from a Compose key and the printable code point of the
 * event, filling in everything either protocol might use.
 *
 * Both halves are computed here rather than one being derived from the other: a key with no X11
 * keysym can still have a scancode (the Windows key on a layout RFB has no name for), and a
 * character composed by the local layout has a code point but no scancode at all. Returns null when
 * neither half exists — there is nothing to send.
 */
fun remoteKeyEvent(key: Key, codePoint: Int): RemoteKeyEvent? {
    val keySym = keySymFor(key, codePoint)
    val scancode = scancodeFor(key)
    if (keySym == 0L && scancode == null && codePoint == 0) return null
    // The single-scancode common case stays in the flat fields; anything longer — or anything the
    // flat fields cannot express, like the E1 prefix — travels as a sequence (F-18).
    val single = scancode?.scans?.singleOrNull()?.takeIf { !it.extended1 }
    return RemoteKeyEvent(
        keySym = keySym,
        scancode = single?.scancode ?: 0,
        extended = single?.extended ?: false,
        codePoint = codePoint,
        sequence = if (scancode != null && single == null) scancode.scans else emptyList(),
    )
}
