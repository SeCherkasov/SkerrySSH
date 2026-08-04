package app.skerry.ui.runbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the picked chip stands for — the one session the run will happen in. */
class RunbookTargetPickerTest {

    private val sessions = listOf(
        RunbookLaunchTarget.Session("pane-1", "web-01"),
        RunbookLaunchTarget.Session("pane-2", "web-02"),
    )
    private val catalog = listOf(RunbookLaunchTarget.CatalogHost("host-a", "db-01"))

    @Test
    fun `an open session is picked by its pane`() {
        assertEquals(sessions[1], pickedLaunchTarget(sessions, catalog, "pane-2"))
    }

    @Test
    fun `a catalog host is picked by its host id`() {
        assertEquals(catalog[0], pickedLaunchTarget(sessions, catalog, "host-a"))
    }

    @Test
    fun `nothing picked is no target`() {
        assertNull(pickedLaunchTarget(sessions, catalog, null))
    }

    @Test
    fun `an id that belongs to neither list resolves to nothing`() {
        // A tab closed while the dialog was open leaves its id behind in the pick.
        assertNull(pickedLaunchTarget(sessions, catalog, "gone"))
    }
}
