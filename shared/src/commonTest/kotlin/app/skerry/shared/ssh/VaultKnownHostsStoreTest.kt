package app.skerry.shared.ssh

import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultKnownHostsStoreTest {

    private fun kh(host: String, port: Int = 22, keyType: String = "ssh-ed25519", fp: String = "SHA256:AAA") =
        KnownHost(host, port, keyType, fp, firstSeen = "2026-06-29T00:00:00Z")

    @Test
    fun `add then all returns the key`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("web.example.com"))
        assertEquals(1, store.all().size)
        assertEquals("web.example.com", store.all().single().host)
    }

    @Test
    fun `replace upserts the same identity rather than adding a second record`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("nas", fp = "SHA256:OLD"))
        store.replace(kh("nas", fp = "SHA256:NEW"))
        assertEquals(1, store.all().size)
        assertEquals("SHA256:NEW", store.all().single().fingerprint)
    }

    @Test
    fun `same host different port or keyType are distinct identities`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("h", port = 22, keyType = "ssh-ed25519"))
        store.add(kh("h", port = 2222, keyType = "ssh-ed25519"))
        store.add(kh("h", port = 22, keyType = "rsa-sha2-512"))
        assertEquals(3, store.all().size)
    }

    @Test
    fun `remove forgets the key by identity`() {
        val store = VaultKnownHostsStore(FakeVault())
        store.add(kh("a")); store.add(kh("b"))
        store.remove("a", 22, "ssh-ed25519")
        assertEquals(listOf("b"), store.all().map { it.host })
    }

    @Test
    fun `keys survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultKnownHostsStore(vault).add(kh("persisted"))
        assertEquals(listOf("persisted"), VaultKnownHostsStore(vault).all().map { it.host })
    }

    @Test
    fun `allOrNull is null on a locked vault, not an empty list`() {
        val vault = LockableVault()
        val store = VaultKnownHostsStore(vault)
        store.add(kh("web.example.com"))
        vault.locked = true

        assertNull(store.allOrNull())
        // The non-verifier read (known-hosts manager UI) stays a safe empty list.
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `allOrNull is null when auto-lock fires mid-read`() {
        // isUnlocked passes, then the vault locks before records() — the throw must surface as
        // "unreadable" (null), not crash sshj's IO thread and not look like an empty store.
        val vault = LockableVault(lockAfterUnlockCheck = true)
        val store = VaultKnownHostsStore(vault)

        assertNull(store.allOrNull())
    }

    @Test
    fun `allOrNull returns the keys on an unlocked vault`() {
        val store = VaultKnownHostsStore(LockableVault())
        store.add(kh("web.example.com"))
        assertEquals(listOf("web.example.com"), store.allOrNull()?.map { it.host })
    }
}

/**
 * [FakeVault] with a lock switch modeling [app.skerry.shared.vault.FileVault]'s locked behavior:
 * CRUD on a locked vault throws. [lockAfterUnlockCheck] flips the lock on the first `isUnlocked`
 * read to reproduce the auto-lock-between-check-and-read race.
 */
private class LockableVault private constructor(
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

    override fun put(id: String, type: app.skerry.shared.vault.RecordType, payload: ByteArray) {
        check(!locked) { "vault is locked" }
        delegate.put(id, type, payload)
    }
}
