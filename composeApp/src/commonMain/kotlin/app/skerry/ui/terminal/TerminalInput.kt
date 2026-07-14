package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key

/**
 * AWT sends keyChar = CHAR_UNDEFINED (0xFFFF) for "keypresses without a char": lone modifiers and
 * Alt+letter on Linux. Compose puts this value straight into `utf16CodePoint`, so this codePoint is
 * garbage and must NOT reach the PTY as a printable character.
 */
private const val CHAR_UNDEFINED = 0xffff

/** ESC (0x1b) and DEL (0x7f) — the only place with a \u-escape; sequences are built from these below. */
private const val ESC = ""
private const val DEL = ""

// On the IME path the hidden field already feeds printable chars; some keyboards also emit a
// hardware key event for the number row, so the key path must drop it or "3" is sent twice.
fun isImeOwnedPrintable(imeInput: Boolean, ctrl: Boolean, alt: Boolean, codePoint: Int): Boolean =
    imeInput && !ctrl && !alt && codePoint in 0x20 until CHAR_UNDEFINED && codePoint != 0x7f

/**
 * Maps a key press to PTY bytes — raw mode of the interactive terminal: characters go to the shell
 * one at a time, echo is drawn by the shell itself. Returns the string to send to the session, or
 * `null` if the key is ignored (lone modifier, unsupported combo).
 *
 * Special keys are encoded xterm-compatibly: arrow keys and Home/End honor DECCKM
 * ([applicationCursor]) — sent as SS3 (`ESC O x`) in application mode, else CSI (`ESC[x`); function
 * keys/Page/Insert/Delete are fixed CSI/SS3, unaffected by DECCKM. With Shift/Ctrl/Alt held, special
 * keys switch to modifyOtherKeys-CSI form (`ESC[1;<mod>x` for arrows/Home/End/F1-F4, `ESC[<n>;<mod>~`
 * for tilde keys), where `mod = 1 + Shift + Alt·2 + Ctrl·4` — enabling Shift+arrow for selection in
 * mc, Ctrl+arrow for word jumps, etc. [shift]+Tab produces back-tab (`ESC[Z`). [alt] = Meta: for
 * printable characters and C0 bytes (Ctrl/Backspace/Enter), prepends ESC (readline word-ops,
 * Alt+Backspace = delete word).
 *
 * Parameters are primitives (not `KeyEvent`) to keep the function pure and testable.
 */
fun mapTerminalKey(
    key: Key,
    ctrl: Boolean,
    codePoint: Int,
    alt: Boolean = false,
    shift: Boolean = false,
    applicationCursor: Boolean = false,
    applicationKeypad: Boolean = false,
): String? {
    // Application keypad (DECKPAM): numpad sends SS3 (ESC O p..y / M/k/m/j/o/n) instead of digits.
    // Only without ctrl — Ctrl+numpad falls through to the general path. Checked before the `when`
    // below (otherwise NumPadEnter would produce CR).
    if (applicationKeypad && !ctrl) keypadSequence(key)?.let { return it }
    // Navigation and function keys come FIRST: with Ctrl/Alt/Shift they encode the modifier inside
    // CSI (ESC[1;<mod>x), so Ctrl+arrow must not fall into the ctrl block below (which would return null).
    navKeySequence(key, applicationCursor, shift, alt, ctrl)?.let { return it }
    if (ctrl) {
        // Ctrl+key → C0 byte. Determined from the PHYSICAL key, not codePoint: on desktop AWT sends
        // Ctrl+C directly as the finished control byte (keyChar 0x03), but layout-dependent/lone
        // combos as CHAR_UNDEFINED, so relying on codePoint broke Ctrl+letter in practice.
        // Alt adds the meta ESC prefix.
        val ctrlByte = controlByte(key, codePoint) ?: return null
        return meta(alt, ctrlByte.toChar().toString())
    }
    // C0-byte editing keys — honor Alt=Meta (Alt+Backspace = delete word).
    when (key) {
        Key.Enter, Key.NumPadEnter -> return meta(alt, "\r")
        Key.Backspace -> return meta(alt, DEL)
        Key.Escape -> return meta(alt, ESC)
        // Shift+Tab — back-tab (multi-byte CSI, no meta); otherwise plain HT, honor Alt=Meta.
        Key.Tab -> return if (shift) "$ESC[Z" else meta(alt, "\t")
    }
    val ch = printableChar(key, codePoint, shift) ?: return null
    return meta(alt, ch.toString())
}

