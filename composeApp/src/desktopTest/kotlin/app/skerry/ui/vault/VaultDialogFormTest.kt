package app.skerry.ui.vault

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.skerry.shared.vault.SshKeyType
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runForm
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_field_certificate
import app.skerry.ui.generated.resources.vault_field_key_path
import app.skerry.ui.generated.resources.vault_field_name
import app.skerry.ui.generated.resources.vault_field_password
import app.skerry.ui.generated.resources.vault_field_private_key_pem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The keychain's create dialogs. Every one of them hands a secret to the vault, so what matters is
 * that the values reaching the callback are the ones that were typed — and that a half-filled
 * dialog cannot fire at all, which would put an unusable record in the keychain.
 */
@OptIn(ExperimentalTestApi::class)
class VaultDialogFormTest {

    @Test
    fun `generating a key passes the typed name on`() {
        var created: Pair<String, SshKeyType>? = null
        runForm({ GenerateKeyDialog(onDismiss = {}, onCreate = { name, type -> created = name to type }) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(NAME, created?.first)
        assertEquals(SshKeyType.ED25519, created?.second, "the dialog's default key type changed")
    }

    @Test
    fun `a password secret carries both the name and the password`() {
        var created: Pair<String, String>? = null
        runForm({ AddPasswordDialog(onDismiss = {}, onCreate = { name, pw -> created = name to pw }) }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_password).performTextInput(SECRET)
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(NAME to SECRET, created)
    }

    /** A password record with no password is a record that cannot be used to log in anywhere. */
    @Test
    fun `a password secret cannot be saved without the password`() {
        var created: Pair<String, String>? = null
        runForm({ AddPasswordDialog(onDismiss = {}, onCreate = { name, pw -> created = name to pw }) }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertNull(created)
    }

    @Test
    fun `cancelling a dialog creates nothing`() {
        var created = false
        runForm({ AddPasswordDialog(onDismiss = {}, onCreate = { _, _ -> created = true }) }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_password).performTextInput(SECRET)
            onNodeWithTag(UiTags.FORM_CANCEL).performClick()
            waitForIdle()
        }
        assertTrue(!created, "cancel created a secret anyway")
    }

    /**
     * The import stays shut until the certificate actually parses: the dialog reads it with the real
     * inspector, so a name and a key are not enough, and neither is a certificate-shaped string. A
     * record that only looks like a certificate would fail later, at connect time, on a live host.
     */
    @Test
    fun `a certificate that does not parse cannot be imported`() {
        var created = false
        runForm({
            ImportCertificateDialog(
                inspector = SshjCertificateInspector(),
                onDismiss = {},
                onCreate = { _, _, _, _ -> created = true },
            )
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_certificate).performTextInput(CERT)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertTrue(!created)
    }

    /** A key on disk is referenced, not copied — without a path there is nothing to reference. */
    @Test
    fun `linking a key file needs a path as well as a name`() {
        var created: Triple<String, String, String?>? = null
        runForm({
            LinkKeyFileDialog(onDismiss = {}, onCreate = { name, keyRef, certRef, _ -> created = Triple(name, keyRef, certRef) })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_key_path).performTextInput(KEY_PATH)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(NAME, created?.first)
        assertEquals(KEY_PATH, created?.second)
    }

    /** Renaming to the same label is a sync push with nothing in it, so the button stays shut. */
    @Test
    fun `renaming to the same label changes nothing`() {
        var renamed: String? = null
        runForm({ RenameSecretDialog(currentLabel = NAME, onDismiss = {}, onConfirm = { renamed = it }) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_name).performTextReplacement(RENAMED)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(RENAMED, renamed)
    }
}

private const val NAME = "work-laptop"
private const val SECRET = "hunter2"
private const val PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
/** Shaped like a certificate, but its blob is not one — the inspector must reject it. */
private const val KEY_PATH = "~/.ssh/id_ed25519"
private const val RENAMED = "laptop-key"
private const val CERT = "ssh-ed25519-cert-v01@openssh.com AAAA"
