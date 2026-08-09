package app.skerry.ui.mobile

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.shell_master_password
import app.skerry.ui.generated.resources.shell_repeat_password
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone's gate screens. Same job as the desktop pair
 * ([app.skerry.ui.vault.UnlockScreenFormTest], [app.skerry.ui.vault.CreateVaultFormTest]) but a
 * separate composable, and the phone is the device most likely to be picked up by someone else —
 * so the same two rules are pinned here in their own right.
 *
 * The fields are addressed by name, not by index: the create screen draws two identical masked
 * boxes, and a test that tells them apart only by composition order would keep passing if they
 * were swapped — which is also exactly what a screen-reader user cannot do without the names.
 */
@OptIn(ExperimentalTestApi::class)
class MobileLockFormTest {

    @Test
    fun `the typed password reaches the caller`() {
        var submitted: CharArray? = null
        runForm({
            MobileUnlockScreen(
                error = null,
                canUseBiometric = false,
                onUnlock = { submitted = it },
                onBiometric = {},
                onForgotPassword = {},
            )
        }) {
            onField(Res.string.shell_master_password).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(PASSWORD, submitted?.concatToString())
    }

    @Test
    fun `an empty password is not submitted`() {
        var submitted: CharArray? = null
        runForm({
            MobileUnlockScreen(
                error = null,
                canUseBiometric = false,
                onUnlock = { submitted = it },
                onBiometric = {},
                onForgotPassword = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertNull(submitted, "an empty box was sent to the vault as an unlock attempt")
    }

    @Test
    fun `creating a vault needs the password twice`() {
        var created: Pair<String, String>? = null
        runForm({
            MobileCreateScreen(error = null, onCreate = { a, b -> created = a.concatToString() to b.concatToString() })
        }) {
            onField(Res.string.shell_master_password).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.shell_repeat_password).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(PASSWORD to PASSWORD, created)
    }

    @Test
    fun `a password that fails the strength check cannot create a vault`() {
        var created = false
        runForm({ MobileCreateScreen(error = null, onCreate = { _, _ -> created = true }) }) {
            onField(Res.string.shell_master_password).performTextInput("abc")
            onField(Res.string.shell_repeat_password).performTextInput("abc")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertEquals(false, created)
    }
}

private const val PASSWORD = "long-enough-master-password"
