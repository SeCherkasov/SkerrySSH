package app.skerry.ui.vault

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.skerry.shared.vault.SshCertificateInfo
import app.skerry.shared.vault.SshCertificateInspector
import app.skerry.shared.vault.SshKeyGenerator
import app.skerry.shared.vault.SshKeyType
import app.skerry.shared.vault.SshPublicKeyInfo
import app.skerry.shared.vault.SshjCertificateInspector
import app.skerry.ui.app.UiTags
import app.skerry.ui.desktop.onField
import app.skerry.ui.desktop.runForm
import app.skerry.ui.desktop.string
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.vault_cert_key_mismatch
import app.skerry.ui.generated.resources.vault_cert_read_error
import app.skerry.ui.generated.resources.vault_field_cert_path
import app.skerry.ui.generated.resources.vault_field_certificate
import app.skerry.ui.generated.resources.vault_field_key_path
import app.skerry.ui.generated.resources.vault_field_name
import app.skerry.ui.generated.resources.vault_field_note
import app.skerry.ui.generated.resources.vault_field_passphrase
import app.skerry.ui.generated.resources.vault_field_password
import app.skerry.ui.generated.resources.vault_field_private_key_pem
import app.skerry.ui.generated.resources.vault_key_path_is_key
import app.skerry.ui.generated.resources.vault_key_read_error
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
            ImportKeyDialog(
                mode = ImportKeyMode.CERTIFICATE,
                generator = FakeKeys(),
                inspector = SshjCertificateInspector(),
                onDismiss = {},
                onCreate = { _, _, _, _ -> created = true },
            )
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            awaitKeyRead()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_certificate).performTextInput(CERT)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
        assertTrue(!created)
    }

    /** Opened from Certificates, the import is a certificate import: a bare key is not what was asked for. */
    @Test
    fun `a certificate import needs the certificate`() {
        runForm({
            ImportKeyDialog(ImportKeyMode.CERTIFICATE, FakeKeys(), CertInspector, onDismiss = {}, onCreate = { _, _, _, _ -> })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            awaitKeyRead()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_certificate).performTextInput(VALID_CERT)
            awaitSaveEnabled()
        }
    }

    /** Issue #396's ask: a pasted private key becomes a key secret, with no certificate and no file. */
    @Test
    fun `importing a key without a certificate hands back just the key`() {
        var created: List<String?>? = null
        runForm({
            ImportKeyDialog(ImportKeyMode.KEY, FakeKeys(), CertInspector, onDismiss = {}, onCreate = { name, pem, cert, pass -> created = listOf(name, pem, cert, pass) })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
            onField(Res.string.vault_field_private_key_pem).performTextInput("\n$PEM\n")
            awaitSaveEnabled()
            onNodeWithText(KEY_INFO.fingerprintSha256, substring = true).assertExists()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(NAME, PEM, null, null), created)
    }

    /**
     * What sshj cannot load here, it cannot log in with either: the key is read with the same parser
     * the connection uses, and a record that fails it never reaches the vault.
     */
    @Test
    fun `a private key that does not parse cannot be imported`() {
        var created = false
        runForm({
            ImportKeyDialog(ImportKeyMode.KEY, FakeKeys(), CertInspector, onDismiss = {}, onCreate = { _, _, _, _ -> created = true })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput("-----BEGIN OPENSSH PRIVATE KEY-----\nnot a key")
            awaitKeyRead()
            onNodeWithText(string(Res.string.vault_key_read_error)).assertExists()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled().performClick()
            waitForIdle()
        }
        assertTrue(!created)
    }

    /** An encrypted key reads only with its passphrase — and that passphrase is what gets stored. */
    @Test
    fun `an encrypted key imports once its passphrase is entered`() {
        var created: List<String?>? = null
        runForm({
            ImportKeyDialog(ImportKeyMode.KEY, FakeKeys(passphrase = SECRET), CertInspector, onDismiss = {}, onCreate = { name, pem, cert, pass -> created = listOf(name, pem, cert, pass) })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            awaitKeyRead()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_passphrase).performTextInput(SECRET)
            awaitSaveEnabled()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(NAME, PEM, null, SECRET), created)
    }

    /** The certificate is optional for a key, but one that is there has to parse. */
    @Test
    fun `an optional certificate is handed back when it parses and blocks the import when it does not`() {
        var created: List<String?>? = null
        runForm({
            ImportKeyDialog(ImportKeyMode.KEY, FakeKeys(), CertInspector, onDismiss = {}, onCreate = { name, pem, cert, pass -> created = listOf(name, pem, cert, pass) })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            awaitSaveEnabled()

            onField(Res.string.vault_field_certificate).performTextInput(CERT)
            onNodeWithText(string(Res.string.vault_cert_read_error)).assertExists()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_certificate).performTextReplacement(VALID_CERT)
            awaitSaveEnabled()
            onNodeWithTag(UiTags.FORM_SAVE).performClick()
            waitForIdle()
        }
        assertEquals(listOf(NAME, PEM, VALID_CERT, null), created)
    }

    /**
     * A verdict belongs to the text it was read from. Edited after reading, the key is unread again
     * until the next read lands — the button must shut at once, not after the pause, or the click in
     * between saves a key nobody read.
     */
    @Test
    fun `editing a key after it read shuts the import until it is read again`() {
        var created = false
        runForm({
            ImportKeyDialog(ImportKeyMode.KEY, FakeKeys(passphrase = SECRET), CertInspector, onDismiss = {}, onCreate = { _, _, _, _ -> created = true })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            onField(Res.string.vault_field_passphrase).performTextInput(SECRET)
            awaitSaveEnabled()

            onField(Res.string.vault_field_passphrase).performTextReplacement("hunter3")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_passphrase).performTextReplacement(SECRET)
            awaitSaveEnabled()
            onField(Res.string.vault_field_private_key_pem).performTextReplacement("$PEM\nx")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled().performClick()
            waitForIdle()
        }
        assertTrue(!created)
    }

    /** A certificate that parses but names another key would be refused by the server at login. */
    @Test
    fun `a certificate issued for another key cannot be imported with this one`() {
        runForm({
            ImportKeyDialog(ImportKeyMode.KEY, FakeKeys(), CertInspector, onDismiss = {}, onCreate = { _, _, _, _ -> })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_private_key_pem).performTextInput(PEM)
            onField(Res.string.vault_field_certificate).performTextInput(OTHER_KEY_CERT)
            awaitKeyRead()
            onNodeWithText(string(Res.string.vault_cert_key_mismatch)).assertExists()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()
        }
    }

    /** Issue #396: the key itself in the path field was linked as a path and broke the vault on open. */
    @Test
    fun `a key pasted into the key file path is refused`() {
        var created = false
        runForm({
            LinkKeyFileDialog(onDismiss = {}, onCreate = { _, _, _, _ -> created = true })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_key_path).performTextInput(PEM)
            onNodeWithText(string(Res.string.vault_key_path_is_key)).assertExists()
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

    /** Explorer's "Copy as path" wraps the path in quotes; stored as-is it named no file (#396). */
    @Test
    fun `a pasted path in quotes is linked without them`() {
        var created: Triple<String, String, String?>? = null
        runForm({
            LinkKeyFileDialog(onDismiss = {}, onCreate = { name, keyRef, certRef, _ -> created = Triple(name, keyRef, certRef) })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_key_path).performTextInput("\"\"")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_key_path).performTextReplacement("\"$KEY_PATH\"")
            onField(Res.string.vault_field_cert_path).performTextInput("\"$KEY_PATH-cert.pub\"")
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled().performClick()
            waitForIdle()
        }
        assertEquals(KEY_PATH, created?.second)
        assertEquals("$KEY_PATH-cert.pub", created?.third)
    }

    /** A certificate line in the certificate path is the same mistake as a key in the key path. */
    @Test
    fun `a certificate pasted into the certificate path is refused`() {
        runForm({
            LinkKeyFileDialog(onDismiss = {}, onCreate = { _, _, _, _ -> })
        }) {
            onField(Res.string.vault_field_name).performTextInput(NAME)
            onField(Res.string.vault_field_key_path).performTextInput(KEY_PATH)
            onField(Res.string.vault_field_cert_path).performTextInput(VALID_CERT)
            onNodeWithText(string(Res.string.vault_key_path_is_key)).assertExists()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsNotEnabled()

            onField(Res.string.vault_field_cert_path).performTextReplacement("$KEY_PATH-cert.pub")
            onNodeWithText(string(Res.string.vault_key_path_is_key)).assertDoesNotExist()
            onNodeWithTag(UiTags.FORM_SAVE).assertIsEnabled()
        }
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
private const val VALID_CERT = "ssh-ed25519-cert-v01@openssh.com AAAAvalid alice@skerry"
private val KEY_INFO = SshPublicKeyInfo("ssh-ed25519 AAAAC3Nza", "SHA256:q0uM3cD0x5aW", "ED25519")

/** Reads [PEM] as a key — with [passphrase] only, when one is set — and nothing else. */
private class FakeKeys(private val passphrase: String? = null) : SshKeyGenerator {
    override fun generate(type: SshKeyType, comment: String) = error("the import never generates")
    override fun inspect(privateKeyPem: String, passphrase: String?): SshPublicKeyInfo? =
        KEY_INFO.takeIf { privateKeyPem == PEM && passphrase == this.passphrase }
}

private const val OTHER_KEY_CERT = "ssh-ed25519-cert-v01@openssh.com AAAAother bob@skerry"

private val CertInspector = SshCertificateInspector { cert ->
    val issuedFor = when (cert) {
        VALID_CERT -> KEY_INFO.fingerprintSha256
        OTHER_KEY_CERT -> "SHA256:someone-else"
        else -> return@SshCertificateInspector null
    }
    SshCertificateInfo("ED25519", "alice", listOf("alice"), "1", "2026-01-01", SshCertificateInfo.FOREVER, false, "SHA256:ca", issuedFor)
}

/** The key is read off the UI thread; a verdict either way shows as a line under the fields. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitKeyRead() = waitUntil(timeoutMillis = 5_000) {
    val verdicts = listOf(string(Res.string.vault_key_read_error), KEY_INFO.fingerprintSha256)
    verdicts.any { onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.awaitSaveEnabled() = waitUntil(timeoutMillis = 5_000) {
    onNodeWithTag(UiTags.FORM_SAVE).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
}
