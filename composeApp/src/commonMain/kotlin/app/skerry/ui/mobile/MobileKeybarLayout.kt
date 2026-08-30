package app.skerry.ui.mobile

import androidx.compose.ui.input.key.Key
import app.skerry.ui.terminal.mapTerminalKey

/**
 * Keys of the mobile terminal panel, its two-row layout and what each key sends. Pure: separated
 * from [MobileKeybar] so the layout and the escape sequences are testable without Compose.
 *
 * Encoding of the navigation and function keys is delegated to [mapTerminalKey] — the same function
 * the physical keyboard goes through — so a panel key and a hardware key produce identical bytes,
 * modifiers included. The four symbol keys are the exception: they are typed characters, and armed
 * ctrl masks them here ([applyStickyCtrl]), which is how the panel's Ctrl+`-` submits the line. The
 * physical path drops those combinations instead — `TerminalInput.controlByte` has no branch for
 * punctuation — so this is a deliberate divergence, not a shared encoder.
 */

/**
 * A key the panel can draw. Each carries what it does: the Compose key it stands for (encoded through
 * [mapTerminalKey]), the character it types, or the panel action it runs — exactly one of the three.
 */
enum class KeybarKey(
    internal val pressed: Key? = null,
    internal val symbol: Char? = null,
    internal val command: KeybarCommand? = null,
) {
    Esc(pressed = Key.Escape),
    Tab(pressed = Key.Tab),
    Ctrl(command = KeybarCommand.ArmCtrl),
    Alt(command = KeybarCommand.ArmAlt),
    Fn(command = KeybarCommand.ToggleFn),
    HideKeyboard(command = KeybarCommand.HideKeyboard),
    Slash(symbol = '/'),
    Pipe(symbol = '|'),
    Dash(symbol = '-'),
    Tilde(symbol = '~'),
    Up(pressed = Key.DirectionUp),
    Down(pressed = Key.DirectionDown),
    Left(pressed = Key.DirectionLeft),
    Right(pressed = Key.DirectionRight),
    Home(pressed = Key.MoveHome),
    End(pressed = Key.MoveEnd),
    PageUp(pressed = Key.PageUp),
    PageDown(pressed = Key.PageDown),
    F1(pressed = Key.F1),
    F2(pressed = Key.F2),
    F3(pressed = Key.F3),
    F4(pressed = Key.F4),
    F5(pressed = Key.F5),
    F6(pressed = Key.F6),
    F7(pressed = Key.F7),
    F8(pressed = Key.F8),
    F9(pressed = Key.F9),
    F10(pressed = Key.F10),
    F11(pressed = Key.F11),
    F12(pressed = Key.F12),
    Ai(command = KeybarCommand.ToggleAi),
    SearchHistory(command = KeybarCommand.SearchHistory),
    FindOutput(command = KeybarCommand.FindOutput),
    CycleSuggestion(command = KeybarCommand.CycleSuggestion),
}

/** A panel action the view performs itself — nothing reaches the PTY. */
enum class KeybarCommand { ArmCtrl, ArmAlt, ToggleFn, HideKeyboard, ToggleAi, SearchHistory, FindOutput, CycleSuggestion }

/** What tapping a key does. */
sealed interface KeybarEffect {
    /** Printable text — goes through `typeInput` so the production guard sees the line. */
    data class Type(val text: String) : KeybarEffect

    /** Control sequence — `sendUserInput`. */
    data class Send(val sequence: String) : KeybarEffect

    /** Handled by the view. */
    data class Run(val command: KeybarCommand) : KeybarEffect
}

/**
 * Rows of the panel: the base layer, or the function layer when [fnLayer].
 *
 * Two rows of equal width, drawn as a grid without horizontal scrolling — every key is reachable
 * without a swipe that the terminal underneath would rather have. The arrows sit as a cross (Up over
 * Down, Left and Right beside it), `fn` holds the top-right slot in both layers and the key that
 * drops the soft keyboard the bottom-right one, so neither moves under the finger that just used it.
 */
fun keybarRows(fnLayer: Boolean): List<List<KeybarKey>> = if (fnLayer) FN_ROWS else BASE_ROWS

private val BASE_ROWS = listOf(
    listOf(
        KeybarKey.Esc, KeybarKey.Slash, KeybarKey.Pipe, KeybarKey.Dash,
        KeybarKey.Up, KeybarKey.Home, KeybarKey.End, KeybarKey.PageUp, KeybarKey.Fn,
    ),
    listOf(
        KeybarKey.Tab, KeybarKey.Ctrl, KeybarKey.Alt, KeybarKey.Left,
        KeybarKey.Down, KeybarKey.Right, KeybarKey.PageDown, KeybarKey.Tilde, KeybarKey.HideKeyboard,
    ),
)

// The panel's own tools (assistant, history search, find in output, suggestion cycling) live here
// rather than on the base layer: the base layer's nineteen candidates do not fit eighteen slots, and
// these four are the ones a session reaches for least often.
private val FN_ROWS = listOf(
    listOf(
        KeybarKey.F1, KeybarKey.F2, KeybarKey.F3, KeybarKey.F4,
        KeybarKey.F5, KeybarKey.F6, KeybarKey.F7, KeybarKey.F8, KeybarKey.Fn,
    ),
    listOf(
        KeybarKey.F9, KeybarKey.F10, KeybarKey.F11, KeybarKey.F12,
        KeybarKey.Ai, KeybarKey.SearchHistory, KeybarKey.FindOutput, KeybarKey.CycleSuggestion,
        KeybarKey.HideKeyboard,
    ),
)

/**
 * What [key] does, given the armed sticky modifiers and the session's DECCKM mode
 * ([app.skerry.ui.terminal.TerminalScreenState.applicationCursorKeys]).
 *
 * Navigation and function keys carry the armed modifiers inside the sequence (`ESC[1;5C` = ctrl+→,
 * a word jump). A combination with no xterm encoding (Ctrl+Esc, Ctrl+Tab) falls back to the same key
 * without ctrl: a tap that produced nothing at all would be worse than one that dropped a modifier.
 */
fun keybarEffect(
    key: KeybarKey,
    ctrlArmed: Boolean = false,
    altArmed: Boolean = false,
    applicationCursor: Boolean = false,
): KeybarEffect {
    key.command?.let { return KeybarEffect.Run(it) }
    key.symbol?.let { c ->
        // Both modifiers survive, meta outermost — the order mapTerminalKey encodes for the physical
        // keyboard and the IME path composes ([StickyModifiers.applyToImeInput]).
        return KeybarEffect.Type(applyStickyMeta(altArmed, applyStickyCtrl(ctrlArmed, c.toString())))
    }
    val pressed = requireNotNull(key.pressed) { "key $key has no effect" }
    // A key that cannot carry ctrl sends itself anyway: Ctrl+Esc and Ctrl+Tab have no xterm form, and
    // mapTerminalKey answers null for them — dropping the tap would be worse than dropping the
    // modifier, which is why the fallback repeats the call without ctrl rather than trusting a list.
    val sequence = mapTerminalKey(pressed, ctrlArmed, codePoint = 0, alt = altArmed, applicationCursor = applicationCursor)
        ?: mapTerminalKey(pressed, ctrl = false, codePoint = 0, alt = altArmed, applicationCursor = applicationCursor)
    return KeybarEffect.Send(requireNotNull(sequence) { "key $key encodes to nothing" })
}
