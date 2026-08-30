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
import app.skerry.ui.mobile.applyStickyCtrl
import app.skerry.ui.mobile.controlByte
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The soft keyboard's own funnel into the PTY reports typing to the vault's idle auto-lock.
 *
 * On Android nothing about this path produces a key event the gate's modifier could see: the
 * keyboard is its own window and the characters arrive as text into an invisible field. Without
 * this report, typing in a session for the idle window locks the vault on top of it — issue #291's
 * bug, in the one place the fix cannot rely on key events. The same test carries the byte through:
 * a character typed on the soft keyboard has to arrive at the PTY exactly once, and the funnel's own
 * anchors must not arrive at all.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalImeActivityTest {

    @Test
    fun `text from a soft keyboard is reported as user activity`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = SilentSession()
        val state = TerminalScreenState(session, scope)
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
        waitForIdle()

        assertTrue(reported, "typing through the soft keyboard never reached the idle timer")
        assertEquals("l", session.sent(), "the character never reached the PTY")
        scope.cancel()
    }

    /**
     * The key panel's armed Ctrl reaches the soft keyboard through [TerminalScreen]'s `imeTransform`,
     * and it is the only thing standing between Ctrl+C on a phone and a literal `c` on the line.
     * Dropping the wiring breaks nothing that compiles, so it is asserted end to end.
     */
    @Test
    fun `an ime transform is applied to what reaches the PTY`() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = SilentSession()
        val state = TerminalScreenState(session, scope)
        val raw = mutableListOf<String>()

        setContent {
            SkerryTheme {
                CompositionLocalProvider(
                    LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                    LocalUserActivity provides {},
                ) {
                    Box(Modifier.fillMaxSize()) {
                        TerminalScreen(
                            state,
                            Modifier.fillMaxSize(),
                            imeInput = true,
                            imeTransform = { typed ->
                                raw += typed
                                applyStickyCtrl(armed = true, input = typed)
                            },
                        )
                    }
                }
            }
        }

        onNode(hasSetTextAction()).performTextInput("c")
        waitForIdle()

        assertEquals(listOf("c"), raw, "the transform never saw the keystroke")
        assertEquals(controlByte('c'), session.sent(), "the transformed byte never reached the PTY")
        scope.cancel()
    }
}

/** A session that never speaks back, and keeps what was typed at it. */
private class SilentSession : TerminalSession {
    private val bytes = StringBuilder()

    fun sent(): String = bytes.toString()

    override val state: StateFlow<TerminalState> = MutableStateFlow(TerminalState.Open)
    override val output: Flow<ByteArray> = MutableSharedFlow()
    override suspend fun send(data: ByteArray) {
        bytes.append(data.decodeToString())
    }
    override suspend fun resize(size: PtySize) = Unit
    override suspend fun close() = Unit
}
