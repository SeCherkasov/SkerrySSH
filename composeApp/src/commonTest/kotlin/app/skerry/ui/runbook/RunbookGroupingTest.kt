package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import app.skerry.shared.runbook.RunbookStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunbookGroupingTest {

    private fun entry(label: String, group: String? = null) = RunbookEntry(
        Runbook(
            id = label,
            label = label,
            steps = listOf(RunbookStep.Command(id = "s1", command = "uptime")),
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
}
