package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CredentialStoreTest {

    @Test
    fun `put then get round-trips a password credential`() {
        val store = CredentialStore(FakeVault())
        val cred = Credential("c-1", "Prod root", CredentialSecret.Password("s3cret"))

        store.put(cred)

        assertEquals(cred, store.get("c-1"))
    }

    @Test
    fun `put then get round-trips a private-key credential with passphrase`() {
        val store = CredentialStore(FakeVault())
        val cred = Credential(
            "c-2",
            "Laptop key",
            CredentialSecret.PrivateKey(privateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n...", passphrase = "pp"),
        )

        store.put(cred)

        assertEquals(cred, store.get("c-2"))
    }

    @Test
    fun `private-key credential without passphrase round-trips with null`() {
        val store = CredentialStore(FakeVault())
        val cred = Credential("c-3", "CI key", CredentialSecret.PrivateKey(privateKeyPem = "pem", passphrase = null))

        store.put(cred)

        assertEquals(cred, store.get("c-3"))
    }

    @Test
    fun `certificate credential round-trips`() {
        val store = CredentialStore(FakeVault())
        val cred = Credential("c-4", "Bastion cert", CredentialSecret.Certificate("pem", "ssh-ed25519-cert...", "pp"))

        store.put(cred)

        assertEquals(cred, store.get("c-4"))
    }

    @Test
    fun `all returns live credentials and skips tombstones`() {
        val store = CredentialStore(FakeVault())
        store.put(Credential("a", "A", CredentialSecret.Password("x")))
        store.put(Credential("b", "B", CredentialSecret.Password("y")))

        store.remove("a")

        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test
    fun `all ignores records of other types`() {
        val vault = FakeVault()
        vault.put("acct-1", RecordType.IDENTITY, "whatever".encodeToByteArray())
        val store = CredentialStore(vault)
        store.put(Credential("c-1", "Key", CredentialSecret.Password("x")))

        assertEquals(listOf("c-1"), store.all().map { it.id })
    }

    @Test
    fun `all skips a credential record whose payload does not decode`() {
        val vault = FakeVault()
        vault.put("broken", RecordType.CREDENTIAL, "not json".encodeToByteArray())
        val store = CredentialStore(vault)
        store.put(Credential("ok", "Key", CredentialSecret.Password("x")))

        assertEquals(listOf("ok"), store.all().map { it.id })
    }
}
