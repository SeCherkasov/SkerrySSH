package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetLibraryStateTest {

    private fun entry(label: String, tags: List<String> = emptyList()) =
        SnippetEntry(Snippet(id = label, label = label, command = "cmd $label", tags = tags))

    private val all = listOf(
        entry("Disk", listOf("disk")),
        entry("Ports", listOf("net")),
        entry("Loose"),
    )

    @Test
    fun starts_unfiltered() {
        val s = SnippetLibraryState()

        assertEquals(ALL_SNIPPETS_CHIP, s.activeChip)
        assertEquals(3, s.visible(all).size)
    }

    @Test
    fun chip_and_query_narrow_the_list() {
        val s = SnippetLibraryState()
        s.activeChip = "disk"
        assertEquals(listOf("Disk"), s.visible(all).map { it.snippet.label })

        s.activeChip = ALL_SNIPPETS_CHIP
        s.query = "ports"
        assertEquals(listOf("Ports"), s.visible(all).map { it.snippet.label })
    }

    @Test
    fun a_chip_whose_category_disappeared_behaves_like_all() {
        val s = SnippetLibraryState()
        s.activeChip = "docker" // last #docker snippet has just been deleted

        assertEquals(3, s.visible(all).size)
    }

    @Test
    fun a_snippet_carrying_several_tags_is_listed_once_per_chip() {
        val multi = listOf(
            entry("Deploy", listOf("prod", "db")),
            entry("Dump", listOf("db")),
        )
        val s = SnippetLibraryState()
        s.activeChip = "db"

        // The list is flat: a snippet with two tags appears once, not once per tag.
        assertEquals(listOf("Deploy", "Dump"), s.visible(multi).map { it.snippet.label })
    }

    @Test
    fun a_rename_moves_the_active_chip_instead_of_falling_back_to_all() {
        val s = SnippetLibraryState()
        s.activeChip = "db"

        s.onTagRenamed("db", "database")

        assertEquals("database", s.activeChip)
    }

    @Test
    fun a_rename_leaves_an_unrelated_chip_untouched() {
        val s = SnippetLibraryState()
        s.activeChip = "net"

        s.onTagRenamed("db", "database") // "db" was not the active chip

        assertEquals("net", s.activeChip)
    }
}
