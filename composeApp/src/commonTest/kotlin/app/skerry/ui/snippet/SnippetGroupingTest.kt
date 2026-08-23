package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun groups_by_tag_in_alphabetical_order() {
        val groups = groupSnippetsByCategory(
            listOf(entry("Disk", listOf("disk")), entry("Ports", listOf("net")), entry("Containers", listOf("docker"))),
        )

        assertEquals(listOf("disk", "docker", "net"), groups.map { it.name })
    }

    @Test
    fun a_snippet_with_several_tags_lands_in_every_category() {
        val groups = groupSnippetsByCategory(listOf(entry("Ports", listOf("net", "disk"))))

        assertEquals(listOf("disk", "net"), groups.map { it.name })
        assertTrue(groups.all { it.snippets.single().snippet.label == "Ports" })
    }

    @Test
    fun untagged_snippets_fall_into_the_uncategorized_bucket_last() {
        val groups = groupSnippetsByCategory(listOf(entry("Loose"), entry("Disk", listOf("disk"))))

        assertEquals(listOf("disk", UNCATEGORIZED_KEY), groups.map { it.name })
        assertEquals("Loose", groups.last().snippets.single().snippet.label)
    }

    @Test
    fun keeps_source_order_inside_a_category() {
        val groups = groupSnippetsByCategory(
            listOf(entry("B", listOf("disk")), entry("A", listOf("disk"))),
        )

        assertEquals(listOf("B", "A"), groups.single().snippets.map { it.snippet.label })
    }

    @Test
    fun empty_input_produces_no_groups() {
        assertEquals(emptyList(), groupSnippetsByCategory(emptyList()))
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
