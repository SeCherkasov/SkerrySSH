package app.skerry.shared.runbook

import app.skerry.shared.vault.FakeVault
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log of past runs. It answers one question the runbook itself can't — "did this work last
 * time, and how long did it take" — and it is capped, because a procedure run daily would otherwise
 * grow the vault without end.
 */
class VaultRunbookRunStoreTest {

    private fun run(id: String, runbookId: String = "rb", startedAt: Long = 0) = RunbookRunRecord(
        id = id,
        runbookId = runbookId,
        startedAt = startedAt,
        durationMillis = 72_000,
        outcome = RunbookRunOutcome.DONE,
        host = RunbookHostOutcome(label = "web-01", stepsDone = 7, stepsTotal = 7),
    )

    @Test
    fun `a recorded run comes back for its runbook`() {
        val store = VaultRunbookRunStore(FakeVault())
        store.record(run("r1"))

        assertEquals(listOf("r1"), store.forRunbook("rb").map { it.id })
        assertTrue(store.forRunbook("other").isEmpty())
    }

    @Test
    fun `runs come back newest first`() {
        val store = VaultRunbookRunStore(FakeVault())
        store.record(run("older", startedAt = 100))
        store.record(run("newer", startedAt = 300))
        store.record(run("middle", startedAt = 200))

        assertEquals(listOf("newer", "middle", "older"), store.forRunbook("rb").map { it.id })
    }

    @Test
    fun `only the last runs of a runbook are kept`() {
        val store = VaultRunbookRunStore(FakeVault(), keepPerRunbook = 3)
        repeat(5) { i -> store.record(run("r$i", startedAt = i.toLong())) }

        assertEquals(listOf("r4", "r3", "r2"), store.forRunbook("rb").map { it.id })
    }

    @Test
    fun `the cap is per runbook, not across the library`() {
        val store = VaultRunbookRunStore(FakeVault(), keepPerRunbook = 2)
        repeat(3) { i -> store.record(run("a$i", runbookId = "rb-a", startedAt = i.toLong())) }
        repeat(3) { i -> store.record(run("b$i", runbookId = "rb-b", startedAt = i.toLong())) }

        assertEquals(listOf("a2", "a1"), store.forRunbook("rb-a").map { it.id })
        assertEquals(listOf("b2", "b1"), store.forRunbook("rb-b").map { it.id })
    }

    @Test
    fun `deleting a runbook's history removes exactly its own runs`() {
        val store = VaultRunbookRunStore(FakeVault())
        store.record(run("a1", runbookId = "rb-a"))
        store.record(run("b1", runbookId = "rb-b"))

        store.forget("rb-a")

        assertTrue(store.forRunbook("rb-a").isEmpty())
        assertEquals(listOf("b1"), store.forRunbook("rb-b").map { it.id })
    }

    @Test
    fun `a locked vault reads as empty and records nothing`() {
        val vault = FakeVault()
        val store = VaultRunbookRunStore(vault)
        store.record(run("r1"))
        vault.locked = true

        assertTrue(store.forRunbook("rb").isEmpty())
        store.record(run("r2"))

        vault.locked = false
        assertEquals(listOf("r1"), store.forRunbook("rb").map { it.id }, "a locked vault takes no writes")
    }

    @Test
    fun `a failed run remembers which step it died on`() {
        val store = VaultRunbookRunStore(FakeVault())
        store.record(
            run("r1").copy(
                outcome = RunbookRunOutcome.FAILED,
                host = RunbookHostOutcome(label = "web-01", stepsDone = 5, stepsTotal = 7, failedStep = 6),
            ),
        )

        val record = store.forRunbook("rb").single()
        assertEquals(RunbookRunOutcome.FAILED, record.outcome)
        assertEquals(6, record.host.failedStep)
    }
}
