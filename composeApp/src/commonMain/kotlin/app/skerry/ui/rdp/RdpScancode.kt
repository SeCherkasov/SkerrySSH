package app.skerry.ui.rdp

import androidx.compose.ui.input.key.Key

/**
 * A key as RDP carries it: a PC/AT set 1 scancode, plus the E0 prefix flag that tells the two
 * halves of a keyboard apart (right Ctrl from left Ctrl, the arrow block from the numeric keypad).
 */
data class RdpKeyCode(val scancode: Int, val extended: Boolean)

/**
 * Maps a Compose [Key] to its PC/AT set 1 scancode.
 *
 * RDP replays scancodes into the remote keyboard driver rather than sending characters, so the
 * *remote* layout decides what a key produces. That is what makes a Cyrillic or German layout on
 * the server work while the local keyboard is something else — and also why anything the local
 * layout composes but no key on the remote one carries has to go out as Unicode instead (see
 * `RdpSession.sendUnicode`).
 *
 * Pure and platform-neutral, like `keySymFor` on the VNC side, so it is unit-tested without a UI.
 * Returns null for keys with no scancode of their own.
 */
fun scancodeFor(key: Key): RdpKeyCode? = scancodes[key]

private fun plain(code: Int) = RdpKeyCode(code, extended = false)

private fun extended(code: Int) = RdpKeyCode(code, extended = true)

private val scancodes: Map<Key, RdpKeyCode> = buildMap {
    // Row 1: escape, digits, backspace.
    put(Key.Escape, plain(0x01))
    put(Key.One, plain(0x02))
    put(Key.Two, plain(0x03))
    put(Key.Three, plain(0x04))
    put(Key.Four, plain(0x05))
    put(Key.Five, plain(0x06))
    put(Key.Six, plain(0x07))
    put(Key.Seven, plain(0x08))
    put(Key.Eight, plain(0x09))
    put(Key.Nine, plain(0x0A))
    put(Key.Zero, plain(0x0B))
    put(Key.Minus, plain(0x0C))
    put(Key.Equals, plain(0x0D))
    put(Key.Backspace, plain(0x0E))

    // Row 2.
    put(Key.Tab, plain(0x0F))
    put(Key.Q, plain(0x10))
    put(Key.W, plain(0x11))
    put(Key.E, plain(0x12))
    put(Key.R, plain(0x13))
    put(Key.T, plain(0x14))
    put(Key.Y, plain(0x15))
    put(Key.U, plain(0x16))
    put(Key.I, plain(0x17))
    put(Key.O, plain(0x18))
    put(Key.P, plain(0x19))
    put(Key.LeftBracket, plain(0x1A))
    put(Key.RightBracket, plain(0x1B))
    put(Key.Enter, plain(0x1C))

    // Row 3.
    put(Key.CtrlLeft, plain(0x1D))
    put(Key.A, plain(0x1E))
    put(Key.S, plain(0x1F))
    put(Key.D, plain(0x20))
    put(Key.F, plain(0x21))
    put(Key.G, plain(0x22))
    put(Key.H, plain(0x23))
    put(Key.J, plain(0x24))
    put(Key.K, plain(0x25))
    put(Key.L, plain(0x26))
    put(Key.Semicolon, plain(0x27))
    put(Key.Apostrophe, plain(0x28))
    put(Key.Grave, plain(0x29))
    put(Key.ShiftLeft, plain(0x2A))
    put(Key.Backslash, plain(0x2B))

    // Row 4.
    put(Key.Z, plain(0x2C))
    put(Key.X, plain(0x2D))
    put(Key.C, plain(0x2E))
    put(Key.V, plain(0x2F))
    put(Key.B, plain(0x30))
    put(Key.N, plain(0x31))
    put(Key.M, plain(0x32))
    put(Key.Comma, plain(0x33))
    put(Key.Period, plain(0x34))
    put(Key.Slash, plain(0x35))
    put(Key.ShiftRight, plain(0x36))
    put(Key.NumPadMultiply, plain(0x37))
    put(Key.AltLeft, plain(0x38))
    put(Key.Spacebar, plain(0x39))
    put(Key.CapsLock, plain(0x3A))

    // Function keys.
    put(Key.F1, plain(0x3B))
    put(Key.F2, plain(0x3C))
    put(Key.F3, plain(0x3D))
    put(Key.F4, plain(0x3E))
    put(Key.F5, plain(0x3F))
    put(Key.F6, plain(0x40))
    put(Key.F7, plain(0x41))
    put(Key.F8, plain(0x42))
    put(Key.F9, plain(0x43))
    put(Key.F10, plain(0x44))
    put(Key.F11, plain(0x57))
    put(Key.F12, plain(0x58))

    // Numeric keypad. These share their scancodes with the navigation block, which is exactly what
    // the extended flag below distinguishes.
    put(Key.NumLock, plain(0x45))
    put(Key.ScrollLock, plain(0x46))
    put(Key.NumPad7, plain(0x47))
    put(Key.NumPad8, plain(0x48))
    put(Key.NumPad9, plain(0x49))
    put(Key.NumPadSubtract, plain(0x4A))
    put(Key.NumPad4, plain(0x4B))
    put(Key.NumPad5, plain(0x4C))
    put(Key.NumPad6, plain(0x4D))
    put(Key.NumPadAdd, plain(0x4E))
    put(Key.NumPad1, plain(0x4F))
    put(Key.NumPad2, plain(0x50))
    put(Key.NumPad3, plain(0x51))
    put(Key.NumPad0, plain(0x52))
    put(Key.NumPadDot, plain(0x53))

    // Extended (E0-prefixed) keys: the right-hand modifiers, the navigation block and the keypad's
    // enter and divide.
    put(Key.NumPadEnter, extended(0x1C))
    put(Key.CtrlRight, extended(0x1D))
    put(Key.NumPadDivide, extended(0x35))
    put(Key.AltRight, extended(0x38))
    put(Key.Home, extended(0x47))
    put(Key.DirectionUp, extended(0x48))
    put(Key.PageUp, extended(0x49))
    put(Key.DirectionLeft, extended(0x4B))
    put(Key.DirectionRight, extended(0x4D))
    put(Key.MoveEnd, extended(0x4F))
    put(Key.DirectionDown, extended(0x50))
    put(Key.PageDown, extended(0x51))
    put(Key.Insert, extended(0x52))
    put(Key.Delete, extended(0x53))
    put(Key.MetaLeft, extended(0x5B))
    put(Key.MetaRight, extended(0x5C))
    put(Key.Menu, extended(0x5D))
    put(Key.PrintScreen, extended(0x37))
    put(Key.Break, extended(0x46))
}
