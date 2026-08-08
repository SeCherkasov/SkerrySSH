package app.skerry.ui.vault

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PEM = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjE=\n-----END OPENSSH PRIVATE KEY-----\n"
private const val CERT = "ssh-ed25519-cert-v01@openssh.com AAAAIHNzaC1lZDI1 deploy@ci"

class SecretExportTest {

    @Test
    fun a_private_key_exports_the_private_key() {
        val export = privateKeyExport(Credential("k1", "id_ed25519", CredentialSecret.PrivateKey(PEM)))
        assertEquals(PEM, export?.content)
        assertEquals("id_ed25519.pem", export?.fileName)
    }

    @Test
    fun a_private_key_never_exports_its_public_half() {
        // The bug this test was written for: Export handed out `<label>.pub` with the OpenSSH public
        // key in it — the one half already covered by "Copy public key", and the one the user can
        // regenerate from the key anyway.
        val export = privateKeyExport(Credential("k1", "id_ed25519", CredentialSecret.PrivateKey(PEM)))!!
        assertTrue(!export.fileName.endsWith(".pub"), export.fileName)
        assertTrue(export.content.startsWith("-----BEGIN"))
    }

    @Test
    fun a_certificate_offers_the_key_and_the_certificate_as_two_separate_files() {
        // Authenticating with a certificate needs both files; exporting only the public one (the old
        // behaviour) left the credential unusable on the target machine. They are two actions, not
        // one action writing two files — see certificateExport.
        val credential = Credential("c1", "prod-ca", CredentialSecret.Certificate(PEM, CERT))
        assertEquals(SecretExport.PrivateKey("prod-ca.pem", PEM), privateKeyExport(credential))
        assertEquals(SecretExport.Public("prod-ca-cert.pub", CERT), certificateExport(credential))
    }

    @Test
    fun only_a_certificate_has_a_certificate_to_export() {
        assertEquals(null, certificateExport(Credential("k1", "id_ed25519", CredentialSecret.PrivateKey(PEM))))
        assertEquals(null, certificateExport(Credential("p1", "root", CredentialSecret.Password("x"))))
    }

    @Test
    fun a_passphrase_protected_key_is_exported_as_stored_without_its_passphrase() {
        val export = privateKeyExport(Credential("k1", "id_rsa", CredentialSecret.PrivateKey(PEM, passphrase = "s3cret")))!!
        assertEquals(PEM, export.content)
        assertTrue("s3cret" !in export.content)
    }

    @Test
    fun a_password_has_nothing_to_export() {
        assertEquals(null, privateKeyExport(Credential("p1", "root", CredentialSecret.Password("hunter2"))))
    }

    @Test
    fun a_file_backed_secret_has_nothing_to_export() {
        assertEquals(null, privateKeyExport(Credential("f1", "teleport", CredentialSecret.KeyFile("/home/me/.ssh/id_ecdsa"))))
    }

    @Test
    fun a_label_cannot_turn_into_a_path() {
        // Labels are user data and travel straight into a Save-As dialog.
        val export = privateKeyExport(Credential("k1", "../../etc/id_rsa", CredentialSecret.PrivateKey(PEM)))
        assertEquals("etc-id_rsa.pem", export?.fileName)
    }

    @Test
    fun a_nameless_secret_still_gets_a_file_name() {
        assertEquals("key.pem", privateKeyExport(Credential("k1", "///", CredentialSecret.PrivateKey(PEM)))?.fileName)
    }

    @Test
    fun a_label_that_names_a_windows_device_does_not_become_one() {
        // On Windows `aux.pem` resolves to the AUX device, not a file: the write reports success and
        // nothing appears on disk — after the user has spent a re-authentication on it.
        val export = privateKeyExport(Credential("k1", "aux", CredentialSecret.PrivateKey(PEM)))
        assertEquals("aux-key.pem", export?.fileName)
    }

    @Test
    fun each_secret_kind_gets_its_own_button_row() {
        // Decided once for both screens: a chain of ifs that happened to pair up correctly would
        // drift the first time one platform was edited and the other wasn't.
        assertEquals(
            SecretActions.KeyAndCertificate,
            secretActions(Credential("c1", "prod-ca", CredentialSecret.Certificate(PEM, CERT))),
        )
        assertEquals(
            SecretActions.KeyAndDelete,
            secretActions(Credential("k1", "id_ed25519", CredentialSecret.PrivateKey(PEM))),
        )
        assertEquals(
            SecretActions.DeleteOnly,
            secretActions(Credential("p1", "root", CredentialSecret.Password("x"))),
        )
        assertEquals(
            SecretActions.DeleteOnly,
            secretActions(Credential("f1", "teleport", CredentialSecret.KeyFile("/home/me/.ssh/id_ecdsa"))),
        )
    }

    @Test
    fun the_certificate_export_hides_the_label_too() {
        val export = certificateExport(Credential("c1", "root@customer-bastion", CredentialSecret.Certificate(PEM, CERT)))!!
        assertTrue("customer-bastion" !in export.toString(), export.toString())
    }

    @Test
    fun the_export_never_prints_the_key_material() {
        val export = privateKeyExport(Credential("k1", "id_ed25519", CredentialSecret.PrivateKey(PEM)))!!
        // Neither the key nor the label it was named after: Credential.toString redacts the label
        // for the same reason.
        assertTrue("BEGIN" !in export.toString(), export.toString())
        assertTrue("id_ed25519" !in export.toString(), export.toString())
    }
}
