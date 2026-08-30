package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import app.skerry.ui.design.UNGROUPED_FOLDER
import app.skerry.ui.snippet.UNCATEGORIZED_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunbookGroupingTest {

    private fun entry(label: String, group: String? = null, tags: List<String> = emptyList()) = RunbookEntry(
        Runbook(
            id = label,
            label = label,
            steps = listOf(RunbookStep.Command(id = "s1", command = "uptime")),
            tags = tags,
            group = group,
        ),
    )

    @Test
    fun search_reaches_the_folder() {
        assertTrue(entry("Drain", group = "client-acme").matches("acme"))
        assertTrue(entry("Drain", group = "client-acme").matches("CLIENT"))
        assertFalse(entry("Drain").matches("acme"))
    }

    @Test
    fun the_editor_is_offered_the_folders_the_library_already_uses() {
        val all = listOf(entry("A", "staging"), entry("B"), entry("C", "Prod"), entry("D", "staging"))

        assertEquals(listOf("Prod", "staging"), runbookFolders(all))
    }

    @Test
    fun groups_runbooks_by_folder() {
        val all = listOf(
            entry("Backup", group = "db"),
            entry("Deploy", group = "ops"),
            entry("Unfiled"),
        )
        val groups = groupRunbooksByCategory(all)
        assertEquals(3, groups.size)
        assertEquals("db", groups[0].name)
        assertEquals(1, groups[0].runbooks.size)
        assertEquals("ops", groups[1].name)
        assertEquals(1, groups[1].runbooks.size)
        assertEquals(UNGROUPED_FOLDER, groups[2].name)
        assertEquals(1, groups[2].runbooks.size)
    }

    @Test
    fun chips_are_all_plus_sorted_unique_tags() {
        val chips = runbookCategoryChips(
            listOf(entry("a", tags = listOf("net", "disk")), entry("b", tags = listOf("disk")), entry("c")),
        )

        assertEquals(listOf(ALL_RUNBOOKS_CHIP, "disk", "net", UNCATEGORIZED_KEY), chips)
    }

    @Test
    fun group_chips_are_all_plus_unique_folders_in_source_order() {
        val chips = runbookGroupChips(
            listOf(entry("a", group = "net"), entry("b", group = "disk"), entry("c")),
        )

        assertEquals(listOf(ALL_RUNBOOKS_CHIP, "net", "disk", UNGROUPED_FOLDER), chips)
    }

    @Test
    fun filter_runbooks_by_chip_and_query() {
        val all = listOf(
            entry("Backup", group = "ops"),
            entry("Deploy", group = "release"),
        )
        assertEquals(1, filterRunbooks(all, activeChip = "ops").size)
        assertEquals("Backup", filterRunbooks(all, activeChip = "ops")[0].runbook.label)
        assertEquals(1, filterRunbooks(all, query = "deploy").size)
    }
}
