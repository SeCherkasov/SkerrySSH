package app.skerry.ui.runbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Getting a run's hosts ready before it starts. Some targets are sessions already open; others are
 * catalog hosts that have to be connected first — and a run must not begin on half of them, nor
 * wait forever on a host that never answers.
 */
class RunbookLaunchControllerTest {

    private val clock = FakeClock()

    private class FakeClock {
        var millis = 0L
        fun now(): Long = millis
    }

    private fun controller(timeout: Long = 60_000) = RunbookLaunchController(now = clock::now, timeoutMillis = timeout)

    private fun session(paneId: String) = RunbookLaunchTarget.Session(paneId, paneId)
    private fun host(hostId: String) = RunbookLaunchTarget.CatalogHost(hostId, hostId)

    @Test
    fun `targets that are already open are ready at once`() {
        val opened = mutableListOf<String>()
        val c = controller()

        c.begin(listOf(session("pane-1"), session("pane-2")), openHost = { opened += it })

        assertEquals(RunbookLaunchState.Ready(listOf("pane-1", "pane-2")), c.state)
        assertTrue(opened.isEmpty(), "nothing to connect")
    }

    @Test
    fun `a catalog host is opened once and waited for`() {
        val opened = mutableListOf<String>()
        val c = controller()

        c.begin(listOf(host("h1")), openHost = { opened += it })

        assertEquals(listOf("h1"), opened)
        assertIs<RunbookLaunchState.Connecting>(c.state)

        c.refresh { hostId -> if (hostId == "h1") "pane-9" else null }

        assertEquals(RunbookLaunchState.Ready(listOf("pane-9")), c.state)
        assertEquals(listOf("h1"), opened, "a host is dialled once, not once per refresh")
    }

    @Test
    fun `the run waits until every host is up`() {
        val c = controller()
        c.begin(listOf(host("h1"), host("h2")), openHost = {})

        c.refresh { hostId -> if (hostId == "h1") "pane-1" else null }

        val state = assertIs<RunbookLaunchState.Connecting>(c.state)
        assertEquals(listOf("h2"), state.pending)
    }

    @Test
    fun `targets keep the order they were picked in, sessions and hosts mixed`() {
        // Order is the rolling order: with one host at a time, this is the sequence of the deploy.
        val c = controller()
        c.begin(listOf(host("h1"), session("pane-a"), host("h2")), openHost = {})

        c.refresh { hostId -> mapOf("h1" to "pane-1", "h2" to "pane-2")[hostId] }

        assertEquals(RunbookLaunchState.Ready(listOf("pane-1", "pane-a", "pane-2")), c.state)
    }

    @Test
    fun `a host that never answers is dropped once the wait runs out`() {
        val c = controller(timeout = 30_000)
        c.begin(listOf(session("pane-a"), host("h1")), openHost = {})

        clock.millis = 30_001
        c.refresh { null }

        val state = assertIs<RunbookLaunchState.Unreachable>(c.state)
        assertEquals(listOf("h1"), state.unreachable)
        assertEquals(listOf("pane-a"), state.ready)
    }

    @Test
    fun `a wait that runs out with nothing connected has nothing to run`() {
        val c = controller(timeout = 10_000)
        c.begin(listOf(host("h1")), openHost = {})

        clock.millis = 10_001
        c.refresh { null }

        val state = assertIs<RunbookLaunchState.Unreachable>(c.state)
        assertTrue(state.ready.isEmpty())
    }

    @Test
    fun `a host connecting just before the wait runs out still counts`() {
        val c = controller(timeout = 10_000)
        c.begin(listOf(host("h1")), openHost = {})

        clock.millis = 9_999
        c.refresh { "pane-1" }

        assertEquals(RunbookLaunchState.Ready(listOf("pane-1")), c.state)
    }

    @Test
    fun `beginning again forgets the previous attempt`() {
        val opened = mutableListOf<String>()
        val c = controller(timeout = 10_000)
        c.begin(listOf(host("h1")), openHost = { opened += it })
        clock.millis = 10_001
        c.refresh { null }
        assertIs<RunbookLaunchState.Unreachable>(c.state)

        c.begin(listOf(session("pane-a")), openHost = { opened += it })

        assertEquals(RunbookLaunchState.Ready(listOf("pane-a")), c.state)
        assertEquals(listOf("h1"), opened)
    }

    @Test
    fun `an empty pick is refused rather than starting a run with no hosts`() {
        val c = controller()

        c.begin(emptyList(), openHost = {})

        assertEquals(RunbookLaunchState.Idle, c.state)
    }

    @Test
    fun `cancelling puts it back to idle`() {
        val c = controller()
        c.begin(listOf(host("h1")), openHost = {})

        c.cancel()

        assertEquals(RunbookLaunchState.Idle, c.state)
    }
}
