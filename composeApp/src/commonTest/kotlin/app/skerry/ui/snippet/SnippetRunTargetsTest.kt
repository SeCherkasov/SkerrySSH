package app.skerry.ui.snippet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnippetRunTargetsTest {

    private val targets = listOf(
        SnippetRunTarget(id = "t1", label = "prod-web-01"),
        SnippetRunTarget(id = "t2", label = "db-master"),
    )

    @Test
    fun the_active_session_is_the_default_target() {
        assertEquals("t2", defaultSnippetRunTarget(targets, activeId = "t2", chosenId = null)?.id)
    }

    @Test
    fun an_explicit_choice_wins_over_the_active_session() {
        assertEquals("t1", defaultSnippetRunTarget(targets, activeId = "t2", chosenId = "t1")?.id)
    }

    @Test
    fun a_choice_whose_session_is_gone_falls_back_to_the_active_one() {
        // The chosen tab was closed while the panel stayed open — running into its id would go nowhere.
        assertEquals("t2", defaultSnippetRunTarget(targets, activeId = "t2", chosenId = "closed")?.id)
    }

    @Test
    fun without_a_connected_active_session_the_first_target_is_used() {
        // Active tab is a remote desktop or a blank pane: it never reaches the target list.
        assertEquals("t1", defaultSnippetRunTarget(targets, activeId = "vnc", chosenId = null)?.id)
    }

    @Test
    fun nothing_connected_means_nothing_to_run_into() {
        assertNull(defaultSnippetRunTarget(emptyList(), activeId = "t1", chosenId = "t1"))
    }
}
