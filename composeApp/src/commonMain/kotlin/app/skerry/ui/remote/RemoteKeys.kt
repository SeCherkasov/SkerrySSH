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

/**
 * The modifier a key stands for, for the state that has to keep the server's idea of them in step.
 *
 * Super (`Key.MetaLeft`/`MetaRight`, forwarded as the Windows key) is deliberately not one of them:
 * the reconciliation needs a truth to compare against, and `isMetaPressed` carries Super on macOS
 * only — AWT never sets it for the Super key on X11 or Windows. Reconciling against a flag that is
 * always false there would lift the key mid-chord and turn Win+R into a bare "r".
 */
enum class RemoteModifier { Ctrl, Alt, Shift }

/** Which modifier [key] is, if it is one the local machine can be asked about. */
fun remoteModifier(key: Key): RemoteModifier? = when (key) {
    Key.CtrlLeft, Key.CtrlRight -> RemoteModifier.Ctrl
    Key.AltLeft, Key.AltRight -> RemoteModifier.Alt
    Key.ShiftLeft, Key.ShiftRight -> RemoteModifier.Shift
    else -> null
}

/** What the local machine says is held down right now — every input event carries it. */
data class RemoteModifiers(val ctrl: Boolean, val alt: Boolean, val shift: Boolean) {
    fun holds(modifier: RemoteModifier): Boolean = when (modifier) {
        RemoteModifier.Ctrl -> ctrl
        RemoteModifier.Alt -> alt
        RemoteModifier.Shift -> shift
    }
}
