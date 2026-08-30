package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookPolicy
import app.skerry.shared.runbook.RunbookStep
import app.skerry.shared.runbook.RunbookStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunbookManagerTest {

    private class MemoryStore : RunbookStore {
        val items = linkedMapOf<String, Runbook>()
        override fun all(): List<Runbook> = items.values.toList()
        override fun put(runbook: Runbook) { items[runbook.id] = runbook }
        override fun remove(id: String) { items.remove(id) }
        override fun reorder(transform: (List<Runbook>) -> List<Runbook>) {
            val updated = transform(all())
            items.clear()
            updated.forEach { items[it.id] = it }
        }
    }

    private fun manager(store: RunbookStore = MemoryStore()): RunbookManager {
        var n = 0
        return RunbookManager(store, newId = { "id-${++n}" })
    }

    private fun draft(label: String = "Deploy", vararg commands: String) = RunbookDraft(
        label = label,
        description = "",
        steps = commands.mapIndexed { i, c -> RunbookStep.Command(id = "s$i", command = c) },
        tags = listOf("Ops"),
    )

    @Test
    fun `save creates a runbook and assigns an id`() {
        val store = MemoryStore()
        val m = manager(store)
        val id = m.save(draft(commands = arrayOf("uptime")))

        assertEquals("id-1", id)
        assertEquals(listOf("Deploy"), m.runbooks.map { it.runbook.label })
        assertEquals(listOf("uptime"), store.items.getValue(id).steps.map { it.summaryLine() })
    }

    @Test
    fun `saving an existing id updates it in place`() {
        val m = manager()
        val id = m.save(draft("Old", "uptime"))
        m.save(draft("New", "uptime").copy(id = id))

        assertEquals(1, m.runbooks.size)
        assertEquals("New", m.find(id)?.runbook?.label)
    }

    @Test
    fun `save normalizes the folder and drops a blank one`() {
        val m = manager()
        val filed = m.save(draft(commands = arrayOf("uptime")).copy(group = "  client-acme  "))
        assertEquals("client-acme", m.find(filed)?.runbook?.group)

        val unfiled = m.save(draft(commands = arrayOf("uptime")).copy(group = "   "))
        assertNull(m.find(unfiled)?.runbook?.group)
    }

    @Test
    fun `tags are canonicalized on save`() {
        val m = manager()
        val id = m.save(draft(commands = arrayOf("uptime")).copy(tags = listOf("#DB", "db", "Ops")))
        assertEquals(listOf("db", "ops"), m.find(id)?.runbook?.tags)
    }

    @Test
    fun `steps without an id get one so reordering keeps them apart`() {
        val m = manager()
        val id = m.save(
            RunbookDraft(label = "Deploy", steps = listOf(RunbookStep.Command(id = "", command = "a"), RunbookStep.Command(id = "", command = "b"))),
        )
        val ids = m.find(id)!!.runbook.steps.map { it.id }
        assertEquals(2, ids.distinct().size, "step ids must be unique: $ids")
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `blank steps are dropped on save`() {
        val m = manager()
        val id = m.save(
            RunbookDraft(
                label = "Deploy",
                steps = listOf(RunbookStep.Command(id = "a", command = "uptime"), RunbookStep.Command(id = "b", command = "   ")),
            ),
        )
        assertEquals(listOf("uptime"), m.find(id)!!.runbook.steps.map { it.summaryLine() })
    }

    @Test
    fun `a transfer step with no destination is dropped like a blank command`() {
        val m = manager()
        val id = m.save(
            RunbookDraft(
                label = "Deploy",
                steps = listOf(
                    RunbookStep.Transfer(id = "a", localPath = "app.tgz", remotePath = "/srv/app.tgz"),
                    RunbookStep.Transfer(id = "b", localPath = "app.tgz", remotePath = "  "),
                ),
            ),
        )

        assertEquals(listOf("sftp: app.tgz → /srv/app.tgz"), m.find(id)!!.runbook.steps.map { it.summaryLine() })
    }

    @Test
    fun `the run policy is saved with the runbook`() {
        val store = MemoryStore()
        val m = manager(store)
        val id = m.save(
            draft(commands = arrayOf("uptime")).copy(
                policy = RunbookPolicy(stopOnFirstFailure = false, watchdogMinutes = 5),
            ),
        )

        assertEquals(false, store.items.getValue(id).policy.stopOnFirstFailure)
        assertEquals(5, store.items.getValue(id).policy.watchdogMinutes)
    }

    @Test
    fun `delete removes it from the list and the store`() {
        val store = MemoryStore()
        val m = manager(store)
        val id = m.save(draft(commands = arrayOf("uptime")))
        m.delete(id)

        assertTrue(m.runbooks.isEmpty())
        assertTrue(store.items.isEmpty())
        assertNull(m.find(id))
    }

    @Test
    fun `reload picks up what the store gained behind our back`() {
        val store = MemoryStore()
        val m = manager(store)
        store.put(Runbook(id = "x", label = "Synced", steps = listOf(RunbookStep.Command(id = "s", command = "uptime"))))

        assertTrue(m.runbooks.isEmpty())
        m.reload()
        assertEquals(listOf("Synced"), m.runbooks.map { it.runbook.label })
    }

    @Test
    fun `renameGroup updates group across runbooks`() {
        val m = manager()
        m.save(draft("R1", "uptime").copy(group = "Deploy"))
        m.save(draft("R2", "uptime").copy(group = "Deploy"))
        m.save(draft("R3", "uptime").copy(group = "Backup"))

        m.renameGroup("Deploy", "Deployment")

        assertEquals(listOf("Deployment", "Deployment", "Backup"), m.runbooks.map { it.runbook.group })
    }

    @Test
    fun `deleteGroup ungroups runbooks without deleting them`() {
        val m = manager()
        m.save(draft("R1", "uptime").copy(group = "Deploy"))
        m.save(draft("R2", "uptime").copy(group = "Backup"))

        m.deleteGroup("Deploy")

        assertEquals(null, m.runbooks.first { it.runbook.label == "R1" }.runbook.group)
        assertEquals("Backup", m.runbooks.first { it.runbook.label == "R2" }.runbook.group)
    }

    @Test
    fun `moveRunbook reorders and persists`() {
        val m = manager()
        val r1 = m.save(draft("R1", "uptime").copy(group = "Deploy"))
        val r2 = m.save(draft("R2", "uptime").copy(group = "Deploy"))

        m.moveRunbook(r2, "Deploy", 0)

        assertEquals(listOf(r2, r1), m.runbooks.map { it.id })
    }
}
