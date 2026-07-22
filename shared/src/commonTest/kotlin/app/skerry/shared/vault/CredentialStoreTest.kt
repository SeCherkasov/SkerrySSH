package app.skerry.shared.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `rename changes the label and keeps the id and secret`() {
        val store = CredentialStore(FakeVault())
        val secret = CredentialSecret.PrivateKey(privateKeyPem = "pem", passphrase = "pp")
        store.put(Credential("c-1", "old name", secret))

        store.rename("c-1", "new name")

        assertEquals(Credential("c-1", "new name", secret), store.get("c-1"))
    }

    @Test
    fun `rename bumps the record version so the change propagates to sync`() {
        val vault = FakeVault()
        val store = CredentialStore(vault)
        store.put(Credential("c-1", "old", CredentialSecret.Password("s")))

        store.rename("c-1", "new")

        // A rename is a re-put of the same id: the version must advance so LWW/live-sync push it.
        assertEquals(2L, vault.records().single { it.id == "c-1" }.version)
    }

    @Test
    fun `rename of a missing id is a no-op`() {
        val store = CredentialStore(FakeVault())

        store.rename("ghost", "whatever")

        assertNull(store.get("ghost"))
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `rename runs its read-modify-write inside a single transaction`() {
        val vault = FakeVault()
        val store = CredentialStore(vault)
        store.put(Credential("c-1", "old", CredentialSecret.Password("s")))
        // A plain put is a single call and holds no transaction — the control for the assertion below.
        assertFalse(vault.lastPutInTransaction)

        store.rename("c-1", "new")

        // rename's put must run under a held transaction so a concurrent mergeRemote can't slip a
        // tombstone between the get and the put (TOCTOU resurrection across all synced devices).
        assertTrue(vault.lastPutInTransaction)
    }

    @Test
    fun `rename does not resurrect a deleted credential`() {
        val store = CredentialStore(FakeVault())
        store.put(Credential("c-1", "old", CredentialSecret.Password("s")))
        store.remove("c-1")

        store.rename("c-1", "back from the dead")

        assertNull(store.get("c-1"))
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
