package app.skerry.shared.host

import app.skerry.shared.vault.DataKey
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.MergeResult
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.SyncMeta
import app.skerry.shared.vault.UnlockResult
import app.skerry.shared.vault.Vault
import app.skerry.shared.vault.VaultRecord
import app.skerry.shared.vault.WorkspaceLayoutStore
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

    /**
     * The layout is one record holding the whole account's host order, and every write to it is a
     * read-modify-write. A record that exists but no longer decrypts (what adopting an account
     * dataKey leaves behind) reads as "no layout at all", so the next saved host would replace the
     * order of every host with a one-element list — and LWW would carry that to every device.
     */
    @Test
    fun `an unreadable layout record is left alone instead of being overwritten`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        store.put(host("a"))
        store.put(host("b"))

        vault.unreadable += WorkspaceLayoutStore.LAYOUT_ID
        store.put(host("c"))
        vault.unreadable -= WorkspaceLayoutStore.LAYOUT_ID

        assertEquals(
            listOf("a", "b"),
            WorkspaceLayoutStore(vault).read().hostOrder,
            "the order of every host was replaced by the one host saved while it was unreadable",
        )
    }

    /**
     * The same record, written by the other owner: the group layer replaces the empty-folder lists
     * and must keep the host order beside them — and skip entirely when the record cannot be read,
     * or one renamed folder wipes the order of every host on the account.
     */
    @Test
    fun `updating groups keeps the host order, and skips an unreadable record`() {
        val vault = FakeVault()
        val store = VaultHostStore(vault)
        store.put(host("a"))
        store.put(host("b"))
        val layout = WorkspaceLayoutStore(vault)

        layout.updateGroups(groups = listOf("prod"), remoteDesktopGroups = listOf("lab"))

        assertEquals(listOf("a", "b"), layout.read().hostOrder, "the host order was dropped")
        assertEquals(listOf("prod"), layout.read().groups)
        assertEquals(listOf("lab"), layout.read().remoteDesktopGroups)

        vault.unreadable += WorkspaceLayoutStore.LAYOUT_ID
        layout.updateGroups(groups = listOf("staging"), remoteDesktopGroups = emptyList())
        vault.unreadable -= WorkspaceLayoutStore.LAYOUT_ID

        assertEquals(listOf("a", "b"), layout.read().hostOrder, "an unreadable layout was overwritten")
        assertEquals(listOf("prod"), layout.read().groups, "the update landed on a record it could not read")
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
    fun `jump host reference survives persist and reload`() {
        val vault = FakeVault()
        VaultHostStore(vault).put(host("web").copy(jumpHostId = "bastion"))
        assertEquals("bastion", VaultHostStore(vault).all().single().jumpHostId)
        // Absent in old records -> null (backward compatible default).
        VaultHostStore(vault).put(host("plain"))
        assertNull(VaultHostStore(vault).all().first { it.id == "plain" }.jumpHostId)
    }

    @Test
    fun `keep-alive interval survives persist and reload with a 30s default`() {
        val vault = FakeVault()
        VaultHostStore(vault).put(host("quiet").copy(keepAliveSeconds = 0))
        assertEquals(0, VaultHostStore(vault).all().single().keepAliveSeconds)
        // Absent in old records -> 30 (backward compatible default, matches the pre-setting behavior).
        VaultHostStore(vault).put(host("plain"))
        assertEquals(30, VaultHostStore(vault).all().first { it.id == "plain" }.keepAliveSeconds)
    }

    @Test
    fun `notes survive persist and reload with a null default`() {
        val vault = FakeVault()
        VaultHostStore(vault).put(host("web").copy(notes = "reboot window: Sun 03:00\nask ops first"))
        assertEquals("reboot window: Sun 03:00\nask ops first", VaultHostStore(vault).all().single().notes)
        // Absent in records written before the field existed -> null (backward compatible default).
        VaultHostStore(vault).put(host("plain"))
        assertNull(VaultHostStore(vault).all().first { it.id == "plain" }.notes)
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
        override fun mergeRemote(remote: List<VaultRecord>): MergeResult = MergeResult.EMPTY
        override fun openPayload(id: String): ByteArray? = error("locked")
        override fun put(id: String, type: RecordType, payload: ByteArray) = error("locked")
        override fun remove(id: String) = error("locked")
        override fun changePassword(oldPassword: CharArray, newPassword: CharArray) = false
        override fun verifyPassword(password: CharArray) = false
    }
}
