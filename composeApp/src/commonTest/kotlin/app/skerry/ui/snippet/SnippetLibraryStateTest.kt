package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun categories_follow_the_active_chip() {
        val s = SnippetLibraryState()
        s.activeChip = "disk"

        assertEquals(listOf("disk"), s.categories(all).map { it.name })
    }

    @Test
    fun a_chip_shows_one_section_even_when_a_snippet_carries_other_tags() {
        val multi = listOf(
            entry("Deploy", listOf("prod", "db")),
            entry("Dump", listOf("db")),
        )
        val s = SnippetLibraryState()
        s.activeChip = "prod"

        // Grouping the filtered list again would split "Deploy" back out under "db" and show it twice.
        assertEquals(listOf("prod"), s.categories(multi).map { it.name })
        assertEquals(listOf("Deploy"), s.categories(multi).single().snippets.map { it.snippet.label })
    }

    @Test
    fun toggle_collapses_and_expands_a_category() {
        val s = SnippetLibraryState()

        assertFalse(s.isCollapsed("disk"))
        s.toggleCollapsed("disk")
        assertTrue(s.isCollapsed("disk"))
        s.toggleCollapsed("disk")
        assertFalse(s.isCollapsed("disk"))
    }

    @Test
    fun a_rename_carries_the_collapsed_state_to_the_new_name() {
        val s = SnippetLibraryState()
        s.toggleCollapsed("db")

        s.onTagRenamed("db", "database")

        assertFalse(s.isCollapsed("db")) // stale key dropped
        assertTrue(s.isCollapsed("database")) // section stays collapsed under its new name
    }

    @Test
    fun a_rename_moves_the_active_chip_instead_of_falling_back_to_all() {
        val s = SnippetLibraryState()
        s.activeChip = "db"

        s.onTagRenamed("db", "database")

        assertEquals("database", s.activeChip)
    }

    @Test
    fun a_rename_leaves_an_expanded_section_and_an_unrelated_chip_untouched() {
        val s = SnippetLibraryState()
        s.activeChip = "net"

        s.onTagRenamed("db", "database") // "db" was neither collapsed nor active

        assertFalse(s.isCollapsed("database"))
        assertEquals("net", s.activeChip)
    }
}
