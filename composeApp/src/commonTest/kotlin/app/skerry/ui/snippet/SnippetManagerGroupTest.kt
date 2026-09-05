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

    private fun manager(store: SnippetStore): SnippetManager {
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
        val store = MemoryStore()
        val m = manager(store)
        m.save(draft(label = "S1", group = "Ops"))
        m.save(draft(label = "S2", group = "Ops"))
        m.save(draft(label = "S3", group = "Dev"))

        m.renameGroup("Ops", "Infra")

        assertEquals(listOf("Infra", "Infra", "Dev"), m.snippets.map { it.snippet.group })
        // The store holds what the other devices read: a rename the list shows and the store has
        // not seen is a rename that dies with the window.
        assertEquals(listOf("Infra", "Infra", "Dev"), store.all().map { it.group })
    }

    @Test
    fun `a drop counted on a filtered screen does not jump the rows it hid`() {
        val store = MemoryStore()
        val m = manager(store)
        listOf("S1", "S2", "S3", "S4").forEach { m.save(draft(label = it, group = "Ops")) }
        val id = { label: String -> m.snippets.first { it.snippet.label == label }.id }
        // The chip row and the search left S2 and S4 on screen, so dropping S4 above S2 is index 0
        // there and index 1 in the library. S1 and S3 are hidden and keep their places.
        m.moveSnippet(id("S4"), "Ops", 0, setOf(id("S2"), id("S4")))

        assertEquals(listOf("S1", "S4", "S2", "S3"), store.all().map { it.label })
    }

    @Test
    fun `a folder drop counted on a filtered screen does not jump the folders it hid`() {
        val store = MemoryStore()
        val m = manager(store)
        m.save(draft(label = "S1", group = "Alpha"))
        m.save(draft(label = "S2", group = "Beta"))
        m.save(draft(label = "S3", group = "Gamma"))
        val visible = m.snippets.filterNot { it.snippet.group == "Beta" }.map { it.id }.toSet()
        // Alpha and Gamma are on screen; dropping Alpha below Gamma is index 1 there and index 2
        // among the library's folders, because Beta sits between them unseen.
        m.moveGroup("Alpha", 1, visible)

        assertEquals(listOf("Beta", "Gamma", "Alpha"), store.all().map { it.group })
    }

    @Test
    fun `deleteGroup ungroups snippets while keeping them`() {
        val m = manager(MemoryStore())
        m.save(draft(label = "S1", group = "Ops"))
        m.save(draft(label = "S2", group = "Dev"))

        m.deleteGroup("Ops")

        assertEquals(null, m.snippets.first { it.snippet.label == "S1" }.snippet.group)
        assertEquals("Dev", m.snippets.first { it.snippet.label == "S2" }.snippet.group)
    }

    @Test
    fun `moveSnippet reorders and persists`() {
        val store = MemoryStore()
        val m = manager(store)
        val s1 = m.save(draft(label = "S1", group = "Ops"))
        val s2 = m.save(draft(label = "S2", group = "Ops"))

        m.moveSnippet(s2, "Ops", 0, setOf(s1, s2))

        assertEquals(listOf(s2, s1), m.snippets.map { it.id })
        assertEquals(listOf(s2, s1), store.all().map { it.id })
    }

    @Test
    fun `moveGroup carries the whole folder and persists`() {
        val store = MemoryStore()
        val m = manager(store)
        val s1 = m.save(draft(label = "S1", group = "Ops"))
        val s2 = m.save(draft(label = "S2", group = "Dev"))
        val s3 = m.save(draft(label = "S3", group = "Ops"))

        m.moveGroup("Dev", 0, setOf(s1, s2, s3))

        assertEquals(listOf(s2, s1, s3), m.snippets.map { it.id })
        assertEquals(listOf(s2, s1, s3), store.all().map { it.id })
    }
}
