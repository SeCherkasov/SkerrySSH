package app.skerry.shared.terminal

/** Mouse button/wheel reported to the application. */
enum class MouseButton { Left, Middle, Right, WheelUp, WheelDown }

/** Mouse event type sent to the PTY. */
enum class MouseEventType {
    /** Button press (also a wheel tick). */
    Press,

    /** Button release. */
    Release,

    /** Motion with a button held (reported in ButtonEvent/AnyEvent). */
    Drag,

    /** Motion with no button held (AnyEvent only). */
    Move,
}

private val ESC = 27.toChar()

/** Base button code in the mouse protocol: left=0, middle=1, right=2, wheel up/down=64/65. */
private fun MouseButton.code(): Int = when (this) {
    MouseButton.Left -> 0
    MouseButton.Middle -> 1
    MouseButton.Right -> 2
    MouseButton.WheelUp -> 64
    MouseButton.WheelDown -> 65
}

/**
 * Encodes a mouse event to PTY bytes per the active [tracking] mode and encoding:
 *  - `sgr=true` — extended SGR format (DEC 1006): `ESC [ < Cb ; col ; row M|m`, 1-based decimal
 *    coordinates, no 223 limit; release uses lowercase `m` and preserves the button;
 *  - `sgr=false` — classic X11 format (DEC 1000): `ESC [ M Cb Cx Cy`, each byte = value+32,
 *    coordinates `col+1`/`row+1`, release uses the indistinguishable code 3; bytes clamped to 0..255.
 *  - `pixels=true` — SGR-Pixels (DEC 1016): same `ESC [ <` format as SGR but pixel coordinates
 *    [pixelX]/[pixelY] instead of cells. Implies SGR encoding regardless of [sgr].
 *
 * Cb bits: low 2 = button (or 3 = none/release), 4 = Shift, 8 = Meta(Alt), 16 = Ctrl, 32 = motion
 * (Drag/Move), 64 = wheel. Returns `null` if the event isn't reported in this mode (e.g. motion in
 * Normal or release in X10).
 *
 * [col]/[row] are 0-based cell indices; the protocol makes them 1-based.
 */
fun encodeMouseReport(
    tracking: MouseTracking,
    sgr: Boolean,
    button: MouseButton,
    type: MouseEventType,
    col: Int,
    row: Int,
    shift: Boolean = false,
    alt: Boolean = false,
    ctrl: Boolean = false,
    pixels: Boolean = false,
    pixelX: Int = 0,
    pixelY: Int = 0,
): ByteArray? {
    val wheel = button == MouseButton.WheelUp || button == MouseButton.WheelDown
    val motion = type == MouseEventType.Drag || type == MouseEventType.Move

    // Mode gating: which events are reported at all.
    when (tracking) {
        MouseTracking.Off -> return null
        MouseTracking.X10 -> if (type != MouseEventType.Press || wheel) return null // button presses only
        MouseTracking.Normal -> if (motion) return null // no motion
        MouseTracking.ButtonEvent -> if (type == MouseEventType.Move) return null // motion only with a button
        MouseTracking.AnyEvent -> {} // everything
    }

    val isRelease = type == MouseEventType.Release
    // Base button code. Legacy encodes release as the indistinguishable 3; SGR preserves the button.
    // Motion with no button (Move) is also low bits 3 (no button held).
    var cb = when {
        wheel -> button.code()
        type == MouseEventType.Move -> 3
        // Only legacy (X11) encodes release as the indistinguishable 3; SGR and SGR-Pixels preserve
        // the button (release is distinguished by lowercase `m`).
        isRelease && !sgr && !pixels -> 3
        else -> button.code()
    }
    if (motion) cb += 32
    // Modifiers (X10 doesn't encode them).
    if (tracking != MouseTracking.X10) {
        if (shift) cb += 4
        if (alt) cb += 8
        if (ctrl) cb += 16
    }

    return when {
        pixels -> {
            val final = if (isRelease) 'm' else 'M'
            "$ESC[<$cb;${pixelX + 1};${pixelY + 1}$final".encodeToByteArray()
        }
        sgr -> {
            val final = if (isRelease) 'm' else 'M'
            "$ESC[<$cb;${col + 1};${row + 1}$final".encodeToByteArray()
        }
        else -> {
            fun enc(v: Int): Byte = (v + 32).coerceIn(0, 255).toByte()
            byteArrayOf(0x1b, '['.code.toByte(), 'M'.code.toByte(), enc(cb), enc(col + 1), enc(row + 1))
        }
    }
}

/**
 * Wraps pasted text in bracketed-paste markers (`ESC[200~` … `ESC[201~`) when the mode is enabled by
 * the application (DEC 2004), so a shell/editor distinguishes paste from input and doesn't execute
 * newlines as commands. When disabled, text is returned as-is.
 *
 * The closing marker `ESC[201~` is stripped from the content: otherwise pasting text into which an
 * untrusted SSH server injected this marker (via output that landed in the selection) would end the
 * paste early and send the tail to the application as typed commands (paste injection).
 *
 * When the mode is disabled the application can't tell paste from input, so control bytes in the
 * buffer (e.g. `CR` = Enter) would execute as commands. Raw pastes therefore drop C0 controls
 * (`0x00`–`0x1F`) and `DEL` (`0x7F`) except `Tab` and `LF` (legitimate separators). In bracketed
 * mode control bytes are kept literal: the application knows it's a paste and won't execute them.
 */
fun bracketedPasteWrap(text: String, bracketed: Boolean): String {
    if (!bracketed) return text.filterNot { it.code < 0x20 && it != '\t' && it != '\n' || it.code == 0x7f }
    val sanitized = text.replace("$ESC[201~", "")
    return "$ESC[200~$sanitized$ESC[201~"
}
