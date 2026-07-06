package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetManagerTest {

    private fun managerWith(
        store: SnippetStore = FakeSnippetStore(),
        ids: List<String> = List(20) { "id-$it" },
    ): SnippetManager {
        val it = ids.iterator()
        return SnippetManager(store) { it.next() }
    }

    private fun draft(label: String = "Disk usage", command: String = "df -h", tags: List<String> = emptyList()) =
        SnippetDraft(label = label, command = command, tags = tags)

    @Test
    fun `save persists a new snippet and lists it`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)

        val id = manager.save(draft(tags = listOf("monitoring")))

        assertEquals("id-0", id)
        val entry = manager.snippets.single()
        assertEquals("Disk usage", entry.snippet.label)
        assertEquals(listOf("monitoring"), entry.snippet.tags)
        assertEquals(listOf(id), store.all().map { it.id }) // долетело до стора
    }

    @Test
    fun `save with existing id updates in place`() {
        val manager = managerWith()
        val id = manager.save(draft(label = "old"))

        manager.save(draft(label = "renamed").copy(id = id))

        val entry = manager.snippets.single()
        assertEquals("renamed", entry.snippet.label)
    }

    @Test
    fun `delete removes the snippet from store and list`() {
        val store = FakeSnippetStore()
        val manager = managerWith(store)
        val id = manager.save(draft())

        manager.delete(id)

        assertTrue(manager.snippets.isEmpty())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `run sends the command followed by a newline`() {
        val manager = managerWith()
        val id = manager.save(draft(command = "uptime"))
        var sent: String? = null

        manager.run(id) { sent = it }

        assertEquals("uptime\n", sent)
    }

    @Test
    fun `run with unknown id is a no-op`() {
        val manager = managerWith()
        var sent: String? = null

        manager.run("nope") { sent = it }

        assertNull(sent)
    }

    @Test
    fun `save persists shortcut`() {
        val manager = managerWith()

        val id = manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        val s = manager.find(id)!!.snippet
        assertEquals("Ctrl+Shift+D", s.shortcut)
    }

    @Test
    fun `save normalizes a blank shortcut to null`() {
        val manager = managerWith()

        val id = manager.save(draft().copy(shortcut = "   "))

        assertNull(manager.find(id)!!.snippet.shortcut)
    }

    @Test
    fun `forShortcut finds the snippet bound to a hotkey`() {
        val manager = managerWith()
        val id = manager.save(draft(label = "Disk").copy(shortcut = "Ctrl+Shift+D"))
        manager.save(draft(label = "Mem")) // no hotkey

        assertEquals(id, manager.forShortcut("Ctrl+Shift+D")?.id)
    }

    @Test
    fun `forShortcut returns null for blank or unmatched`() {
        val manager = managerWith()
        manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        assertNull(manager.forShortcut(null))
        assertNull(manager.forShortcut(""))
        assertNull(manager.forShortcut("Ctrl+Shift+X"))
    }

    @Test
    fun `shortcutConflict finds another snippet holding the hotkey`() {
        val manager = managerWith()
        val owner = manager.save(draft(label = "Disk").copy(shortcut = "Ctrl+Shift+D"))

        val conflict = manager.shortcutConflict("Ctrl+Shift+D", excludingId = null)

        assertEquals(owner, conflict?.id)
    }

    @Test
    fun `shortcutConflict ignores the snippet being edited`() {
        val manager = managerWith()
        val id = manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        assertNull(manager.shortcutConflict("Ctrl+Shift+D", excludingId = id))
    }

    @Test
    fun `shortcutConflict is null for blank or free hotkey`() {
        val manager = managerWith()
        manager.save(draft().copy(shortcut = "Ctrl+Shift+D"))

        assertNull(manager.shortcutConflict(null, excludingId = null))
        assertNull(manager.shortcutConflict("", excludingId = null))
        assertNull(manager.shortcutConflict("Ctrl+Shift+X", excludingId = null))
    }

    @Test
    fun `find returns null for null or unknown id`() {
        val manager = managerWith()
        manager.save(draft())

        assertNull(manager.find(null))
        assertNull(manager.find("nope"))
    }

    @Test
    fun `loads previously saved snippets on construction`() {
        val store = FakeSnippetStore()
        store.put(Snippet("x", "saved", "ls -la", listOf("fs")))
        val manager = managerWith(store)

        assertEquals(listOf("saved"), manager.snippets.map { it.snippet.label })
        assertEquals(listOf("fs"), manager.snippets.single().snippet.tags)
    }
}

private class FakeSnippetStore : SnippetStore {
    private val entries = mutableListOf<Snippet>()
    override fun all(): List<Snippet> = entries.toList()
    override fun put(snippet: Snippet) {
        val i = entries.indexOfFirst { it.id == snippet.id }
        if (i >= 0) entries[i] = snippet else entries += snippet
    }
    override fun remove(id: String) {
        entries.removeAll { it.id == id }
    }
}
