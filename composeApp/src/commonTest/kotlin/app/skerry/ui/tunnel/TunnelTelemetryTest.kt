package app.skerry.ui.tunnel

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunnelTelemetryTest {

    private fun telemetry() = TunnelTelemetry(pollIntervalMillis = 1000)

    @Test
    fun `sampling records aggregate throughput in arrival order`() {
        val t = telemetry()

        t.sample(up = 100, down = 200)
        t.sample(up = 300, down = 400)

        assertEquals(listOf(ThroughputSample(100, 200), ThroughputSample(300, 400)), t.history)
    }

    @Test
    fun `history is bounded and keeps the newest samples`() {
        // The sparkline draws a fixed window; an unbounded list would grow for as long as the app
        // runs, one entry per second, for a chart that can only show the tail anyway.
        val t = telemetry()

        repeat(TunnelTelemetry.HISTORY_CAPACITY + 5) { i -> t.sample(up = i.toLong(), down = 0) }

        assertEquals(TunnelTelemetry.HISTORY_CAPACITY, t.history.size)
        assertEquals(5L, t.history.first().up) // the first five fell off the front
        assertEquals((TunnelTelemetry.HISTORY_CAPACITY + 4).toLong(), t.history.last().up)
    }

    @Test
    fun `a failure is recorded once, not on every retry of the same tunnel`() {
        val t = telemetry()

        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        assertEquals(1, t.events.size)
        assertEquals(TunnelEventKind.Failed(TunnelFailureKind.Connection), t.events.single().kind)
    }

    @Test
    fun `a different cause for the same tunnel is news, not a repeat`() {
        // Retrying a failed tunnel is one tap, and the second attempt can fail for a different
        // reason than the first (credential fixed, network down). Suppressing that leaves the card
        // asserting a cause that is no longer true, while the row right above it says otherwise.
        val t = telemetry()

        t.failed("t1", "Redis", 6379, TunnelFailureKind.Auth)
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        assertEquals(
            listOf(TunnelEventKind.Failed(TunnelFailureKind.Connection), TunnelEventKind.Failed(TunnelFailureKind.Auth)),
            t.events.map { it.kind },
        )
    }

    @Test
    fun `coming up after a failure is recorded as recovered`() {
        val t = telemetry()
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        t.active("t1", "Redis", 6379)

        assertEquals(listOf(TunnelEventKind.Recovered, TunnelEventKind.Failed(TunnelFailureKind.Connection)), t.events.map { it.kind })
    }

    @Test
    fun `a tunnel that never failed produces no recovered event`() {
        val t = telemetry()

        t.active("t1", "Redis", 6379)

        assertTrue(t.events.isEmpty())
    }

    @Test
    fun `recovery is reported once, not on every reconnect`() {
        val t = telemetry()
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        t.active("t1", "Redis", 6379)
        t.active("t1", "Redis", 6379)

        assertEquals(2, t.events.size)
    }

    @Test
    fun `events are newest first and bounded`() {
        val t = telemetry()

        repeat(TunnelTelemetry.EVENT_CAPACITY + 2) { i ->
            t.failed("t$i", "Tunnel $i", 1000 + i, TunnelFailureKind.Auth)
        }

        assertEquals(TunnelTelemetry.EVENT_CAPACITY, t.events.size)
        assertEquals("Tunnel ${TunnelTelemetry.EVENT_CAPACITY + 1}", t.events.first().label)
    }

    @Test
    fun `forgetting a deleted tunnel lets a later failure be reported again`() {
        // Delete-and-recreate must not be silently swallowed because the old id was still marked
        // as failing.
        val t = telemetry()
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        t.forget("t1")
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        assertEquals(2, t.events.size)
    }

    @Test
    fun `event age counts poll ticks, not wall clock`() {
        val t = telemetry()
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)
        val event = t.events.single()

        repeat(12) { t.sample(up = 0, down = 0) }

        assertEquals(12, t.secondsSince(event))
    }

    @Test
    fun `the age of an event is snapshot state, so the card can tick`() {
        // The errors card renders the age of its newest entry. `events` stops changing the moment a
        // failure is recorded, so if the tick counter is not snapshot-backed the card never
        // recomposes and freezes at "0 s ago" forever.
        val t = telemetry()
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)
        val event = t.events.single()

        var stateReads = 0
        Snapshot.observe(readObserver = { stateReads++ }) { t.secondsSince(event) }

        assertTrue(stateReads > 0, "secondsSince must read snapshot state, else the age never updates")
    }

    @Test
    fun `reset clears history, events and the failing set`() {
        val t = telemetry()
        t.sample(up = 1, down = 1)
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)

        t.reset()

        assertTrue(t.history.isEmpty())
        assertTrue(t.events.isEmpty())
        // The failing set went with it: the same tunnel failing after a lock is news again.
        t.failed("t1", "Redis", 6379, TunnelFailureKind.Connection)
        assertEquals(1, t.events.size)
    }
}
