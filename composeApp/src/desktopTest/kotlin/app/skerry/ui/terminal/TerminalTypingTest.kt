package app.skerry.ui.terminal

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.ui.desktop.FakeShellInput
import app.skerry.ui.desktop.runDesktopShell
import kotlin.test.Test

/**
 * Typing into the session that is on screen.
 *
 * The emulator and the key mapping are covered on their own; the path between a key press and the
 * channel is not, and it is a path with several places to lose a keystroke — focus, the pane in
 * focus, the guard, the writer. A terminal that draws but sends nothing is the one failure a
 * render test cannot see.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalTypingTest {

    /**
     * Asserted on the exact write, not on a substring: the session's banner is full of the letter
     * `l`, so `contains` would go green on the greeting alone with the keystroke lost.
     */
    @Test
    fun `a typed character reaches the session`() = runDesktopShell {
        FakeShellInput.clear()
        onRoot().performKeyInput { pressKey(Key.L) }
        waitUntil { FakeShellInput.all().contains("l") }
    }

    /** Ctrl+C is not a character: it goes down the wire as the interrupt byte. */
    @Test
    fun `ctrl-C sends the interrupt byte`() = runDesktopShell {
        FakeShellInput.clear()
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        waitUntil { FakeShellInput.all().contains(INTERRUPT) }
    }
}

/** ETX — what Ctrl+C is on the wire. Written as an escape: the byte itself is invisible in a diff. */
private const val INTERRUPT = "\u0003"