/**
 * SS3 sequence for a numpad key in application-keypad mode (DECKPAM), or `null` if [key] isn't from
 * the numpad. xterm encoding: digits 0..9 → `ESC O p`..`ESC O y`, `.`→`ESC O n`, Enter→`ESC O M`,
 * `+`→`ESC O k`, `-`→`ESC O m`, `*`→`ESC O j`, `/`→`ESC O o`, `=`→`ESC O X`, `,`→`ESC O l`.
 */
private fun keypadSequence(key: Key): String? = when (key) {
    Key.NumPad0 -> "${ESC}Op"
    Key.NumPad1 -> "${ESC}Oq"
    Key.NumPad2 -> "${ESC}Or"
    Key.NumPad3 -> "${ESC}Os"
    Key.NumPad4 -> "${ESC}Ot"
    Key.NumPad5 -> "${ESC}Ou"
    Key.NumPad6 -> "${ESC}Ov"
    Key.NumPad7 -> "${ESC}Ow"
    Key.NumPad8 -> "${ESC}Ox"
    Key.NumPad9 -> "${ESC}Oy"
    Key.NumPadDot -> "${ESC}On"
    Key.NumPadEnter -> "${ESC}OM"
    Key.NumPadAdd -> "${ESC}Ok"
    Key.NumPadSubtract -> "${ESC}Om"
    Key.NumPadMultiply -> "${ESC}Oj"
    Key.NumPadDivide -> "${ESC}Oo"
    Key.NumPadEquals -> "${ESC}OX"
    Key.NumPadComma -> "${ESC}Ol"
    else -> null
}

/**
 * Focus-reporting sequence (DEC 1004): window focus → `ESC[I`, focus lost → `ESC[O`.
 * The UI sends this to the PTY on focus change, only when the app enabled the mode (vim/tmux).
 */
fun focusReportSequence(focused: Boolean): String = if (focused) "$ESC[I" else "$ESC[O"

/** Meta wrapper: prepends ESC when Alt is held (xterm metaSendsEscape). */
private fun meta(alt: Boolean, seq: String): String = if (alt) ESC + seq else seq

/**
 * Control C0 byte for Ctrl+key, or `null` if the combo isn't a control sequence. Determined from the
 * physical key first (reliable regardless of AWT's keyChar: Ctrl+C arrives as 0x03, sometimes as
 * CHAR_UNDEFINED), then falls back to codePoint — if AWT already gave a finished C0 byte (1..26) or,
 * for unit-test compatibility, a letter.
 */
private fun controlByte(key: Key, codePoint: Int): Int? {
    letterIndex(key)?.let { return it + 1 } // Ctrl+A..Z → 0x01..0x1A
    return when (key) {
        Key.LeftBracket -> 0x1b   // Ctrl+[ = ESC
        Key.Backslash -> 0x1c     // Ctrl+\ = FS
        Key.RightBracket -> 0x1d  // Ctrl+] = GS
        Key.Spacebar -> 0x00      // Ctrl+Space = NUL
        else -> when (codePoint) {
            in 1..26 -> codePoint
            in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
            in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
            else -> null
        }
    }
}

/**
 * Printable character of the keypress, or `null`. [codePoint] is used only if it's a real character
 * (not 0, not [CHAR_UNDEFINED], not ISO-control). When AWT didn't provide one — typically Alt+letter
 * on Linux and lone modifiers (keyChar == CHAR_UNDEFINED) — falls back to the physical key's letter,
 * so Alt=Meta works while a lone Alt does NOT send a garbage glyph.
 */
