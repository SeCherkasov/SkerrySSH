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
    fun `key-file credential round-trips`() {
        val store = CredentialStore(FakeVault())
        val cred = Credential(
            "c-5",
            "Teleport cert",
            CredentialSecret.KeyFile(
                privateKeyRef = "~/.ssh/id_ed25519",
                certificateRef = "~/.ssh/id_ed25519-cert.pub",
                passphrase = "pp",
            ),
        )

        store.put(cred)

        assertEquals(cred, store.get("c-5"))
    }

    @Test
    fun `key-file credential without an explicit certificate round-trips with null`() {
        val store = CredentialStore(FakeVault())
        val cred = Credential("c-6", "Vault-signed", CredentialSecret.KeyFile(privateKeyRef = "/keys/id_rsa"))

        store.put(cred)

        assertEquals(cred, store.get("c-6"))
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
    fun `all on a locked vault safely returns an empty list`() {
        val vault = FakeVault()
        val store = CredentialStore(vault)
        store.put(Credential("a", "A", CredentialSecret.Password("x")))

        vault.locked = true

        // Sync-driven reloads (onSynced -> reloadManagers) may race a lock; reading must degrade to
        // an empty list like the sibling vault stores, not throw from Vault.records().
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `edit changes the label and keeps the id and secret`() {
        val store = CredentialStore(FakeVault())
        val secret = CredentialSecret.PrivateKey(privateKeyPem = "pem", passphrase = "pp")
        store.put(Credential("c-1", "old name", secret))

        store.edit("c-1", "new name", note = null)

        assertEquals(Credential("c-1", "new name", secret), store.get("c-1"))
    }

    @Test
    fun `edit bumps the record version so the change propagates to sync`() {
        val vault = FakeVault()
        val store = CredentialStore(vault)
        store.put(Credential("c-1", "old", CredentialSecret.Password("s")))

        store.edit("c-1", "new", note = null)

        // An edit is a re-put of the same id: the version must advance so LWW/live-sync push it.
        assertEquals(2L, vault.records().single { it.id == "c-1" }.version)
    }

    @Test
    fun `edit of a missing id is a no-op`() {
        val store = CredentialStore(FakeVault())

        store.edit("ghost", "whatever", note = null)

        assertNull(store.get("ghost"))
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `edit runs its read-modify-write inside a single transaction`() {
        val vault = FakeVault()
        val store = CredentialStore(vault)
        store.put(Credential("c-1", "old", CredentialSecret.Password("s")))
        // A plain put is a single call and holds no transaction — the control for the assertion below.
        assertFalse(vault.lastPutInTransaction)

        store.edit("c-1", "new", note = null)

        // edit's read AND its put must run under a held transaction, or a concurrent mergeRemote can
        // slip a tombstone between them (TOCTOU resurrection across all synced devices).
        assertTrue(vault.lastReadInTransaction)
        assertTrue(vault.lastPutInTransaction)
    }

    @Test
    fun `edit does not resurrect a deleted credential`() {
        val store = CredentialStore(FakeVault())
        store.put(Credential("c-1", "old", CredentialSecret.Password("s")))
        store.remove("c-1")

        store.edit("c-1", "back from the dead", note = null)

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
    fun `edit writes the note and can clear it again`() {
        val store = CredentialStore(FakeVault())
        val secret = CredentialSecret.PrivateKey(privateKeyPem = "pem", passphrase = null)
        store.put(Credential("c-1", "temp key", secret))

        store.edit("c-1", "temp key", note = "audit access, drop after 2026-09-01")
        assertEquals("audit access, drop after 2026-09-01", store.get("c-1")?.note)

        store.edit("c-1", "temp key", note = null)
        assertNull(store.get("c-1")?.note)
    }

    @Test
    fun `putKeepingNote keeps the stored note under new material`() {
        val store = CredentialStore(FakeVault())
        store.put(Credential("c-1", "temp key", CredentialSecret.PrivateKey("pem")))
        store.edit("c-1", "temp key", note = "audit access")

        // What a form re-saving a secret does: it builds the whole record from its fields, and it
        // has no note field to build one from.
        store.putKeepingNote(Credential("c-1", "temp key", CredentialSecret.PrivateKey("pem2")))

        assertEquals("audit access", store.get("c-1")?.note)
        assertEquals(CredentialSecret.PrivateKey("pem2"), store.get("c-1")?.secret)
    }

    @Test
    fun `putKeepingNote keeps the caller's note when there is no record yet`() {
        val store = CredentialStore(FakeVault())

        store.putKeepingNote(Credential("c-1", "fresh", CredentialSecret.Password("x"), note = "born with one"))

        assertEquals("born with one", store.get("c-1")?.note)
    }

    @Test
    fun `a credential does not print its own note`() {
        val cred = Credential("c-1", "prod root", CredentialSecret.Password("s3cret"), note = "root password is in 1Password")

        // The note lives in the same encrypted payload as the material it describes, and says as much
        // about it: it is redacted from logs and crash reports with the rest.
        assertEquals("Credential(id=c-1, label=redacted, note=redacted, secret=redacted)", cred.toString())
    }

    @Test
    fun `putKeepingNote runs its read-modify-write inside a single transaction`() {
        val vault = FakeVault()
        val store = CredentialStore(vault)
        store.put(Credential("c-1", "temp key", CredentialSecret.Password("x")))
        // A plain put holds no transaction — the control, as in the `edit` case above.
        assertFalse(vault.lastPutInTransaction)

        store.putKeepingNote(Credential("c-1", "temp key", CredentialSecret.Password("y")))

        // Both halves: a merge landing a tombstone between a read taken outside the transaction and
        // the write inside it would still raise the deleted secret on every device.
        assertTrue(vault.lastReadInTransaction)
        assertTrue(vault.lastPutInTransaction)
    }

    @Test
    fun `putKeepingNote does not raise a deleted credential from the dead`() {
        val store = CredentialStore(FakeVault())
        store.put(Credential("c-1", "temp key", CredentialSecret.Password("x")))
        store.remove("c-1")

        // get() answers null for a tombstone exactly as it does for an id that never existed, so a
        // write through that would resurrect the secret on every synced device — the same trap
        // `edit` guards against.
        store.putKeepingNote(Credential("c-1", "temp key", CredentialSecret.Password("y")))

        assertNull(store.get("c-1"))
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `a credential written before notes existed reads back without one`() {
        val vault = FakeVault()
        // Exactly the payload a client predating the field wrote: no "note" key at all.
        vault.put(
            "c-1",
            RecordType.CREDENTIAL,
            """{"id":"c-1","label":"Prod root","secret":{"type":"password","password":"s3cret"}}""".encodeToByteArray(),
        )
        val store = CredentialStore(vault)

        assertEquals(Credential("c-1", "Prod root", CredentialSecret.Password("s3cret"), note = null), store.get("c-1"))
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
