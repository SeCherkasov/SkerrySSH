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
    fun measurement_landing_after_stop_is_discarded() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        // Single-threaded stand-in for the cross-thread race: on a multi-threaded scope stop() can
        // run after measure() returned but before the loop stored the value; the stale store would
        // survive into a restarted cycle.
        var controller: PingController? = null
        val c = PingController(measure = { controller!!.stop(); 42L }, scope = scope)
        controller = c

        c.start()
        assertNull(c.rttMs)

        scope.cancel()
    }

    // Dead-link detection (OpenSSH ServerAliveCountMax analog): [deadAfterFailures] consecutive
    // failed measurements fire onDead once and end the polling loop.

    @Test
    fun onDead_fires_once_after_consecutive_failures_and_polling_ends() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var deaths = 0
        var calls = 0
        val c = PingController(
            measure = { calls++; null }, // link is dead from the start
            scope = scope,
            pollIntervalMillis = 5000,
            deadAfterFailures = 3,
            onDead = { deaths++ },
        )

        c.start() // failure #1 right away
        assertEquals(0, deaths)
        testScheduler.advanceTimeBy(5000); testScheduler.runCurrent() // #2
        assertEquals(0, deaths)
        testScheduler.advanceTimeBy(5000); testScheduler.runCurrent() // #3 -> dead
        assertEquals(1, deaths)

        val after = calls
        testScheduler.advanceTimeBy(50_000); testScheduler.runCurrent()
        assertEquals(after, calls) // loop ended with onDead: no more pings
        assertEquals(1, deaths) // and no repeat firing
        scope.cancel()
    }

    @Test
    fun success_resets_the_failure_streak() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        // fail, fail, success, fail, fail — never 3 consecutive failures.
        val script = listOf<Long?>(null, null, 5L, null, null)
        var i = 0
        var deaths = 0
        val c = PingController(
            measure = { script.getOrNull(i++) ?: 5L },
            scope = scope,
            pollIntervalMillis = 5000,
            deadAfterFailures = 3,
            onDead = { deaths++ },
        )

        c.start()
        testScheduler.advanceTimeBy(30_000); testScheduler.runCurrent()

        assertEquals(0, deaths)
        assertEquals(5L, c.rttMs)
        scope.cancel()
    }

    @Test
    fun stop_during_the_fatal_measure_suppresses_onDead() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        // Same single-threaded stand-in as the stale-rtt test: stop() lands after the fatal
        // measure() returned but before the loop acted on it — the death must be discarded.
        var deaths = 0
        var controller: PingController? = null
        val c = PingController(
            measure = { controller!!.stop(); null },
            scope = scope,
            deadAfterFailures = 1,
            onDead = { deaths++ },
        )
        controller = c

        c.start()

        assertEquals(0, deaths)
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
