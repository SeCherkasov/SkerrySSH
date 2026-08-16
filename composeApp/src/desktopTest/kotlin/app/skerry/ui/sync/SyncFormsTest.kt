package app.skerry.ui.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import app.skerry.ui.app.UiTags
import app.skerry.ui.settings.ChangeAccountPasswordDialog
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.withOfflineCoordinator
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_change_pw_confirm
import app.skerry.ui.generated.resources.settings_change_pw_current
import app.skerry.ui.generated.resources.settings_change_pw_new
import app.skerry.ui.generated.resources.sync_field_account
import app.skerry.ui.generated.resources.sync_field_master_password
import app.skerry.ui.generated.resources.sync_field_server_url
import app.skerry.ui.generated.resources.sync_keep_connected
import kotlin.test.Test

/**
 * The sync onboarding form. Every field is required before the first press: the server URL and the
 * account decide where the vault is about to be published, and the password is what the account key
 * is derived from. A press with any of them missing would go out as a login attempt that cannot
 * succeed, and register a failure against the account.
 *
 * The coordinator behind the form is real but its client is never reached — nothing here submits.
 */
@OptIn(ExperimentalTestApi::class)
class SyncFormsTest {

    /**
     * "Keep me connected" decides whether the session is persisted on this machine, and its tick is
     * a `Sym` glyph, which clears its own semantics. Drawn as a plain click the row announced a
     * button with a word for a name and no on/off state — on a switch about persisting a session
     * (issue #228).
     */
    @Test
    fun `the keep-connected row reads as a checkbox with a state`() = withOfflineCoordinator { sync ->
        runForm({ SyncSetupDialog(sync, onDismiss = {}) }) {
            val label = string(Res.string.sync_keep_connected)
            // The dialog opens with it on (the default a fresh link is offered with).
            onNodeWithText(label).assertIsToggleable().assertIsOn().performClick()
            waitForIdle()
            onNodeWithText(label).assertIsOff()
        }
    }

    @Test
    fun `connect stays shut until the whole form is filled`() = withOfflineCoordinator { sync ->
        runForm({ SyncSetupDialog(sync, onDismiss = {}) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.sync_field_server_url).performTextInput(SERVER)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.sync_field_account).performTextInput(ACCOUNT)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.sync_field_master_password).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
        }
    }

    /**
     * The server field has to be a URL, not a host name: the coordinator dials it as given, and a
     * bare `sync.example.com` has no scheme to dial with.
     */
    @Test
    fun `a server without a scheme blocks connecting`() = withOfflineCoordinator { sync ->
        runForm({ SyncSetupDialog(sync, onDismiss = {}) }) {
            onField(Res.string.sync_field_server_url).performTextInput("sync.example.com")
            onField(Res.string.sync_field_account).performTextInput(ACCOUNT)
            onField(Res.string.sync_field_master_password).performTextInput(PASSWORD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
    }

    /**
     * The account password is the master password once sync is on, so its dialog enforces the same
     * pair of rules the local one does — a confirmation that matches and a length worth deriving a
     * key from. Same three boxes, a different thing on the other end of Save.
     */
    @Test
    fun `the account password dialog holds to the same rules`() = withOfflineCoordinator { sync ->
        runForm({ ChangeAccountPasswordDialog(sync, onClose = {}, onChanged = {}) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.settings_change_pw_current).performTextInput("old-account-password")
            onField(Res.string.settings_change_pw_new).performTextInput(PASSWORD)
            onField(Res.string.settings_change_pw_confirm).performTextInput(PASSWORD + "typo")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
    }
}

private const val SERVER = "https://sync.example.com"
private const val ACCOUNT = "alice@example.com"
private const val PASSWORD = "long-enough-password"
