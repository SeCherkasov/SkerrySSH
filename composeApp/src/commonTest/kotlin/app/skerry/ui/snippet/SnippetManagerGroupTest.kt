package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetManagerGroupTest {

    private class MemoryStore : SnippetStore {
        val items = linkedMapOf<String, Snippet>()
        override fun all(): List<Snippet> = items.values.toList()
        override fun put(snippet: Snippet) { items[snippet.id] = snippet }
        override fun remove(id: String) { items.remove(id) }
        override fun reorder(transform: (List<Snippet>) -> List<Snippet>) {
            val updated = transform(all())
            items.clear()
            updated.forEach { items[it.id] = it }
        }
    }

    private fun manager(store: SnippetStore = MemoryStore()): SnippetManager {
        var n = 0
        return SnippetManager(store, newId = { "id-${++n}" })
    }

    private fun draft(label: String = "S", command: String = "echo", group: String? = null) = SnippetDraft(
        label = label,
        command = command,
        tags = listOf("ops"),
        group = group,
    )

    @Test
    fun `renameGroup updates group across snippets and persists`() {
        val m = manager()
        m.save(draft(label = "S1", group = "Ops"))
        m.save(draft(label = "S2", group = "Ops"))
        m.save(draft(label = "S3", group = "Dev"))

        m.renameGroup("Ops", "Infra")

        assertEquals(listOf("Infra", "Infra", "Dev"), m.snippets.map { it.snippet.group })
    }

    @Test
    fun `deleteGroup ungroups snippets while keeping them`() {
        val m = manager()
        m.save(draft(label = "S1", group = "Ops"))
        m.save(draft(label = "S2", group = "Dev"))

        m.deleteGroup("Ops")

        assertEquals(null, m.snippets.first { it.snippet.label == "S1" }.snippet.group)
        assertEquals("Dev", m.snippets.first { it.snippet.label == "S2" }.snippet.group)
    }

    @Test
    fun `moveSnippet reorders and persists`() {
        val m = manager()
        val s1 = m.save(draft(label = "S1", group = "Ops"))
        val s2 = m.save(draft(label = "S2", group = "Ops"))

        m.moveSnippet(s2, "Ops", 0)

        assertEquals(listOf(s2, s1), m.snippets.map { it.id })
    }
}
