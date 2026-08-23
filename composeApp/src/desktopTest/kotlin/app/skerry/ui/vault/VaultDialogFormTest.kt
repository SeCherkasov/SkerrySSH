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
import app.skerry.ui.generated.resources.vault_field_note
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

    /** Saving the same name and note is a sync push with nothing in it, so the button stays shut. */
    @Test
    fun `re-saving an unchanged secret changes nothing`() {
        var renamed: String? = null
        runForm({ EditSecretDialog(currentLabel = NAME, currentNote = null, currentGroup = null, group = "", onDismiss = {}, onConfirm = { label, _, _ -> renamed = label }, groupField = {}) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_name).performTextReplacement(RENAMED)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(RENAMED, renamed)
    }

    /** Renaming must not cost the note: the field opens prefilled and is handed back untouched. */
    @Test
    fun `an existing note survives a rename that never touches it`() {
        var saved: Pair<String, String?>? = null
        runForm({
            EditSecretDialog(
                currentLabel = NAME,
                currentNote = "drop after the audit",
                currentGroup = null,
                group = "",
                onDismiss = {},
                onConfirm = { label, note, _ -> saved = label to note },
                groupField = {},
            )
        }) {
            onField(Res.string.vault_field_name).performTextReplacement(RENAMED)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(RENAMED to "drop after the audit", saved)
    }

    /** Emptying the field means "no note", not "a note that is empty" — the store keys off null. */
    @Test
    fun `clearing the note hands back null`() {
        var saved: Pair<String, String?>? = null
        runForm({
            EditSecretDialog(
                currentLabel = NAME,
                currentNote = "drop after the audit",
                currentGroup = null,
                group = "",
                onDismiss = {},
                onConfirm = { label, note, _ -> saved = label to note },
                groupField = {},
            )
        }) {
            onField(Res.string.vault_field_note).performTextReplacement("   ")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(NAME to null, saved)
    }

    /**
     * The folder alone is enough of a change too — and it is the one value the dialog does not own:
     * the select writes it into the caller's state (the "New group" overlay stands above this
     * dialog), so what the dialog must do is notice it moved and hand back what it was given.
     */
    @Test
    fun `filing a secret into a folder enables save and hands the folder back`() {
        var saved: Triple<String, String?, String?>? = null
        runForm({
            EditSecretDialog(
                currentLabel = NAME,
                currentNote = null,
                currentGroup = null,
                group = "  client-acme  ",
                onDismiss = {},
                onConfirm = { label, note, group -> saved = Triple(label, note, group) },
                groupField = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(Triple(NAME, null, "client-acme"), saved)
    }

    /** Taking a secret out of its folder is a change like any other, and "none" is null. */
    @Test
    fun `clearing the folder hands back null`() {
        var saved: Triple<String, String?, String?>? = null
        runForm({
            EditSecretDialog(
                currentLabel = NAME,
                currentNote = null,
                currentGroup = "client-acme",
                group = "",
                onDismiss = {},
                onConfirm = { label, note, group -> saved = Triple(label, note, group) },
                groupField = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(Triple(NAME, null, null), saved)
    }

    /** A folder that normalizes back to the stored one is no change: nothing to push to sync. */
    @Test
    fun `re-picking the folder a secret is already in changes nothing`() {
        runForm({
            EditSecretDialog(
                currentLabel = NAME,
                currentNote = null,
                currentGroup = "client-acme",
                group = "client-acme ",
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                groupField = {},
            )
        }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
    }

    /** The note alone is enough of a change: a secret can be re-annotated without being renamed. */
    @Test
    fun `editing only the note enables save and normalizes it`() {
        var saved: Pair<String, String?>? = null
        runForm({ EditSecretDialog(currentLabel = NAME, currentNote = null, currentGroup = null, group = "", onDismiss = {}, onConfirm = { label, note, _ -> saved = label to note }, groupField = {}) }) {
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_note).performTextReplacement("  temp access for the audit  ")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(NAME to "temp access for the audit", saved)
    }
}

private const val NAME = "work-laptop"
private const val SECRET = "hunter2"
private const val PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"
/** Shaped like a certificate, but its blob is not one — the inspector must reject it. */
private const val KEY_PATH = "~/.ssh/id_ed25519"
private const val RENAMED = "laptop-key"
private const val CERT = "ssh-ed25519-cert-v01@openssh.com AAAA"
