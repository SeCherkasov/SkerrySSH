package app.skerry.ui.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import app.skerry.ui.app.UiTags
import kotlin.test.Test

/**
 * Keyboard shortcuts, pressed against the running shell.
 *
 * `matchDesktopShortcut` is a pure function with its own test — which chord means what. This is the
 * half above it: that the chord reaches the handler and the handler does the thing. A shortcut that
 * matches correctly and is wired to nothing looks exactly like a working one from the unit test's
 * side, and like a dead key from the user's.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutWiringTest {

    @Test
    fun `the new-connection chord opens the form`() = runDesktopShell {
        onNodeWithTag(UiTags.FORM_SAVE).assertDoesNotExist()
        chord(Key.N)
        onNodeWithTag(UiTags.FORM_SAVE).assertIsDisplayed()
    }

    /** Ctrl+Shift+K raises the command palette over the active session. */
    @Test
    fun `the palette chord opens the command palette`() = runDesktopShell { shell ->
        chord(Key.K)
        kotlin.test.assertTrue(shell.state.commandPaletteOpen, "the chord did not reach the palette")
    }

    @Test
    fun `the broadcast chord opens the broadcast panel`() = runDesktopShell { shell ->
        chord(Key.B)
        kotlin.test.assertTrue(shell.state.broadcastOpen, "the chord did not reach the broadcast panel")
    }

    /** Ctrl+Shift+<letter> — what `matchDesktopShortcut` accepts on every platform, macOS included. */
    private fun androidx.compose.ui.test.ComposeUiTest.chord(key: Key) {
        onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(key) } }
        }
        waitForIdle()
    }
}
