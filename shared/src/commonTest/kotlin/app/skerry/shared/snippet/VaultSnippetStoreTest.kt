package app.skerry.shared.snippet

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.VaultRunbookStore
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.LibraryOrderStore
import app.skerry.shared.vault.RecordType
import app.skerry.shared.vault.TrashStore
import app.skerry.shared.vault.WorkspaceLayoutStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultSnippetStoreTest {

    private fun snippet(id: String, label: String = id) =
        Snippet(id = id, label = label, command = "echo $id", tags = listOf("ops"))

    @Test
    fun `put then all returns the snippet`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(snippet("s1", "Disk"))
        assertEquals(listOf("s1"), store.all().map { it.id })
        assertEquals("echo s1", store.all().single().command)
    }

    @Test
    fun `put upserts and remove tombstones`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(snippet("s1", "Old"))
        store.put(snippet("s1", "New"))
        assertEquals(listOf("New"), store.all().map { it.label })
        store.remove("s1")
        assertEquals(emptyList(), store.all().map { it.id })
    }

    @Test
    fun `entries survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultSnippetStore(vault).put(snippet("s1"))
        assertEquals(listOf("s1"), VaultSnippetStore(vault).all().map { it.id })
    }

    @Test
    fun `put preserves notes`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(Snippet(id = "s1", label = "Disk", command = "df -h", notes = "Check root partition usage"))
        assertEquals("Check root partition usage", store.all().single().notes)
    }

    @Test
    fun `a snippet written before folders existed reads back unfiled`() {
        val vault = FakeVault()
        // A payload from a client predating the field: no "group" key at all. The decoder has to
        // read it as unfiled, not fail the record and take the snippet with it.
        vault.put(
            "s1",
            RecordType.SNIPPET,
            """{"id":"s1","label":"Disk","command":"df -h","tags":["ops"]}""".encodeToByteArray(),
        )

        val stored = VaultSnippetStore(vault).all().single()
        assertEquals("Disk", stored.label)
        assertNull(stored.group)
    }

    @Test
    fun `reorder persists the library order across store instances`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2")); store.put(snippet("s3"))
        assertEquals(listOf("s1", "s2", "s3"), store.all().map { it.id })

        store.reorder { listOf(it[2], it[0], it[1]) }

        assertEquals(listOf("s3", "s1", "s2"), VaultSnippetStore(vault).all().map { it.id })
    }

    /**
     * The order record is written by a drag and by nothing else. Saving a snippet that appends an
     * id to an empty order is what would sink a whole pre-feature library below the first snippet
     * added after the upgrade — the sort puts an id the order doesn't mention last for free.
     */
    @Test
    fun `saving a snippet writes no order record and keeps the order of a library that has none`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2")); store.put(snippet("s3"))

        store.put(snippet("s4"))

        assertEquals(listOf("s1", "s2", "s3", "s4"), store.all().map { it.id })
        assertFalse(
            vault.records().any { it.id == LibraryOrderStore.ORDER_ID },
            "a snippet nobody dragged wrote an order record",
        )
    }

    /**
     * The host tree is a record of its own, and it travels between devices as one blob under LWW.
     * A snippet saved on a device holding a stale host order must not be able to push that order
     * back over a reorder made somewhere else.
     */
    @Test
    fun `snippet writes never touch the workspace layout`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))
        store.reorder { it.reversed() }
        store.remove("s1")

        assertFalse(
            vault.records().any { it.id == WorkspaceLayoutStore.LAYOUT_ID },
            "the library order was written into the host tree record",
        )
    }

    @Test
    fun `remove drops the id from the order`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))
        store.reorder { it.reversed() }

        store.remove("s2")

        assertEquals(listOf("s1"), LibraryOrderStore(vault).read().snippets)
        assertEquals(listOf("s1"), store.all().map { it.id })
    }

    @Test
    fun `reorder rejects a lost or duplicated snippet`() {
        val store = VaultSnippetStore(FakeVault())
        store.put(snippet("s1")); store.put(snippet("s2")); store.put(snippet("s3"))
        assertFailsWith<IllegalArgumentException> { store.reorder { it.dropLast(1) } }
        // Same id set, one entry twice: caught by the count, not by the set.
        assertFailsWith<IllegalArgumentException> { store.reorder { it + it[0] } }
    }

    @Test
    fun `a pure reorder does not bump snippet record versions`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))
        val before = vault.records().filter { it.type == RecordType.SNIPPET }.associate { it.id to it.version }
        store.reorder { it.reversed() }
        val after = vault.records().filter { it.type == RecordType.SNIPPET }.associate { it.id to it.version }
        assertEquals(before, after, "a reorder rewrote records whose content never changed")
    }

    @Test
    fun `reorder persists content changes like a folder move`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))
        store.reorder { list -> list.map { if (it.id == "s1") it.copy(group = "ops") else it } }
        val reloaded = VaultSnippetStore(vault).all().associateBy { it.id }
        assertEquals("ops", reloaded.getValue("s1").group)
        assertNull(reloaded.getValue("s2").group)
    }

    @Test
    fun `a content-only reorder does not bump the order record`() {
        // The record is the whole library's order under LWW: bumping it on a transform that only
        // rewrote a field (renaming a folder) would let a device holding an older order push it
        // back over a reorder made elsewhere.
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))
        store.reorder { it.reversed() }
        val before = vault.records().single { it.id == LibraryOrderStore.ORDER_ID }.version
        store.reorder { list -> list.map { if (it.id == "s1") it.copy(group = "ops") else it } }
        val after = vault.records().single { it.id == LibraryOrderStore.ORDER_ID }.version
        assertEquals(before, after)
    }

    /**
     * A record that exists and no longer decrypts (what adopting an account dataKey leaves behind)
     * reads as "no order at all". Writing over it would drop the runbook order it also carries, and
     * LWW would carry the loss to every device.
     */
    @Test
    fun `an unreadable order record is left alone instead of being overwritten`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))
        store.reorder { it.reversed() }
        // The runbook order shares the record and is what a blind write would destroy: the snippet
        // list a reverted store writes back is the one it just computed, so only the other library
        // can tell an overwrite from a skip.
        val runbooks = VaultRunbookStore(vault)
        runbooks.put(Runbook(id = "r1", label = "Drain")); runbooks.put(Runbook(id = "r2", label = "Deploy"))
        runbooks.reorder { it.reversed() }

        vault.unreadable += LibraryOrderStore.ORDER_ID
        store.reorder { it.reversed() }
        store.remove("s1")
        vault.unreadable -= LibraryOrderStore.ORDER_ID

        assertEquals(listOf("s2", "s1"), LibraryOrderStore(vault).read().snippets)
        assertEquals(listOf("r2", "r1"), LibraryOrderStore(vault).read().runbooks, "the runbook order was overwritten")
    }

    /**
     * The reason [put] writes no order at all: an id the order doesn't mention ranks last. Revert
     * that fallback and every snippet created after a drag jumps to the top of the library.
     */
    @Test
    fun `a snippet created after a reorder lands at the end of the library`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2")); store.put(snippet("s3"))
        store.reorder { it.reversed() }

        store.put(snippet("s4"))

        assertEquals(listOf("s3", "s2", "s1", "s4"), store.all().map { it.id })
    }

    @Test
    fun `a locked vault reads as empty instead of throwing`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1"))
        store.reorder { it }

        vault.locked = true

        assertEquals(emptyList(), store.all())
    }

    /**
     * The order is read and written inside the same [app.skerry.shared.vault.Vault.transaction] as
     * the records: a store that reads outside it and writes inside still lets a mergeRemote from
     * background sync land in between and be clobbered by an order computed from the stale snapshot.
     */
    @Test
    fun `reorder reads and writes under one transaction`() {
        val vault = FakeVault()
        val store = VaultSnippetStore(vault)
        store.put(snippet("s1")); store.put(snippet("s2"))

        val readsBefore = vault.readsOutsideTransaction
        store.reorder { it.reversed() }

        assertEquals(readsBefore, vault.readsOutsideTransaction, "the library was read outside the transaction")
        assertTrue(vault.lastPutInTransaction, "the order was written outside the transaction")
    }

    /**
     * The trash restores the record, not its place: [remove] dropped the id from the order, and the
     * restore path writes the record straight into the vault. The snippet is back, at the end.
     */
    @Test
    fun `a snippet restored from the trash comes back at the end of the library`() {
        val vault = FakeVault()
        val trash = TrashStore(vault)
        val store = VaultSnippetStore(vault, trash)
        store.put(snippet("s1")); store.put(snippet("s2")); store.put(snippet("s3"))
        store.reorder { it.reversed() }

        store.remove("s2")
        assertTrue(trash.restore(trash.entries().single().recordId))

        assertEquals(listOf("s3", "s1", "s2"), store.all().map { it.id })
    }
}
