package app.skerry.ui.terminal

/** Arrow key on the terminal keyboard bar; [finalByte] is the final character of its escape code. */
enum class ArrowKey(val finalByte: Char) { Up('A'), Down('B'), Right('C'), Left('D') }

/** ESC (0x1B), given as a code point so it isn't an invisible control byte in the source (Read/grep). */
private val ESC: String = 27.toChar().toString()

/**
 * Arrow key escape sequence for the PTY, honoring DECCKM (application-cursor-keys,
 * [app.skerry.ui.terminal.TerminalScreenState.applicationCursorKeys]): CSI (`ESC[A`) normally, or SS3
 * (`ESC O A`) once a fullscreen program (vim/less) enables application mode via `ESC[?1h`.
 */
fun arrowSequence(key: ArrowKey, applicationCursor: Boolean): String =
    (if (applicationCursor) ESC + "O" else ESC + "[") + key.finalByte
