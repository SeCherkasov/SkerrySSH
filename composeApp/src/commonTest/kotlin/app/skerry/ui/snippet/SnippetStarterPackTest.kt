package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.shared.snippet.SnippetStore
import app.skerry.shared.tag.normalizeTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetStarterPackTest {

    private fun manager(store: SnippetStore = FakeStore()): SnippetManager {
        var n = 0
        return SnippetManager(store) { "id-${n++}" }
    }

    @Test
    fun every_starter_snippet_is_usable_and_categorized() {
        assertTrue(STARTER_SNIPPETS.isNotEmpty())
        STARTER_SNIPPETS.forEach { draft ->
            assertTrue(draft.label.isNotBlank(), "blank label")
            assertTrue(draft.command.isNotBlank(), "blank command in ${draft.label}")
            assertTrue(draft.tags.isNotEmpty(), "no category on ${draft.label}")
            // Tags are written as canonical already, so the pack groups the same way it reads.
            assertEquals(normalizeTags(draft.tags), draft.tags, "non-canonical tags on ${draft.label}")
        }
    }

    @Test
    fun covers_the_documented_categories() {
        val categories = STARTER_SNIPPETS.flatMap { it.tags }.toSet()

        assertTrue(setOf("db", "disk", "net", "docker", "monitoring").all { it in categories })
    }

    @Test
    fun install_fills_an_empty_library() {
        val store = FakeStore()
        val m = manager(store)

        val added = m.installStarterPack()

        assertEquals(STARTER_SNIPPETS.size, added)
        assertEquals(STARTER_SNIPPETS.size, m.snippets.size)
        assertEquals(STARTER_SNIPPETS.size, store.all().size)
    }

    @Test
    fun install_is_a_no_op_when_the_library_is_not_empty() {
        val store = FakeStore()
        store.put(Snippet("x", "mine", "uptime"))
        val m = manager(store)

        assertEquals(0, m.installStarterPack())
        assertEquals(1, m.snippets.size)
    }
}

private class FakeStore : SnippetStore {
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
