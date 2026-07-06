package app.skerry.shared.host

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultHostStoreTest {

    private fun host(id: String, label: String = id, group: String? = null) =
        Host(id = id, label = label, address = "$id.example.com", port = 22, username = "root", group = group)

    @Test
    fun `put then all returns the host`() {
        val store = VaultHostStore(FakeVault())
        store.put(host("h1", "Web"))
        assertEquals(listOf("h1"), store.all().map { it.id })
        assertEquals("Web", store.all().single().label)
    }

    @Test
    fun `all preserves insertion order then reorder rewrites it`() {
        val store = VaultHostStore(FakeVault())
        store.put(host("a"))
        store.put(host("b"))
        store.put(host("c"))
        assertEquals(listOf("a", "b", "c"), store.all().map { it.id })

        store.reorder { it.reversed() }
        assertEquals(listOf("c", "b", "a"), store.all().map { it.id })
    }

    @Test
    fun `order survives a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultHostStore(vault).apply {
            put(host("a")); put(host("b")); put(host("c"))
            reorder { listOf(it[2], it[0], it[1]) } // c, a, b
        }
        // A fresh store instance over the same vault sees the persisted order (layout record in the vault).
        assertEquals(listOf("c", "a", "b"), VaultHostStore(vault).all().map { it.id })
    }

    @Test
    fun `remove tombstones the host and drops it from order`() {
        val store = VaultHostStore(FakeVault())
        store.put(host("a")); store.put(host("b"))
        store.remove("a")
        assertEquals(listOf("b"), store.all().map { it.id })
    }

    @Test
    fun `reorder rejects a changed id set`() {
        val store = VaultHostStore(FakeVault())
        store.put(host("a")); store.put(host("b"))
        assertFailsWith<IllegalArgumentException> {
            store.reorder { it.dropLast(1) }
        }
    }

    @Test
    fun `reorder persists content changes like a group move`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        store.put(host("a", group = null))
        store.put(host("b", group = null))
        store.reorder { list -> list.map { if (it.id == "a") it.copy(group = "prod") else it } }
        val reloaded = VaultHostStore(vault).all().associateBy { it.id }
        assertEquals("prod", reloaded.getValue("a").group)
        assertNull(reloaded.getValue("b").group)
    }

    @Test
    fun `pure reorder does not bump host record versions`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        store.put(host("a")); store.put(host("b"))
        val before = vault.records().filter { it.type == RecordType.HOST }.associate { it.id to it.version }
        store.reorder { it.reversed() }
        val after = vault.records().filter { it.type == RecordType.HOST }.associate { it.id to it.version }
        assertEquals(before, after) // only the layout record is bumped, host records are untouched
    }

    @Test
    fun `all on a locked vault is empty rather than throwing`() {
        val store = VaultHostStore(LockedVault)
        assertTrue(store.all().isEmpty())
    }

    /** Locked vault: reads return empty, mutators throw (matches the real [FileVault]). */
    private object LockedVault : Vault {
        override fun exists() = true
        override val isUnlocked = false
        override fun create(password: CharArray) = Unit
        override fun unlock(password: CharArray) = UnlockResult.Success
        override fun unlockWithDataKey(dataKey: DataKey) = UnlockResult.Success
        override fun exportDataKey(): DataKey? = null
        override fun adoptDataKey(newDataKey: DataKey, password: CharArray): Boolean = false
        override fun lock() = Unit
        override fun reset() = Unit
        override fun records(): List<VaultRecord> = error("locked")
        override fun syncMeta(): SyncMeta? = null
        override fun mergeRemote(remote: List<VaultRecord>): List<VaultRecord> = emptyList()
        override fun openPayload(id: String): ByteArray? = error("locked")
        override fun put(id: String, type: RecordType, payload: ByteArray) = error("locked")
        override fun remove(id: String) = error("locked")
        override fun changePassword(oldPassword: CharArray, newPassword: CharArray) = false
        override fun verifyPassword(password: CharArray) = false
    }
}
