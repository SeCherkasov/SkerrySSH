package app.skerry.ui.vnc

import androidx.compose.ui.input.key.Key

/**
 * Maps a Compose [Key] (plus the event's printable code point) to an X11 keysym for RFB KeyEvent.
 * Pure and platform-neutral (like `mapTerminalKey`), so it's unit-tested without a UI.
 *
 * Rule: named non-printable keys map to their X11 keysym; anything else falls back to the event's
 * UTF-16 code point, which for Latin-1/ASCII IS the keysym (RFB keysyms below 0x100 are the Latin-1
 * code, and Unicode above that is 0x01000000 + code point). Returns 0 when there's nothing to send.
 */
fun keySymFor(key: Key, codePoint: Int): Long {
    special[key]?.let { return it }
    if (codePoint != 0) return unicodeKeySym(codePoint)
    return 0L
}

/** X11 keysym for a Unicode code point: Latin-1 (< 0x100) is the code itself; above that, 0x01000000 + code. */
private fun unicodeKeySym(codePoint: Int): Long =
    if (codePoint in 0x20..0xFF) codePoint.toLong() else 0x01000000L + codePoint.toLong()

// Named non-printable keys → X11 keysyms (keysymdef.h); left/right modifiers keep their own keysyms.
private val special: Map<Key, Long> = buildMap {
    put(Key.Enter, 0xFF0DL)
    put(Key.NumPadEnter, 0xFF0DL)
    put(Key.Backspace, 0xFF08L)
    put(Key.Tab, 0xFF09L)
    put(Key.Escape, 0xFF1BL)
    put(Key.Delete, 0xFFFFL)
    put(Key.Insert, 0xFF63L)
    put(Key.Spacebar, 0x020L)
    put(Key.Home, 0xFF50L)
    put(Key.MoveEnd, 0xFF57L)
    put(Key.PageUp, 0xFF55L)
    put(Key.PageDown, 0xFF56L)
    put(Key.DirectionLeft, 0xFF51L)
    put(Key.DirectionUp, 0xFF52L)
    put(Key.DirectionRight, 0xFF53L)
    put(Key.DirectionDown, 0xFF54L)
    put(Key.ShiftLeft, 0xFFE1L)
    put(Key.ShiftRight, 0xFFE2L)
    put(Key.CtrlLeft, 0xFFE3L)
    put(Key.CtrlRight, 0xFFE4L)
    put(Key.AltLeft, 0xFFE9L)
    put(Key.AltRight, 0xFFEAL)
    put(Key.MetaLeft, 0xFFEBL) // Super_L
    put(Key.MetaRight, 0xFFECL)
    put(Key.CapsLock, 0xFFE5L)
    put(Key.F1, 0xFFBEL)
    put(Key.F2, 0xFFBFL)
    put(Key.F3, 0xFFC0L)
    put(Key.F4, 0xFFC1L)
    put(Key.F5, 0xFFC2L)
    put(Key.F6, 0xFFC3L)
    put(Key.F7, 0xFFC4L)
    put(Key.F8, 0xFFC5L)
    put(Key.F9, 0xFFC6L)
    put(Key.F10, 0xFFC7L)
    put(Key.F11, 0xFFC8L)
    put(Key.F12, 0xFFC9L)
}

/** RFB PointerEvent button-mask bits. */
object VncButton {
    const val LEFT = 1 shl 0
    const val MIDDLE = 1 shl 1
    const val RIGHT = 1 shl 2
    const val WHEEL_UP = 1 shl 3
    const val WHEEL_DOWN = 1 shl 4
    const val WHEEL_LEFT = 1 shl 5
    const val WHEEL_RIGHT = 1 shl 6
}
