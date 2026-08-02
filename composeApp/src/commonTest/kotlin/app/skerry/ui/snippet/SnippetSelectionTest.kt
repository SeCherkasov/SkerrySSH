package app.skerry.ui.snippet

import app.skerry.shared.snippet.Snippet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetSelectionTest {

    private fun entry(label: String) = SnippetEntry(Snippet(id = label, label = label, command = "cmd $label"))

    private val deploy = entry("Deploy")
    private val dump = entry("Dump")
    private val ports = entry("Ports")
    private val all = listOf(deploy, dump, ports)

    @Test
    fun the_explicit_selection_wins_while_it_is_on_screen() {
        assertEquals(dump, resolveSelectedSnippet(visible = all, selectedId = "Dump"))
    }

    @Test
    fun a_selection_hidden_by_the_filter_moves_to_the_first_visible_row() {
        // Selected "Deploy", then picked a tag chip that filters it out: the panel must follow what
        // the list shows, or Run would fire a snippet the user can no longer see.
        assertEquals(dump, resolveSelectedSnippet(visible = listOf(dump, ports), selectedId = "Deploy"))
    }

    @Test
    fun a_deleted_selection_moves_to_the_first_visible_row() {
        assertEquals(deploy, resolveSelectedSnippet(visible = all, selectedId = "gone"))
    }

    @Test
    fun nothing_selected_falls_back_to_the_first_visible_row() {
        assertEquals(deploy, resolveSelectedSnippet(visible = all, selectedId = null))
    }

    @Test
    fun an_empty_list_selects_nothing() {
        assertNull(resolveSelectedSnippet(visible = emptyList(), selectedId = "Deploy"))
    }
}
