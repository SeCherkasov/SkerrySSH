package app.skerry.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.ssh.PtySize
import app.skerry.shared.terminal.TerminalSession
import app.skerry.shared.terminal.TerminalState
import app.skerry.ui.app.LocalUserActivity
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The soft keyboard's own funnel into the PTY reports typing to the vault's idle auto-lock.
 *
 * On Android nothing about this path produces a key event the gate's modifier could see: the
 * keyboard is its own window and the characters arrive as text into an invisible field. Without
 * this report, typing in a session for the idle window locks the vault on top of it — issue #291's
 * bug, in the one place the fix cannot rely on key events.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalImeActivityTest {

    @Test
    fun `text from a soft keyboard is reported as user activity`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = TerminalScreenState(SilentSession(), scope)
        var reported = false

        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalUserActivity provides { reported = true },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        TerminalScreen(state, Modifier.fillMaxSize(), imeInput = true)
                    }
                }
            }
        }

        onNode(hasSetTextAction()).performTextInput("l")

        assertTrue(reported, "typing through the soft keyboard never reached the idle timer")
        scope.cancel()
    }
}

/** A session that never speaks: the terminal only has to render for the funnel to exist. */
private class SilentSession : TerminalSession {
    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = MutableSharedFlow()
    override suspend fun send(data: ByteArray) = Unit
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = Unit
}
