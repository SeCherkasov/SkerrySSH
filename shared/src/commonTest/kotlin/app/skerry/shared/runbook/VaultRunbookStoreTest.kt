package app.skerry.shared.runbook

import app.skerry.shared.vault.FakeVault
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultRunbookStoreTest {

    private fun runbook(id: String, label: String = id) = Runbook(
        id = id,
        label = label,
        steps = listOf(RunbookStep(id = "$id-1", title = "Check", command = "uptime")),
        tags = listOf("ops"),
    )

    @Test
    fun `put then all returns the runbook with its steps`() {
        val store = VaultRunbookStore(FakeVault())
        store.put(runbook("r1", "Deploy"))
        assertEquals(listOf("r1"), store.all().map { it.id })
        assertEquals(listOf("uptime"), store.all().single().steps.map { it.command })
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
}
