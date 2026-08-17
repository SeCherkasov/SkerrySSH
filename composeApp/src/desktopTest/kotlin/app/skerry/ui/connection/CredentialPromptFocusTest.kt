package app.skerry.ui.connection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalPresence
import app.skerry.ui.desktop.WithWindowInfo
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_kbdint_asks
import app.skerry.ui.mobile.MobileActionSheet
import app.skerry.ui.mobile.MobilePasswordSheet
import app.skerry.ui.mobile.MobileSheetAction
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two rules that keep a secret out of the session a prompt opened over: the prompt counts as a
 * modal (so whatever owns the keyboard underneath does not claim it back — see
 * [app.skerry.ui.design.ClaimKeyboard]) and it takes the caret itself, because nothing else moves
 * focus off a live terminal or framebuffer.
 *
 * The desktop password dialog has both proven over a live session in
 * [app.skerry.ui.desktop.KeyboardClaimShellTest]; its two siblings are checked here, where standing
 * one up costs a composition rather than a shell.
 */
@OptIn(ExperimentalTestApi::class)
class CredentialPromptFocusTest {

    @Test
    fun `the keyboard-interactive prompt registers as a modal and takes the caret`() {
        val challenge = KeyboardInteractiveChallenge(
            name = "Two-factor",
            instruction = "",
            prompts = listOf(KeyboardInteractivePrompt(text = CODE_PROMPT, echo = false)),
        )
        runComposeUiTest {
            val base = ModalPresence.openCount
            setContent {
                Themed { KeyboardInteractiveDialog(requestId = 1L, challenge = challenge, onDismiss = {}, onSubmit = {}) }
            }
            waitForIdle()
            assertTrue(ModalPresence.openCount > base, "the 2FA prompt does not count as a modal")
            onNodeWithContentDescription("${string(Res.string.shell_kbdint_asks)} $CODE_PROMPT").assertIsFocused()
        }
    }

    @Test
    fun `the phone's password sheet registers as a modal`() {
        runComposeUiTest {
            val base = ModalPresence.openCount
            setContent {
                Themed { MobilePasswordSheet(host = HOST, onDismiss = {}, onConnect = {}) }
            }
            waitForIdle()
            assertTrue(ModalPresence.openCount > base, "the phone's password sheet does not count as a modal")
            // The half that keeps the password out of the session underneath: a hardware keyboard
            // (a tablet, DeX) types wherever the caret is, and nothing else moves it off a session.
            onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    @Test
    fun `the phone's action sheet registers as a modal`() {
        runComposeUiTest {
            val base = ModalPresence.openCount
            setContent {
                Themed {
                    MobileActionSheet(
                        title = "Close session",
                        actions = listOf(MobileSheetAction(label = "Close", onClick = {})),
                        onDismiss = {},
                    )
                }
            }
            waitForIdle()
            assertTrue(ModalPresence.openCount > base, "the phone's action sheet does not count as a modal")
        }
    }

    /**
     * Two prompts at once: a connect password for one host, and a second factor another host asks
     * for while it is up (a snippet run reconnecting, a tunnel dialing). Whichever opened last is
     * the one the user sees, and it has to be the one the caret is in — a password typed into a
     * field belonging to a different host is sent to that host in the clear.
     */
    @Test
    fun `the prompt that opened last is the one on top and the one with the caret`() {
        val challenge = KeyboardInteractiveChallenge(
            name = "Two-factor",
            instruction = "",
            prompts = listOf(KeyboardInteractivePrompt(text = CODE_PROMPT, echo = false)),
        )
        var secondFactorUp by mutableStateOf(false)
        var dismissed: String? = null
        runComposeUiTest {
            setContent {
                Themed {
                    Box(Modifier.fillMaxSize()) {
                        // Composed in the chrome's order: the second-factor host sits above the
                        // password dialogs in the source, so source order alone draws it underneath.
                        if (secondFactorUp) {
                            KeyboardInteractiveDialog(
                                requestId = 1L,
                                challenge = challenge,
                                onDismiss = { dismissed = "2fa" },
                                onSubmit = {},
                            )
                        }
                        DesktopPasswordDialog(host = HOST, onDismiss = { dismissed = "password" }, onConnect = {})
                    }
                }
            }
            waitForIdle()
            secondFactorUp = true
            waitForIdle()

            onNodeWithContentDescription("${string(Res.string.shell_kbdint_asks)} $CODE_PROMPT").assertIsFocused()
            // The scrims are full-screen, so whichever prompt is drawn on top is the one a click on
            // the backdrop reaches — that is what says which one the user is actually looking at.
            onAllNodes(isRoot())[0].performMouseInput { click(Offset(4f, 4f)) }
            waitForIdle()
            assertEquals("2fa", dismissed, "the prompt with the caret was not the one on top")
        }
    }

    /**
     * Compose releases focus app-wide when the window goes away and restores nothing on the way
     * back (`ComposeSceneMediator.focusLost` → `releaseFocus`). A prompt left like that looks alive
     * and swallows every keystroke — and the root shortcut handler stands down while it is up, so
     * the password goes nowhere at all until the field is clicked.
     */
    @Test
    fun `a prompt takes the caret back when the window returns`() {
        var windowFocused by mutableStateOf(true)
        val windowInfo = object : WindowInfo {
            override val isWindowFocused: Boolean get() = windowFocused
        }
        runComposeUiTest {
            setContent {
                WithWindowInfo(windowInfo) {
                    Themed { DesktopPasswordDialog(host = HOST, onDismiss = {}, onConnect = {}) }
                }
            }
            waitForIdle()
            onNode(hasSetTextAction()).assertIsFocused()

            windowFocused = false
            waitForIdle()
            runOnIdle { focusManager?.clearFocus(force = true) }
            waitForIdle()
            windowFocused = true
            waitForIdle()

            onNode(hasSetTextAction()).assertIsFocused()
        }
    }

    /** Captured from the composition, to do to focus what the scene does when the window leaves. */
    private var focusManager: FocusManager? = null

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        val focus = LocalFocusManager.current
        LaunchedEffect(focus) { focusManager = focus }
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
                content = content,
            )
        }
    }

    private companion object {
        const val CODE_PROMPT = "Verification code:"
        val HOST = Host("h-2fa", "prod-web-01", "10.0.0.1", 22, "root", null)
    }
}
