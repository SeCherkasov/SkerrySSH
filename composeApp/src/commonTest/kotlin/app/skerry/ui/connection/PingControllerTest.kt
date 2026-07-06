package app.skerry.ui.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RTT poller on top of a suspend connection measurement. The first measurement happens right at
 * [PingController.start] (like [app.skerry.ui.metrics.HostMetricsController]); a failed measurement
 * keeps the last successful value instead of resetting the indicator. Time is virtual (testScheduler).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PingControllerTest {

    @Test
    fun polls_and_publishes_rtt() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val c = PingController(measure = { 42L }, scope = scope)

        assertNull(c.rttMs)
        c.start()
        assertEquals(42L, c.rttMs)

        c.stop()
        scope.cancel()
    }

    @Test
    fun failed_measure_keeps_last_value() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val c = PingController(
            measure = { calls++; if (calls == 1) 10L else throw RuntimeException("boom") },
            scope = scope,
            pollIntervalMillis = 5000,
        )

        c.start() // first measurement → 10
        assertEquals(10L, c.rttMs)
        testScheduler.advanceTimeBy(5000); testScheduler.runCurrent() // second measurement throws
        assertEquals(10L, c.rttMs) // last successful value is kept

        c.stop()
        scope.cancel()
    }

    @Test
    fun null_measure_keeps_last_value() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val c = PingController(
            measure = { calls++; if (calls == 1) 7L else null }, // dead round-trip → null
            scope = scope,
            pollIntervalMillis = 5000,
        )

        c.start()
        assertEquals(7L, c.rttMs)
        testScheduler.advanceTimeBy(5000); testScheduler.runCurrent()
        assertEquals(7L, c.rttMs)

        c.stop()
        scope.cancel()
    }

    @Test
    fun start_is_idempotent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val c = PingController(measure = { calls++; 1L }, scope = scope)

        c.start()
        val afterFirst = calls
        c.start() // second cycle doesn't spin up
        assertEquals(afterFirst, calls)

        c.stop()
        scope.cancel()
    }
}
