package app.skerry.ui.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.withOfflineCoordinator
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.web_access_field_new
import app.skerry.ui.generated.resources.web_access_field_repeat
import kotlin.test.Test

/**
 * The web-access password form. This password is what lets a browser at the sync server read the
 * account's records, so it is a second credential with the same reach as the vault's own — and the
 * repeat box is the only thing standing between a typo and a password nobody knows.
 */
@OptIn(ExperimentalTestApi::class)
class WebAccessFormTest {

    @Test
    fun `saving needs both boxes to agree`() = withOfflineCoordinator { sync ->
        runForm({ WebAccessCard(sync) }) {
            openForm()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.web_access_field_new).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.web_access_field_repeat).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
        }
    }

    @Test
    fun `a mistyped repeat blocks saving`() = withOfflineCoordinator { sync ->
        runForm({ WebAccessCard(sync) }) {
            openForm()
            onField(Res.string.web_access_field_new).performTextInput(PASSWORD)
            onField(Res.string.web_access_field_repeat).performTextInput(PASSWORD + "typo")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
    }
    /** The card shows a summary until the password is asked for; the boxes come with that press. */
    private fun androidx.compose.ui.test.ComposeUiTest.openForm() {
        onNodeWithTag(UiTags.FORM_EDIT).performClick()
        waitForIdle()
    }
}

private const val PASSWORD = "browser-access-password"
