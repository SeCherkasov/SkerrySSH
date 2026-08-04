package app.skerry.ui.runbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the picked chips add up to — the run's host list, in the order the run will walk it. */
class RunbookTargetPickerTest {

    private val sessions = listOf(
        RunbookLaunchTarget.Session("pane-1", "web-01"),
        RunbookLaunchTarget.Session("pane-2", "web-02"),
    )
    private val catalog = listOf(
        RunbookLaunchTarget.CatalogHost("host-a", "db-01"),
        RunbookLaunchTarget.CatalogHost("host-b", "db-02"),
    )

    @Test
    fun `open sessions come before catalog hosts, each in list order`() {
        val picked = setOf("host-b", "pane-2", "host-a", "pane-1")

        val targets = pickedLaunchTargets(sessions, catalog, picked)

        assertEquals(
            listOf("web-01", "web-02", "db-01", "db-02"),
            targets.map { it.label },
        )
    }

    @Test
    fun `nothing picked is no targets rather than everything`() {
        assertTrue(pickedLaunchTargets(sessions, catalog, emptySet()).isEmpty())
    }

    @Test
    fun `an id that belongs to neither list is ignored`() {
        // A tab closed while the dialog was open leaves its id behind in the pick.
        val targets = pickedLaunchTargets(sessions, catalog, setOf("pane-1", "gone"))

        assertEquals(listOf("web-01"), targets.map { it.label })
    }
}
