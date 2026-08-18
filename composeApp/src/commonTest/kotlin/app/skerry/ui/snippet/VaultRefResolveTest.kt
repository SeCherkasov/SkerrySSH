package app.skerry.ui.snippet

import app.skerry.shared.vault.Credential
import app.skerry.shared.vault.CredentialSecret
import app.skerry.shared.vault.SshPublicKeyInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The glue between the keychain and a `${'$'}{{vault:name}}` reference: which entry is found, and what
 * the platform key parser is allowed to fail at. [app.skerry.shared.snippet.snippetVaultValue] owns
 * the decision itself; what is tested here is the look-up and the failure containment around it.
 */
class VaultRefResolveTest {

    private val working = FakeSshKeyGenerator()

    private val entries = listOf(
        Credential("c-1", "prod-db", CredentialSecret.Password("s3cret")),
        Credential("c-2", "temp_pubkey", CredentialSecret.PrivateKey("pem", passphrase = "pp")),
        Credential("c-3", "on-disk", CredentialSecret.KeyFile("~/.ssh/id_ed25519")),
    )

    @Test
    fun `a password entry resolves to a masked secret`() {
        assertEquals(VaultRef.Ok("s3cret", secret = true), resolveVaultRef("prod-db", entries, working))
    }

    @Test
    fun `a key entry resolves to its public half, shown in clear`() {
        assertEquals(VaultRef.Ok(FAKE_PUBLIC_KEY, secret = false), resolveVaultRef("temp_pubkey", entries, working))
    }

    @Test
    fun `a name no entry carries is missing, not unusable`() {
        // The two say different things to the user: "no such entry" is a typo in the template,
        // "nothing to insert" is an entry that cannot serve the reference.
        assertEquals(VaultRef.Missing, resolveVaultRef("ghost", entries, working))
    }

    @Test
    fun `a file-backed entry has nothing to insert`() {
        assertEquals(VaultRef.Unusable, resolveVaultRef("on-disk", entries, working))
    }

    @Test
    fun `without a key generator a key reference is unusable rather than empty`() {
        assertEquals(VaultRef.Unusable, resolveVaultRef("temp_pubkey", entries, generator = null))
    }

    @Test
    fun `a key parser that throws leaves the reference unusable instead of killing the dialog`() {
        // inspect() is contracted to answer null on a key it cannot read, but it runs a platform
        // BER/DER parser over stored bytes: a violation of that contract must fail this one
        // reference, not the confirmation the user is standing in.
        val exploding = FakeSshKeyGenerator { error("bad key material") }

        assertEquals(VaultRef.Unusable, resolveVaultRef("temp_pubkey", entries, exploding))
    }

    @Test
    fun `a name two entries answer to resolves to nothing at all`() {
        val twins = entries + Credential("c-4", "prod-db", CredentialSecret.PrivateKey("pem"))

        // Nothing stops two secrets sharing a label. Taking the first would splice a password into
        // one run and a public key into the next, in whatever order the store happened to list them.
        assertEquals(VaultRef.Ambiguous, resolveVaultRef("prod-db", twins, working))
        assertEquals(false, vaultRefNeedsKeyParse("prod-db", twins))
    }

    @Test
    fun `only a key reference needs the parser, so the rest resolve on the spot`() {
        assertEquals(true, vaultRefNeedsKeyParse("temp_pubkey", entries))
        assertEquals(false, vaultRefNeedsKeyParse("prod-db", entries))
        assertEquals(false, vaultRefNeedsKeyParse("on-disk", entries))
        assertEquals(false, vaultRefNeedsKeyParse("ghost", entries))
    }

    @Test
    fun `a resolved password does not print itself`() {
        assertEquals("Ok(redacted)", VaultRef.Ok("s3cret", secret = true).toString())
        assertEquals("Ok($FAKE_PUBLIC_KEY)", VaultRef.Ok(FAKE_PUBLIC_KEY, secret = false).toString())
    }

    @Test
    fun `a key whose public half comes back blank is unusable`() {
        val blank = FakeSshKeyGenerator { SshPublicKeyInfo("   ", "SHA256:x", "ED25519") }

        // Splicing an empty string would run the command with the key silently missing.
        assertEquals(VaultRef.Unusable, resolveVaultRef("temp_pubkey", entries, blank))
    }
}
