package app.skerry.shared.ssh

import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultTrustedCaStoreTest {

    private fun ca(id: String, pattern: String = "*.prod.example.com", fingerprint: String = "SHA256:CA") =
        TrustedCa(
            id = id,
            hostPattern = pattern,
            keyType = "ssh-ed25519",
            publicKey = "AAAAC3NzaC1lZDI1NTE5AAAAI",
            fingerprint = fingerprint,
            label = "prod CA",
            addedAt = "2026-07-27T00:00:00Z",
        )

    @Test
    fun `put then all returns the authority`() {
        val store = VaultTrustedCaStore(FakeVault())
        store.put(ca("ca-1"))
        assertEquals(listOf("*.prod.example.com"), store.all().map { it.hostPattern })
    }

    @Test
    fun `put upserts by id rather than adding a second record`() {
        val store = VaultTrustedCaStore(FakeVault())
        store.put(ca("ca-1", pattern = "*.old.example.com"))
        store.put(ca("ca-1", pattern = "*.new.example.com"))
        assertEquals(listOf("*.new.example.com"), store.all().map { it.hostPattern })
    }

    @Test
    fun `the same CA key can be trusted under two patterns`() {
        val store = VaultTrustedCaStore(FakeVault())
        store.put(ca("ca-1", pattern = "*.prod.example.com"))
        store.put(ca("ca-2", pattern = "*.staging.example.com"))
        assertEquals(2, store.all().size)
    }

    @Test
    fun `remove forgets the authority`() {
        val store = VaultTrustedCaStore(FakeVault())
        store.put(ca("ca-1")); store.put(ca("ca-2", pattern = "b"))
        store.remove("ca-1")
        assertEquals(listOf("ca-2"), store.all().map { it.id })
    }

    @Test
    fun `authorities survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultTrustedCaStore(vault).put(ca("ca-1"))
        assertEquals(listOf("ca-1"), VaultTrustedCaStore(vault).all().map { it.id })
    }

    @Test
    fun `records are stored under the trusted CA type`() {
        val vault = FakeVault()
        VaultTrustedCaStore(vault).put(ca("ca-1"))
        assertEquals(listOf(RecordType.TRUSTED_CA), vault.records().filter { !it.deleted }.map { it.type })
    }

    @Test
    fun `allOrNull is null on a locked vault, not an empty list`() {
        val vault = LockableVaultForCa()
        val store = VaultTrustedCaStore(vault)
        store.put(ca("ca-1"))
        vault.locked = true

        assertNull(store.allOrNull())
        // The manager UI reads through all() and must not blow up on a locked vault.
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `allOrNull is null when auto-lock fires mid-read`() {
        val store = VaultTrustedCaStore(LockableVaultForCa(lockAfterUnlockCheck = true))
        assertNull(store.allOrNull())
    }

    @Test
    fun `put on a locked vault is a no-op rather than a throw`() {
        // Writes come from UI handlers, but the vault can auto-lock at any moment; the manager
        // screen must not crash on a lock that fired a moment earlier.
        val vault = LockableVaultForCa()
        val store = VaultTrustedCaStore(vault)
        vault.locked = true
        store.put(ca("ca-1"))
        store.remove("ca-1")
        vault.locked = false
        assertEquals(emptyList(), store.all())
    }
}

/** [FakeVault] whose CRUD throws while locked — see the known-hosts store test for the same shape. */
private class LockableVaultForCa private constructor(
    private val delegate: FakeVault,
    private val lockAfterUnlockCheck: Boolean,
) : Vault by delegate {
    constructor(lockAfterUnlockCheck: Boolean = false) : this(FakeVault(), lockAfterUnlockCheck)

    var locked = false

    override val isUnlocked: Boolean
        get() {
            val unlocked = !locked
            if (lockAfterUnlockCheck) locked = true
            return unlocked
        }

    override fun records(): List<VaultRecord> {
        check(!locked) { "vault is locked" }
        return delegate.records()
    }

    override fun openPayload(id: String): ByteArray? {
        check(!locked) { "vault is locked" }
        return delegate.openPayload(id)
    }

    override fun put(id: String, type: RecordType, payload: ByteArray) {
        check(!locked) { "vault is locked" }
        delegate.put(id, type, payload)
    }
}
