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
    fun toggle_collapses_and_expands_a_category() {
        val s = SnippetLibraryState()

        assertFalse(s.isCollapsed("disk"))
        s.toggleCollapsed("disk")
        assertTrue(s.isCollapsed("disk"))
        s.toggleCollapsed("disk")
        assertFalse(s.isCollapsed("disk"))
    }
}
