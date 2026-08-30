package app.skerry.shared.runbook

import app.skerry.shared.vault.FakeVault
import app.skerry.shared.vault.RecordType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `reorder updates and persists order in layout`() {
        val vault = FakeVault()
        val store = VaultRunbookStore(vault)
        store.put(runbook("r1"))
        store.put(runbook("r2"))
        store.put(runbook("r3"))

        assertEquals(listOf("r1", "r2", "r3"), store.all().map { it.id })

        store.reorder { listOf(it[2], it[0], it[1]) }

        val freshStore = VaultRunbookStore(vault)
        assertEquals(listOf("r3", "r1", "r2"), freshStore.all().map { it.id })
    }
}
