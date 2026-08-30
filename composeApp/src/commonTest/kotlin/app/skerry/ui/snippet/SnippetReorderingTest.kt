package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetReorderingTest {

    private fun snippet(id: String, group: String? = null) = Snippet(
        id = id,
        label = "Label $id",
        command = "echo $id",
        group = group,
    )

    @Test
    fun `moveSnippetToGroup reorders within the same group`() {
        val snippets = listOf(
            snippet("s1", "Ops"),
            snippet("s2", "Ops"),
            snippet("s3", "Ops"),
        )
        val result = moveSnippetToGroup(snippets, snippetId = "s3", targetGroup = "Ops", targetIndexInGroup = 0)
        assertEquals(listOf("s3", "s1", "s2"), result.map { it.id })
    }

    @Test
    fun `moveSnippetToGroup moves item to another group`() {
        val snippets = listOf(
            snippet("s1", "Ops"),
            snippet("s2", "Ops"),
            snippet("s3", "Dev"),
        )
        val result = moveSnippetToGroup(snippets, snippetId = "s3", targetGroup = "Ops", targetIndexInGroup = 1)
        assertEquals(listOf("s1", "s3", "s2"), result.map { it.id })
        assertEquals("Ops", result.first { it.id == "s3" }.group)
    }

    @Test
    fun `moveSnippetToGroup moves item to ungrouped`() {
        val snippets = listOf(
            snippet("s1", "Ops"),
            snippet("s2", "Ops"),
        )
        val result = moveSnippetToGroup(snippets, snippetId = "s1", targetGroup = null, targetIndexInGroup = 0)
        assertEquals(listOf("s2", "s1"), result.map { it.id })
        assertEquals(null, result.first { it.id == "s1" }.group)
    }

    @Test
    fun `moveSnippetsToGroup batch moves multiple items preserving order`() {
        val snippets = listOf(
            snippet("s1", "Ops"),
            snippet("s2", "Ops"),
            snippet("s3", "Dev"),
            snippet("s4", "Dev"),
            snippet("s5", "Dev"),
        )
        val result = moveSnippetsToGroup(
            snippets,
            movingIds = setOf("s3", "s5"),
            targetGroup = "Ops",
            targetIndexInGroup = 1,
        )
        assertEquals(listOf("s1", "s3", "s5", "s2", "s4"), result.map { it.id })
        assertEquals("Ops", result.first { it.id == "s3" }.group)
        assertEquals("Ops", result.first { it.id == "s5" }.group)
        assertEquals("Dev", result.first { it.id == "s4" }.group)
    }

    @Test
    fun `renameSnippetGroup renames group across snippets`() {
        val snippets = listOf(
            snippet("s1", "Ops"),
            snippet("s2", "Ops"),
            snippet("s3", "Dev"),
        )
        val result = renameSnippetGroup(snippets, oldName = "Ops", newName = "Operations")
        assertEquals(listOf("Operations", "Operations", "Dev"), result.map { it.group })
        assertEquals(listOf("s1", "s2", "s3"), result.map { it.id })
    }

    @Test
    fun `renameSnippetGroup ungroups when newName is blank`() {
        val snippets = listOf(
            snippet("s1", "Ops"),
            snippet("s2", "Dev"),
        )
        val result = renameSnippetGroup(snippets, oldName = "Ops", newName = "")
        assertEquals(null, result.first { it.id == "s1" }.group)
        assertEquals("Dev", result.first { it.id == "s2" }.group)
    }

    @Test
    fun `moveSnippetGroup reorders whole folder blocks`() {
        val snippets = listOf(
            snippet("s1", "Alpha"),
            snippet("s2", "Alpha"),
            snippet("s3", "Beta"),
            snippet("s4", "Gamma"),
        )
        val result = moveSnippetGroup(snippets, group = "Gamma", targetGroupIndex = 0)
        assertEquals(listOf("s4", "s1", "s2", "s3"), result.map { it.id })

        val result2 = moveSnippetGroup(snippets, group = "Alpha", targetGroupIndex = 2)
        assertEquals(listOf("s3", "s4", "s1", "s2"), result2.map { it.id })
    }
}
