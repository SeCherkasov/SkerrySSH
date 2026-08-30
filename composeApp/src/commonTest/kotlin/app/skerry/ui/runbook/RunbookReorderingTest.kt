package app.skerry.ui.runbook

import app.skerry.shared.runbook.Runbook
import kotlin.test.Test
import kotlin.test.assertEquals

class RunbookReorderingTest {

    private fun runbook(id: String, group: String? = null) = Runbook(
        id = id,
        label = "Runbook $id",
        group = group,
    )

    @Test
    fun `moveRunbookToGroup reorders within the same group`() {
        val runbooks = listOf(
            runbook("r1", "Deploy"),
            runbook("r2", "Deploy"),
            runbook("r3", "Deploy"),
        )
        val result = moveRunbookToGroup(runbooks, runbookId = "r3", targetGroup = "Deploy", targetIndexInGroup = 0)
        assertEquals(listOf("r3", "r1", "r2"), result.map { it.id })
    }

    @Test
    fun `moveRunbookToGroup moves item to another group`() {
        val runbooks = listOf(
            runbook("r1", "Deploy"),
            runbook("r2", "Deploy"),
            runbook("r3", "Backup"),
        )
        val result = moveRunbookToGroup(runbooks, runbookId = "r3", targetGroup = "Deploy", targetIndexInGroup = 1)
        assertEquals(listOf("r1", "r3", "r2"), result.map { it.id })
        assertEquals("Deploy", result.first { it.id == "r3" }.group)
    }

    @Test
    fun `moveRunbookToGroup moves item to ungrouped`() {
        val runbooks = listOf(
            runbook("r1", "Deploy"),
            runbook("r2", "Deploy"),
        )
        val result = moveRunbookToGroup(runbooks, runbookId = "r1", targetGroup = null, targetIndexInGroup = 0)
        assertEquals(listOf("r2", "r1"), result.map { it.id })
        assertEquals(null, result.first { it.id == "r1" }.group)
    }

    @Test
    fun `moveRunbooksToGroup batch moves multiple items preserving order`() {
        val runbooks = listOf(
            runbook("r1", "Deploy"),
            runbook("r2", "Deploy"),
            runbook("r3", "Backup"),
            runbook("r4", "Backup"),
            runbook("r5", "Backup"),
        )
        val result = moveRunbooksToGroup(
            runbooks,
            movingIds = setOf("r3", "r5"),
            targetGroup = "Deploy",
            targetIndexInGroup = 1,
        )
        assertEquals(listOf("r1", "r3", "r5", "r2", "r4"), result.map { it.id })
        assertEquals("Deploy", result.first { it.id == "r3" }.group)
        assertEquals("Deploy", result.first { it.id == "r5" }.group)
        assertEquals("Backup", result.first { it.id == "r4" }.group)
    }

    @Test
    fun `renameRunbookGroup renames group across runbooks`() {
        val runbooks = listOf(
            runbook("r1", "Deploy"),
            runbook("r2", "Deploy"),
            runbook("r3", "Backup"),
        )
        val result = renameRunbookGroup(runbooks, oldName = "Deploy", newName = "Deployment")
        assertEquals(listOf("Deployment", "Deployment", "Backup"), result.map { it.group })
        assertEquals(listOf("r1", "r2", "r3"), result.map { it.id })
    }

    @Test
    fun `renameRunbookGroup ungroups when newName is blank`() {
        val runbooks = listOf(
            runbook("r1", "Deploy"),
            runbook("r2", "Backup"),
        )
        val result = renameRunbookGroup(runbooks, oldName = "Deploy", newName = "")
        assertEquals(null, result.first { it.id == "r1" }.group)
        assertEquals("Backup", result.first { it.id == "r2" }.group)
    }

    @Test
    fun `moveRunbookGroup reorders whole folder blocks`() {
        val runbooks = listOf(
            runbook("r1", "Alpha"),
            runbook("r2", "Alpha"),
            runbook("r3", "Beta"),
            runbook("r4", "Gamma"),
        )
        val result = moveRunbookGroup(runbooks, group = "Gamma", targetGroupIndex = 0)
        assertEquals(listOf("r4", "r1", "r2", "r3"), result.map { it.id })

        val result2 = moveRunbookGroup(runbooks, group = "Alpha", targetGroupIndex = 2)
        assertEquals(listOf("r3", "r4", "r1", "r2"), result2.map { it.id })
    }
}
