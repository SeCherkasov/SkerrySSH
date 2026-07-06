package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure type-ahead logic for snippet tag suggestions: collection, excluding selected, query narrowing. */
class SnippetSuggestionsTest {

    private fun snip(vararg tags: String) = Snippet(id = "s-${tags.joinToString()}", label = "l", command = "c", tags = tags.toList())

    @Test
    fun `collects unique tags in first-seen order`() {
        val snippets = listOf(snip("monitoring", "disk"), snip("disk", "net"), snip("monitoring"))

        assertEquals(listOf("monitoring", "disk", "net"), snippetTagSuggestions(snippets, selected = emptyList()))
    }

    @Test
    fun `excludes already selected tags case-insensitively`() {
        val snippets = listOf(snip("Monitoring", "disk", "net"))

        assertEquals(listOf("disk", "net"), snippetTagSuggestions(snippets, selected = listOf("monitoring")))
    }

    @Test
    fun `narrows by query as case-insensitive substring`() {
        val snippets = listOf(snip("monitoring", "disk", "docker"))

        assertEquals(listOf("disk", "docker"), snippetTagSuggestions(snippets, selected = emptyList(), query = "d"))
        assertEquals(listOf("docker"), snippetTagSuggestions(snippets, selected = emptyList(), query = "DOCK"))
    }

    @Test
    fun `blank query keeps everything`() {
        val snippets = listOf(snip("a", "b"))

        assertEquals(listOf("a", "b"), snippetTagSuggestions(snippets, selected = emptyList(), query = "   "))
    }

    @Test
    fun `preserves original casing of stored tags`() {
        val snippets = listOf(snip("Disk", "NET"))

        assertEquals(listOf("Disk", "NET"), snippetTagSuggestions(snippets, selected = emptyList()))
    }

    @Test
    fun `no snippets yields no suggestions`() {
        assertEquals(emptyList(), snippetTagSuggestions(emptyList(), selected = emptyList()))
    }
}
