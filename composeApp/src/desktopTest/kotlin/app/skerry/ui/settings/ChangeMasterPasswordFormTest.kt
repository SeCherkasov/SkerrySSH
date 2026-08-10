package app.skerry.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.settings_change_pw_confirm
import app.skerry.ui.generated.resources.settings_change_pw_current
import app.skerry.ui.generated.resources.settings_change_pw_new
import app.skerry.ui.vault.MIN_MASTER_PASSWORD_LENGTH
import app.skerry.ui.vault.VaultGateController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The master-password dialog. Everything the vault holds is behind this password, and the three
 * boxes exist to stop the two ways of losing it: a typo in the new password, which the confirm box
 * catches, and a password too short to be worth deriving a key from.
 *
 * Rendered without the settings panel around it — reaching it needs an unlocked vault.
 */
@OptIn(ExperimentalTestApi::class)
class ChangeMasterPasswordFormTest {

    @Test
    fun `the change goes through with the old and new passwords`() {
        val vault = RecordingVault()
        runForm({ dialog(vault) }) {
            onField(Res.string.settings_change_pw_current).performTextInput(OLD)
            onField(Res.string.settings_change_pw_new).performTextInput(NEW)
            onField(Res.string.settings_change_pw_confirm).performTextInput(NEW)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(OLD to NEW, vault.changed, "the dialog did not pass the pair the user typed")
    }

    /** A mistyped confirmation would lock the vault behind a password nobody knows. */
    @Test
    fun `a mismatched confirmation blocks the change`() {
        val vault = RecordingVault()
        runForm({ dialog(vault) }) {
            onField(Res.string.settings_change_pw_current).performTextInput(OLD)
            onField(Res.string.settings_change_pw_new).performTextInput(NEW)
            onField(Res.string.settings_change_pw_confirm).performTextInput(NEW + "typo")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertNull(vault.changed)
    }

    @Test
    fun `a password under the minimum length blocks the change`() {
        val short = "x".repeat(MIN_MASTER_PASSWORD_LENGTH - 1)
        val vault = RecordingVault()
        runForm({ dialog(vault) }) {
            onField(Res.string.settings_change_pw_current).performTextInput(OLD)
            onField(Res.string.settings_change_pw_new).performTextInput(short)
            onField(Res.string.settings_change_pw_confirm).performTextInput(short)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertNull(vault.changed)
    }

    @Test
    fun `the current password alone is not enough`() {
        val vault = RecordingVault()
        runForm({ dialog(vault) }) {
            onField(Res.string.settings_change_pw_current).performTextInput(OLD)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertTrue(vault.changed == null)
    }
}

@androidx.compose.runtime.Composable
private fun dialog(vault: Vault) = ChangeMasterPasswordDialog(
    controller = VaultGateController(vault),
    onClose = {},
    onChanged = {},
)

/** A vault that accepts any change and remembers the pair it was given. */
private class RecordingVault : Vault {
    var changed: Pair<String, String>? = null

    override fun changePassword(oldPassword: CharArray, newPassword: CharArray): Boolean {
        changed = oldPassword.concatToString() to newPassword.concatToString()
        return true
    }

    override fun exists(): Boolean = true
    override val isUnlocked: Boolean = true
    override fun create(password: CharArray) = Unit
    override fun unlock(password: CharArray): UnlockResult = UnlockResult.Success
    override fun unlockWithDataKey(dataKey: DataKey): UnlockResult = UnlockResult.Success
    override fun exportDataKey(): DataKey? = null
    override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
    override fun lock() = Unit
    override fun reset() = Unit
    override fun records(): List<VaultRecord> = emptyList()
    override fun syncMeta(): SyncMeta? = null
    override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
    override fun openPayload(id: String): ByteArray? = null
    override fun put(id: String, type: RecordType, payload: ByteArray) = Unit
    override fun remove(id: String) = Unit
    override fun verifyPassword(password: CharArray): Boolean = true
}

private const val OLD = "old-master-password"
private const val NEW = "new-master-password"
