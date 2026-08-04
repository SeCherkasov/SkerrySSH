package app.skerry.ui.runbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Getting a run's session ready before it starts. One that is already open is usable as it stands;
 * a catalog host has to be connected first — and the run must not wait forever on a host that never
 * answers.
 */
class RunbookLaunchControllerTest {

    private val clock = FakeClock()

    private class FakeClock {
        var millis = 0L
        fun now(): Long = millis
    }

    private fun controller(timeout: Long = 60_000) = RunbookLaunchController(now = clock::now, timeoutMillis = timeout)

    @Test
    fun `a session that is already open is ready at once`() {
        val opened = mutableListOf<String>()
        val c = controller()

        c.begin(RunbookLaunchTarget.Session("pane-1", "web-01"), openHost = { opened += it })

        assertEquals(RunbookLaunchState.Ready("pane-1"), c.state)
        assertTrue(opened.isEmpty(), "nothing to connect")
    }

    @Test
    fun `a catalog host is dialled once and waited for`() {
        val opened = mutableListOf<String>()
        val c = controller()

        c.begin(RunbookLaunchTarget.CatalogHost("h1", "db-01"), openHost = { opened += it })

        assertEquals(listOf("h1"), opened)
        assertEquals(RunbookLaunchState.Connecting("db-01"), c.state)

        c.refresh { hostId -> if (hostId == "h1") "pane-9" else null }

        assertEquals(RunbookLaunchState.Ready("pane-9"), c.state)
        assertEquals(listOf("h1"), opened, "a host is dialled once, not once per refresh")
    }

    @Test
    fun `a host that never answers gives up once the wait runs out`() {
        val c = controller(timeout = 30_000)
        c.begin(RunbookLaunchTarget.CatalogHost("h1", "db-01"), openHost = {})

        clock.millis = 30_001
        c.refresh { null }

        assertEquals(RunbookLaunchState.Unreachable("db-01"), c.state)
    }

    @Test
    fun `a host connecting just before the wait runs out still counts`() {
        val c = controller(timeout = 10_000)
        c.begin(RunbookLaunchTarget.CatalogHost("h1", "db-01"), openHost = {})

        clock.millis = 9_999
        c.refresh { "pane-1" }

        assertEquals(RunbookLaunchState.Ready("pane-1"), c.state)
    }

    @Test
    fun `a refresh after the session is ready changes nothing`() {
        // The dialog keeps re-reading the session list while it starts the run; a pane that goes
        // away in that window must not turn a ready launch back into a wait.
        val c = controller()
        c.begin(RunbookLaunchTarget.CatalogHost("h1", "db-01"), openHost = {})
        c.refresh { "pane-1" }

        c.refresh { null }

        assertEquals(RunbookLaunchState.Ready("pane-1"), c.state)
    }

    @Test
    fun `beginning again forgets the previous attempt`() {
        val opened = mutableListOf<String>()
        val c = controller(timeout = 10_000)
        c.begin(RunbookLaunchTarget.CatalogHost("h1", "db-01"), openHost = { opened += it })
        clock.millis = 10_001
        c.refresh { null }
        assertIs<RunbookLaunchState.Unreachable>(c.state)

        c.begin(RunbookLaunchTarget.Session("pane-a", "web-01"), openHost = { opened += it })

        assertEquals(RunbookLaunchState.Ready("pane-a"), c.state)
        assertEquals(listOf("h1"), opened)
    }

    @Test
    fun `cancelling puts it back to idle`() {
        val c = controller()
        c.begin(RunbookLaunchTarget.CatalogHost("h1", "db-01"), openHost = {})

        c.cancel()

        assertEquals(RunbookLaunchState.Idle, c.state)
    }
}
