package app.skerry.shared.runbook

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.VaultSnippetStore
import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.LibraryOrderStore
import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultRunbookStoreTest {

    private fun runbook(id: String, label: String = id) = Runbook(
        id = id,
        label = label,
        steps = listOf(RunbookStep.Command(id = "$id-1", title = "Check", command = "uptime")),
        tags = listOf("ops"),
    )

    @Test
    fun `put then all returns the runbook with its steps`() {
        val store = VaultRunbookStore(FakeVault())
        store.put(runbook("r1", "Deploy"))
        assertEquals(listOf("r1"), store.all().map { it.id })
        assertEquals(
            listOf("uptime"),
            store.all().single().steps.map { (it as RunbookStep.Command).command },
        )
    }

    @Test
    fun `put upserts and remove tombstones`() {
        val store = VaultRunbookStore(FakeVault())
        store.put(runbook("r1", "Old"))
        store.put(runbook("r1", "New"))
        assertEquals(listOf("New"), store.all().map { it.label })
        store.remove("r1")
        assertEquals(emptyList(), store.all().map { it.id })
    }

    @Test
    fun `entries survive a fresh store over the same vault`() {
        val vault = FakeVault()
        VaultRunbookStore(vault).put(runbook("r1"))
        assertEquals(listOf("r1"), VaultRunbookStore(vault).all().map { it.id })
    }

    @Test
    fun `a locked vault reads as empty instead of throwing`() {
        val vault = FakeVault()
        VaultRunbookStore(vault).put(runbook("r1"))
        vault.locked = true
        assertEquals(emptyList(), VaultRunbookStore(vault).all())
    }

    @Test
    fun `a runbook written before folders existed reads back unfiled`() {
        val vault = FakeVault()
        // A payload from a client predating the field: no "group" key at all. It has to read as
        // unfiled rather than fail the record and take the runbook with it.
        vault.put(
            "r1",
            RecordType.RUNBOOK,
            """{"id":"r1","label":"Drain","steps":[{"id":"s1","command":"uptime"}]}""".encodeToByteArray(),
        )

        val stored = VaultRunbookStore(vault).all().single()
        assertEquals("Drain", stored.label)
        assertNull(stored.group)
    }

    @Test
    fun `reorder persists the library order across store instances`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2")); store.put(runbook("r3"))
        assertEquals(listOf("r1", "r2", "r3"), store.all().map { it.id })

        store.reorder { listOf(it[2], it[0], it[1]) }

        assertEquals(listOf("r3", "r1", "r2"), VaultRunbookStore(vault).all().map { it.id })
    }

    /** See [app.skerry.shared.snippet.VaultSnippetStoreTest]: a saved runbook writes no order. */
    @Test
    fun `saving a runbook writes no order record and keeps the order of a library that has none`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2")); store.put(runbook("r3"))

        store.put(runbook("r4"))

        assertEquals(listOf("r1", "r2", "r3", "r4"), store.all().map { it.id })
        assertFalse(
            vault.records().any { it.id == LibraryOrderStore.ORDER_ID },
            "a runbook nobody dragged wrote an order record",
        )
    }

    @Test
    fun `remove drops the id from the order`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2"))
        store.reorder { it.reversed() }

        store.remove("r2")

        assertEquals(listOf("r1"), LibraryOrderStore(vault).read().runbooks)
        assertEquals(listOf("r1"), store.all().map { it.id })
    }

    @Test
    fun `reorder rejects a lost or duplicated runbook`() {
        val store = VaultRunbookStore(FakeVault())
        store.put(runbook("r1")); store.put(runbook("r2")); store.put(runbook("r3"))
        assertFailsWith<IllegalArgumentException> { store.reorder { it.dropLast(1) } }
        // Same id set, one entry twice: caught by the count, not by the set.
        assertFailsWith<IllegalArgumentException> { store.reorder { it + it[0] } }
    }

    @Test
    fun `a pure reorder does not bump runbook record versions`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2"))
        val before = vault.records().filter { it.type == RecordType.RUNBOOK }.associate { it.id to it.version }
        store.reorder { it.reversed() }
        val after = vault.records().filter { it.type == RecordType.RUNBOOK }.associate { it.id to it.version }
        assertEquals(before, after, "a reorder rewrote records whose content never changed")
    }

    @Test
    fun `reorder persists content changes like a folder move`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2"))
        store.reorder { list -> list.map { if (it.id == "r1") it.copy(group = "ops") else it } }
        val reloaded = VaultRunbookStore(vault).all().associateBy { it.id }
        assertEquals("ops", reloaded.getValue("r1").group)
        assertNull(reloaded.getValue("r2").group)
    }

    @Test
    fun `an unreadable order record is left alone instead of being overwritten`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2"))
        store.reorder { it.reversed() }
        // See the snippet twin: only the other library's list can tell an overwrite from a skip.
        val snippets = VaultSnippetStore(vault)
        snippets.put(Snippet(id = "s1", label = "Disk", command = "df -h"))
        snippets.put(Snippet(id = "s2", label = "Load", command = "uptime"))
        snippets.reorder { it.reversed() }

        vault.unreadable += LibraryOrderStore.ORDER_ID
        store.reorder { it.reversed() }
        store.remove("r1")
        vault.unreadable -= LibraryOrderStore.ORDER_ID

        assertEquals(listOf("r2", "r1"), LibraryOrderStore(vault).read().runbooks)
        assertEquals(listOf("s2", "s1"), LibraryOrderStore(vault).read().snippets, "the snippet order was overwritten")
    }

    /** See the snippet twin: an id the order doesn't mention ranks last, which is why [put] writes none. */
    @Test
    fun `a runbook created after a reorder lands at the end of the library`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2")); store.put(runbook("r3"))
        store.reorder { it.reversed() }

        store.put(runbook("r4"))

        assertEquals(listOf("r3", "r2", "r1", "r4"), store.all().map { it.id })
    }

    @Test
    fun `a content-only reorder does not bump the order record`() {
        // The record is the order of both libraries under LWW: bumping it on a transform that only
        // rewrote a field would let a device holding an older order push it back over a reorder
        // made elsewhere.
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2"))
        store.reorder { it.reversed() }
        val before = vault.records().single { it.id == LibraryOrderStore.ORDER_ID }.version
        store.reorder { list -> list.map { if (it.id == "r1") it.copy(group = "ops") else it } }
        val after = vault.records().single { it.id == LibraryOrderStore.ORDER_ID }.version
        assertEquals(before, after)
    }

    /** See the snippet twin: read and write have to share one transaction, or a merge lands between. */
    @Test
    fun `reorder reads and writes under one transaction`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1")); store.put(runbook("r2"))

        val readsBefore = vault.readsOutsideTransaction
        store.reorder { it.reversed() }

        assertEquals(readsBefore, vault.readsOutsideTransaction, "the library was read outside the transaction")
        assertTrue(vault.lastPutInTransaction, "the order was written outside the transaction")
    }
}
