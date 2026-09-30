package app.skerry.ui.vault

import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportKeyTest {

    @Test
    fun `a bare key imports as a private key and lands in SSH keys`() {
        val draft = importedKeyDraft("laptop", "PEM", certificate = null, passphrase = null)

        assertEquals(CredentialSecret.PrivateKey("PEM", null), draft.toSecret())
        assertEquals(VaultCategoryKind.SSH_KEYS, draft.importCategory())
    }

    @Test
    fun `a key with a certificate imports as a certificate and keeps its passphrase`() {
        val draft = importedKeyDraft("prod", "PEM", certificate = "CERT", passphrase = "pw")

        assertEquals(CredentialSecret.Certificate("PEM", "CERT", "pw"), draft.toSecret())
        assertEquals(VaultCategoryKind.CERTIFICATES, draft.importCategory())
    }

    // Issue #396: the key itself pasted into *Link key file*'s path field was saved as a path. The
    // dialog has to tell the two apart before it links anything.

    @Test
    fun `a PEM block is key material, not a path`() {
        assertTrue(looksLikeKeyMaterial("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk=\n-----END OPENSSH PRIVATE KEY-----"))
        assertTrue(looksLikeKeyMaterial("  -----BEGIN RSA PRIVATE KEY-----"), "a PEM header alone, as a single-line field keeps it")
    }

    @Test
    fun `a public key or certificate line is key material`() {
        assertTrue(looksLikeKeyMaterial("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGw alice@laptop"))
        assertTrue(looksLikeKeyMaterial("ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1NTE5LWNlcnQ alice@skerry"))
        assertTrue(looksLikeKeyMaterial("ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY="))
    }

    @Test
    fun `paths and document refs are not key material`() {
        assertFalse(looksLikeKeyMaterial("~/.ssh/id_ed25519"))
        assertFalse(looksLikeKeyMaterial("C:\\Users\\me\\.ssh\\id_ed25519"))
        assertFalse(looksLikeKeyMaterial("\"C:\\Users\\me\\.ssh\\id_ed25519\""))
        assertFalse(looksLikeKeyMaterial("/home/me/keys/ssh-ed25519 AAAA backup"), "a path that merely contains a key-type word")
        assertFalse(looksLikeKeyMaterial("content://com.android.externalstorage.documents/document/primary%3Aid"))
        assertFalse(looksLikeKeyMaterial(""))
    }
}
