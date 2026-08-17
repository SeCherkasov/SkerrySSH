package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.design.ModalPresence
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_kbdint_asks
import app.skerry.ui.mobile.MobileActionSheet
import app.skerry.ui.mobile.MobilePasswordSheet
import app.skerry.ui.mobile.MobileSheetAction
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
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

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
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
