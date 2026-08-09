package app.skerry.ui.connection

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.host.Host
import app.skerry.shared.ssh.KeyboardInteractiveChallenge
import app.skerry.shared.ssh.KeyboardInteractivePrompt
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_kbdint_asks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The two forms that ask for a secret at connect time, neither of which was reachable from the demo
 * graph — one needs a host mid-dial, the other a server that asks a question back.
 *
 * They are the last hop before a credential leaves the machine, so what is checked is that the
 * characters typed are the characters handed to the transport, and that an empty box cannot be sent
 * (an empty password is a failed auth attempt the server counts against the account).
 */
@OptIn(ExperimentalTestApi::class)
class ConnectSecretFormTest {

    @Test
    fun `the typed password is the one handed to the connect`() {
        var connected: String? = null
        runForm({ DesktopPasswordDialog(host = HOST, onDismiss = {}, onConnect = { connected = it }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(PASSWORD, connected)
    }

    @Test
    fun `an empty password cannot be sent`() {
        var connected: String? = null
        runForm({ DesktopPasswordDialog(host = HOST, onDismiss = {}, onConnect = { connected = it }) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertNull(connected)
    }

    @Test
    fun `dismissing the password prompt connects nothing`() {
        var connected: String? = null
        runForm({ DesktopPasswordDialog(host = HOST, onDismiss = {}, onConnect = { connected = it }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_CANCEL).performClick()
            waitForIdle()
        }
        assertNull(connected)
    }

    /**
     * A keyboard-interactive challenge can carry several prompts (password, then a one-time code),
     * and the answers go back as a list the server reads positionally. Swapping them fails the
     * login with no hint as to why, so the order is the thing worth pinning.
     */
    @Test
    fun `answers go back in the order the server asked`() {
        var answers: List<String>? = null
        runForm({
            KeyboardInteractiveDialog(
                requestId = 1L,
                challenge = TWO_PROMPTS,
                onDismiss = {},
                onSubmit = { answers = it },
            )
        }) {
            onAllNodesWithTag(UiTags.FORM_FIELD)[0].performTextInput(PASSWORD)
            onAllNodesWithTag(UiTags.FORM_FIELD)[1].performTextInput(OTP)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(PASSWORD, OTP), answers)
    }

    /**
     * The dialog says whose words these are, whatever the server sent.
     *
     * Everything below Skerry's own heading is written by the host that was dialled, prompt captions
     * included, and a host that fills in nothing but a prompt can caption the input box itself:
     * "Skerry vault master password:" over a field whose answer goes straight back to it. The
     * provenance line is the only thing that tells the two apart, so it is drawn even when the
     * challenge carries no name and no instruction, and it rides on the field's own name too.
     */
    @Test
    fun `a bare challenge still says the words are the server's`() {
        runForm({
            KeyboardInteractiveDialog(requestId = 1L, challenge = SPOOFING_PROMPT, onDismiss = {}, onSubmit = {})
        }) {
            val asks = string(Res.string.shell_kbdint_asks)
            onNodeWithText(asks).assertIsDisplayed()
            onNodeWithContentDescription("$asks ${SPOOFING_PROMPT.prompts.first().text}").assertExists()
        }
    }

    /** Dismissing a challenge must answer nothing rather than send blanks the server would reject. */
    @Test
    fun `dismissing a challenge answers nothing`() {
        var answers: List<String>? = null
        runForm({
            KeyboardInteractiveDialog(
                requestId = 1L,
                challenge = TWO_PROMPTS,
                onDismiss = {},
                onSubmit = { answers = it },
            )
        }) {
            onAllNodesWithTag(UiTags.FORM_FIELD)[0].performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_CANCEL).performClick()
            waitForIdle()
        }
        assertNull(answers)
    }
}

private val HOST = Host("h1", "prod-web-01", "10.0.0.1", 22, "root", null)
private const val PASSWORD = "hunter2"
private const val OTP = "123456"

/** What a hostile host sends to make its prompt read like Skerry's own: nothing else at all. */
private val SPOOFING_PROMPT = KeyboardInteractiveChallenge(
    name = "",
    instruction = "",
    prompts = listOf(KeyboardInteractivePrompt(text = "Skerry vault master password:", echo = false)),
)

private val TWO_PROMPTS = KeyboardInteractiveChallenge(
    name = "Two-factor",
    instruction = "",
    prompts = listOf(
        KeyboardInteractivePrompt(text = "Password: ", echo = false),
        KeyboardInteractivePrompt(text = "Verification code: ", echo = true),
    ),
)
