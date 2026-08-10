package app.skerry.ui.vault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The unlock screen — the one form standing between a stolen laptop and the vault.
 *
 * Two things are checked because both have teeth: an empty password must not be submitted (the
 * vault would count a failed attempt against a press of Enter on an empty box), and the password
 * must reach the caller as the characters typed, since it is handed on as a [CharArray] the caller
 * wipes rather than a String.
 */
@OptIn(ExperimentalTestApi::class)
class UnlockScreenFormTest {

    @Test
    fun `the typed password reaches the caller`() {
        var submitted: CharArray? = null
        runForm({ unlockScreen(onUnlock = { submitted = it }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(PASSWORD, submitted?.concatToString())
    }

    @Test
    fun `an empty password is not submitted`() {
        var submitted: CharArray? = null
        runForm({ unlockScreen(onUnlock = { submitted = it }) }) {
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertNull(submitted, "an empty box was sent to the vault as an unlock attempt")
    }

    /**
     * With no biometrics enrolled the screen must not ask the platform for a prompt — on a machine
     * where none is configured that is a dialog that can never be answered.
     */
    @Test
    fun `no biometric prompt is raised when biometrics are off`() {
        var prompted = false
        runForm({ unlockScreen(onUnlock = {}, canUseBiometric = false, onBiometric = { prompted = true }) }) {
            onNodeWithTag(UiTags.FORM_FIELD).performTextInput(PASSWORD)
        }
        assertFalse(prompted)
    }
}

@androidx.compose.runtime.Composable
private fun unlockScreen(
    onUnlock: (CharArray) -> Unit,
    canUseBiometric: Boolean = false,
    onBiometric: () -> Unit = {},
) = DesktopUnlockScreen(
    error = null,
    canUseBiometric = canUseBiometric,
    onUnlock = onUnlock,
    onBiometric = onBiometric,
    onForgotPassword = {},
)

private const val PASSWORD = "correct horse battery"
