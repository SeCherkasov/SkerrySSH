package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import app.skerry.ui.design.UNGROUPED_FOLDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnippetGroupingTest {

    private fun entry(
        label: String,
        tags: List<String> = emptyList(),
        command: String = "cmd",
        notes: String? = null,
        group: String? = null,
    ) = SnippetEntry(Snippet(id = label, label = label, command = command, tags = tags, notes = notes, group = group))

    @Test
    fun folder_sections_are_the_ones_the_library_draws_in_the_order_it_draws_them() {
        // The palette sections by folder, not by tag: a command sits in exactly one folder, and the
        // order is the one the user dragged the library into ([LibraryOrder]), so the palette shows
        // a command where its owner expects it.
        val sections = snippetFolderSections(
            listOf(entry("Ports", group = "net"), entry("Loose"), entry("Disk", group = "disk"), entry("Df", group = "net")),
        )

        assertEquals(listOf("net", "disk", UNGROUPED_FOLDER), sections.map { it.name })
        assertEquals(listOf("Ports", "Df"), sections.first().items.map { it.snippet.label })
        assertEquals(listOf("Loose"), sections.last().items.map { it.snippet.label })
    }

    @Test
    fun a_snippet_belongs_to_one_folder_however_many_tags_it_carries() {
        val sections = snippetFolderSections(listOf(entry("Ports", tags = listOf("net", "disk"), group = "ops")))

        assertEquals(listOf("ops"), sections.map { it.name })
    }

    @Test
    fun tags_are_unique_and_sorted() {
        assertEquals(
            listOf("disk", "net"),
            snippetTags(listOf(entry("a", tags = listOf("net", "disk")), entry("b", tags = listOf("disk")))),
        )
        assertEquals(emptyList(), snippetTags(listOf(entry("a"))))
    }

    @Test
    fun a_library_with_no_tags_has_no_filter_row() {
        assertTrue(hasCategories(listOf(entry("a", tags = listOf("disk")))))
        assertFalse(hasCategories(listOf(entry("a"), entry("b", group = "ops"))))
    }

    @Test
    fun chips_are_all_plus_sorted_unique_tags() {
        val chips = snippetCategoryChips(
            listOf(entry("a", listOf("net", "disk")), entry("b", listOf("disk")), entry("c")),
        )

        assertEquals(listOf(ALL_SNIPPETS_CHIP, "disk", "net", UNCATEGORIZED_KEY), chips)
    }

    @Test
    fun chips_gain_the_uncategorized_entry_only_when_something_is_untagged() {
        assertEquals(
            listOf(ALL_SNIPPETS_CHIP, "disk"),
            snippetCategoryChips(listOf(entry("a", listOf("disk")))),
        )
        assertEquals(
            listOf(ALL_SNIPPETS_CHIP, "disk", UNCATEGORIZED_KEY),
            snippetCategoryChips(listOf(entry("a", listOf("disk")), entry("b"))),
        )
    }

    @Test
    fun filter_narrows_by_chip() {
        val all = listOf(entry("Disk", listOf("disk")), entry("Ports", listOf("net")), entry("Loose"))

        assertEquals(3, filterSnippets(all).size)
        assertEquals(listOf("Disk"), filterSnippets(all, activeChip = "disk").map { it.snippet.label })
        assertEquals(listOf("Loose"), filterSnippets(all, activeChip = UNCATEGORIZED_KEY).map { it.snippet.label })
    }

    @Test
    fun filter_combines_chip_and_query() {
        val all = listOf(
            entry("Disk usage", listOf("disk"), command = "df -h"),
            entry("Disk io", listOf("net"), command = "iostat"),
        )

        assertEquals(listOf("Disk usage"), filterSnippets(all, activeChip = "disk", query = "disk").map { it.snippet.label })
        assertTrue(filterSnippets(all, activeChip = "disk", query = "iostat").isEmpty())
    }

    @Test
    fun search_reaches_the_folder() {
        // The folder is what the user filed it under; a search that ignores it makes the folder a
        // thing you can only find by scrolling.
        val all = listOf(entry("Rollout", group = "client-acme"), entry("Disk"))

        assertEquals(listOf("Rollout"), filterSnippets(all, query = "acme").map { it.snippet.label })
        assertEquals(listOf("Rollout"), filterSnippets(all, query = "CLIENT").map { it.snippet.label })
    }

    @Test
    fun the_editor_is_offered_the_folders_the_library_already_uses() {
        val all = listOf(entry("A", group = "staging"), entry("B"), entry("C", group = "Prod"), entry("D", group = "staging"))

        assertEquals(listOf("Prod", "staging"), snippetFolders(all))
    }

    @Test
    fun search_reaches_the_notes() {
        val all = listOf(
            entry("Rollout", command = "kubectl apply -f -", notes = "Drains the canary pool first"),
            entry("Disk", command = "df -h"),
        )

        assertEquals(listOf("Rollout"), filterSnippets(all, query = "canary").map { it.snippet.label })
        assertEquals(listOf("Rollout"), filterSnippets(all, query = "DRAINS").map { it.snippet.label })
    }
}
