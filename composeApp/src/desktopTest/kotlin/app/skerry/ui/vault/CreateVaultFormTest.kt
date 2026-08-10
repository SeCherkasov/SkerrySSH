package app.skerry.ui.vault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.runForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The screen that creates the vault. Getting this wrong is the one mistake nothing can undo: the
 * master password is the only way in, and there is no recovery — hence three separate guards, all
 * of which have to hold before the button opens.
 */
@OptIn(ExperimentalTestApi::class)
class CreateVaultFormTest {

    @Test
    fun `the vault is created with the password that was typed twice`() {
        var created: Pair<String, String>? = null
        runForm({ DesktopCreateScreen(error = null, onCreate = { a, b -> created = a.concatToString() to b.concatToString() }) }) {
            password().performTextInput(PASSWORD)
            confirmation().performTextInput(PASSWORD)
            acknowledge()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(PASSWORD to PASSWORD, created)
    }

    /**
     * The acknowledgement is not decoration: "lose this password and the vault is gone" has to be
     * read before the vault exists, so an otherwise complete form still cannot be submitted.
     */
    @Test
    fun `creation is refused until the no-recovery notice is acknowledged`() {
        var created = false
        runForm({ DesktopCreateScreen(error = null, onCreate = { _, _ -> created = true }) }) {
            password().performTextInput(PASSWORD)
            confirmation().performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            acknowledge()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
        }
        assertEquals(false, created)
    }

    @Test
    fun `an unconfirmed password cannot create a vault`() {
        var created: Pair<String, String>? = null
        runForm({ DesktopCreateScreen(error = null, onCreate = { a, b -> created = a.concatToString() to b.concatToString() }) }) {
            password().performTextInput(PASSWORD)
            acknowledge()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertNull(created)
    }

    /** Too weak to derive a key from: the strength check holds the button even with everything else done. */
    @Test
    fun `a password that fails the strength check is refused`() {
        var created = false
        runForm({ DesktopCreateScreen(error = null, onCreate = { _, _ -> created = true }) }) {
            password().performTextInput("abc")
            confirmation().performTextInput("abc")
            acknowledge()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertEquals(false, created)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.password() = onAllNodesWithTag(UiTags.FORM_FIELD)[0]
    private fun androidx.compose.ui.test.ComposeUiTest.confirmation() = onAllNodesWithTag(UiTags.FORM_FIELD)[1]

    /** The notice is a checkbox row — found by its role rather than a tag of its own. */
    private fun androidx.compose.ui.test.ComposeUiTest.acknowledge() {
        onAllNodes(isToggleable())[0].performClick()
        waitForIdle()
    }
}

private const val PASSWORD = "long-enough-master-password"