private fun printableChar(key: Key, codePoint: Int, shift: Boolean): Char? {
    if (codePoint != 0 && codePoint != CHAR_UNDEFINED) {
        val ch = codePoint.toChar()
        if (!ch.isISOControl()) return ch
    }
    return letterIndex(key)?.let { idx ->
        val c = 'a' + idx
        if (shift) c.uppercaseChar() else c
    }
}

/** Index of a letter key A..Z → 0..25, or `null` for a non-letter key. */
private fun letterIndex(key: Key): Int? = when (key) {
    Key.A -> 0; Key.B -> 1; Key.C -> 2; Key.D -> 3; Key.E -> 4; Key.F -> 5; Key.G -> 6
    Key.H -> 7; Key.I -> 8; Key.J -> 9; Key.K -> 10; Key.L -> 11; Key.M -> 12; Key.N -> 13
    Key.O -> 14; Key.P -> 15; Key.Q -> 16; Key.R -> 17; Key.S -> 18; Key.T -> 19; Key.U -> 20
    Key.V -> 21; Key.W -> 22; Key.X -> 23; Key.Y -> 24; Key.Z -> 25
    else -> null
}

/**
 * xterm sequence for a navigation/function key, or `null` if [key] isn't from this set.
 *
 * Without modifiers: arrow keys and Home/End honor DECCKM ([applicationCursor]) — SS3 lead-in
 * (`ESC O`) in application mode, else CSI (`ESC[`); Page/Insert/Delete as CSI `ESC[<n>~`, F1-F4 as SS3
 * `ESC O P..S`, F5-F12 as CSI `ESC[<n>~`.
 *
 * With [shift]/[alt]/[ctrl] held, the key is encoded in modifyOtherKeys form: "letter" keys (arrows,
 * Home/End, F1-F4) → `ESC[1;<mod><letter>` (always CSI, SS3/DECCKM ignored — SS3 carries no
 * parameter), "tilde" keys (Page/Insert/Delete, F5-F12) → `ESC[<n>;<mod>~`. `mod = 1 + Shift + Alt·2 + Ctrl·4`.
 */
private fun navKeySequence(key: Key, applicationCursor: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean): String? {
    val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    // "Letter" keys: final byte + (for arrows/Home/End) DECCKM honored when unmodified.
    val letter: Char? = when (key) {
        Key.DirectionUp -> 'A'
        Key.DirectionDown -> 'B'
        Key.DirectionRight -> 'C'
        Key.DirectionLeft -> 'D'
        Key.MoveHome -> 'H'
        Key.MoveEnd -> 'F'
        Key.F1 -> 'P'
        Key.F2 -> 'Q'
        Key.F3 -> 'R'
        Key.F4 -> 'S'
        else -> null
    }
    if (letter != null) {
        if (mod != 1) return "$ESC[1;$mod$letter"
        // F1-F4 without a modifier — SS3 regardless of DECCKM; arrows/Home/End — SS3 only in application mode.
        val ss3 = key == Key.F1 || key == Key.F2 || key == Key.F3 || key == Key.F4 || applicationCursor
        return if (ss3) "${ESC}O$letter" else "$ESC[$letter"
    }
    // "Tilde" keys: CSI <n> [; <mod>] ~.
    val num: Int = when (key) {
        Key.Insert -> 2
        Key.Delete -> 3
        Key.PageUp -> 5
        Key.PageDown -> 6
        Key.F5 -> 15
        Key.F6 -> 17
        Key.F7 -> 18
        Key.F8 -> 19
        Key.F9 -> 20
        Key.F10 -> 21
        Key.F11 -> 23
        Key.F12 -> 24
        else -> return null
    }
    return if (mod != 1) "$ESC[$num;$mod~" else "$ESC[$num~"
}
