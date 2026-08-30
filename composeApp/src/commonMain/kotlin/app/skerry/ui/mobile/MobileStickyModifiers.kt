package app.skerry.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * The terminal panel's sticky ctrl and alt, held for the whole screen rather than for the panel:
 * a modifier armed on the panel has to reach soft-keyboard input too, and the IME path
 * ([app.skerry.ui.design.ImeFunnel]) bypasses the panel entirely.
 *
 * "Sticky" is one keystroke: [applyToImeInput] spends the modifier on the input it applied to.
 */
@Stable
class StickyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** Both down: a key panel tap sends its sequence and leaves nothing armed behind it. */
    fun disarm() {
        ctrl = false
        alt = false
    }

    /**
     * Encodes soft-keyboard input with whatever is armed, and spends it.
     *
     * Ctrl is spent only on input that [takesStickyCtrl] — a Backspace or Enter passes through and
     * leaves it armed for the letter it was armed for. Alt is spent on anything non-empty, because
     * Alt+Backspace (delete word) is one of the combinations it is armed for.
     */
    fun applyToImeInput(raw: String): String {
        val out = applyStickyMeta(alt, applyStickyCtrl(ctrl, raw))
        if (ctrl && takesStickyCtrl(raw)) ctrl = false
        if (alt && raw.isNotEmpty()) alt = false
        return out
    }
}

/** Modifiers for one session: a new session starts with nothing armed. */
@Composable
fun rememberStickyModifiers(sessionKey: Any?): StickyModifiers = remember(sessionKey) { StickyModifiers() }
