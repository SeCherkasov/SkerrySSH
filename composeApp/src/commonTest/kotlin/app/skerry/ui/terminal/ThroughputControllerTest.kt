package app.skerry.ui.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Terminal channel throughput poller: computes bytes/sec from the byte-counter delta over the
 * period. Time is virtual (testScheduler): `advanceTimeBy(period)` + `runCurrent()` triggers
 * exactly one poll (the delay task lands at the end of the period; runCurrent runs it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThroughputControllerTest {

    @Test
    fun computes_rate_from_byte_delta_over_interval() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var up = 0L
        var down = 0L
        val c = ThroughputController({ up }, { down }, scope, pollIntervalMillis = 1000)

        c.start() // captures the 0/0 baseline
        up = 2048
        down = 8192
        testScheduler.advanceTimeBy(1000); testScheduler.runCurrent()

        assertEquals(2048L, c.upRate)
        assertEquals(8192L, c.downRate)
        c.stop()
        scope.cancel()
    }

    @Test
    fun rate_is_zero_without_new_bytes_and_tracks_next_delta() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var up = 1000L // already accumulated before the poller starts
        val c = ThroughputController({ up }, { 0L }, scope, pollIntervalMillis = 1000)

        c.start() // baseline = 1000; the prior total must not count as rate
        testScheduler.advanceTimeBy(1000); testScheduler.runCurrent()
        assertEquals(0L, c.upRate)

        up = 1500
        testScheduler.advanceTimeBy(1000); testScheduler.runCurrent()
        assertEquals(500L, c.upRate)

        c.stop()
        scope.cancel()
    }

    @Test
    fun half_second_interval_scales_to_per_second() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var down = 0L
        val c = ThroughputController({ 0L }, { down }, scope, pollIntervalMillis = 500)

        c.start()
        down = 1024 // 1024 bytes in half a second -> 2048 bytes/sec
        testScheduler.advanceTimeBy(500); testScheduler.runCurrent()
        assertEquals(2048L, c.downRate)

        c.stop()
        scope.cancel()
    }

    @Test
    fun start_is_idempotent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var polls = 0
        val c = ThroughputController({ polls.toLong() }, { 0L }, scope, pollIntervalMillis = 1000)

        c.start()
        c.start() // second call must not spin up a second poll loop
        polls = 10
        testScheduler.advanceTimeBy(1000); testScheduler.runCurrent()

        // One loop -> one increment per period; a second loop would double the delta on the shared counter.
        assertEquals(10L, c.upRate)
        c.stop()
        scope.cancel()
    }
}
