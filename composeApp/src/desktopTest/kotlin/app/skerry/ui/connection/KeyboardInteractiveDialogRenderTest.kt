package app.skerry.ui.connection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import app.skerry.ui.design.DesignFonts
import app.skerry.ui.design.LocalFonts
import app.skerry.ui.theme.SkerryTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the prompt offscreen: the dialog focuses its first field on appearance and lays out one
 * row per prompt, both of which are the kind of thing that only fails once composed for real.
 */
@OptIn(ExperimentalComposeUiApi::class)
class KeyboardInteractiveDialogRenderTest {

    @Composable
    private fun DialogUnderTest(challenge: KeyboardInteractiveChallenge) {
        SkerryTheme {
            CompositionLocalProvider(
                LocalFonts provides DesignFonts(FontFamily.Default, FontFamily.Monospace, FontFamily.Default),
            ) {
                KeyboardInteractiveDialog(challenge, onDismiss = {}, onSubmit = {})
            }
        }
    }

    private fun renders(challenge: KeyboardInteractiveChallenge): Boolean {
        ImageComposeScene(width = 640, height = 520, density = Density(1f)).use { scene ->
            scene.setContent { DialogUnderTest(challenge) }
            return scene.render().width > 0
        }
    }

    @Test
    fun `renders a single code prompt`() {
        val challenge = KeyboardInteractiveChallenge(
            name = "Two-factor authentication",
            instruction = "Enter the code from your authenticator app",
            prompts = listOf(KeyboardInteractivePrompt("Verification code:", echo = false)),
        )
        assertTrue(renders(challenge))
    }

    @Test
    fun `renders several prompts in one challenge`() {
        val challenge = KeyboardInteractiveChallenge(
            name = "",
            instruction = "",
            prompts = listOf(
                KeyboardInteractivePrompt("Password:", echo = false),
                KeyboardInteractivePrompt("Verification code:", echo = false),
                KeyboardInteractivePrompt("Token serial (visible):", echo = true),
            ),
            hop = true,
        )
        assertTrue(renders(challenge))
    }

    @Test
    fun `renders a hostile challenge without letting its text through`() {
        // A server flooding the dialog: the card is capped and scrolls rather than pushing the
        // buttons out of the window.
        val challenge = KeyboardInteractiveChallenge(
            name = "A".repeat(4_000),
            instruction = "B".repeat(10_000),
            prompts = listOf(KeyboardInteractivePrompt("C".repeat(4_000), echo = false)),
        )
        assertTrue(renders(challenge))
    }
}
