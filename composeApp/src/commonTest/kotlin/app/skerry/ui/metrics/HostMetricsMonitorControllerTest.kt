package app.skerry.ui.metrics

import app.skerry.shared.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Polling behaviour beyond a single snapshot: the sparkline history, network rates derived from
 * counter deltas, and the availability verdict for hosts that can't serve metrics at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostMetricsMonitorControllerTest {

    private fun output(cpuBusy: Int, rx: Long, tx: Long) = """
        cpu  100 0 100 800 0 0 0 0
        cpu  ${100 + cpuBusy} 0 ${100 + cpuBusy} ${800 + (200 - 2 * cpuBusy)} 0 0 0 0
        @MEM
        Mem:  4000000000 2000000000 2000000000
        @DISK
        /dev/sda1  100 87 13 87% /
        @NET
          eth0: $rx 10 0 0 0 0 0 0 $tx 10
    """.trimIndent()

    @Test
    fun accumulates_a_history_sample_per_successful_poll() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(
            exec = { ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        assertEquals(1, controller.history.size)
        testScheduler.advanceTimeBy(2_500)
        assertEquals(3, controller.history.size)
        assertTrue(controller.history.all { it.cpuPercent == 50 })

        controller.stop()
        scope.cancel()
    }

    @Test
    fun history_is_capped_at_the_window_size() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(
            exec = { ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 1,
        )

        controller.start()
        testScheduler.advanceTimeBy((METRICS_HISTORY_SIZE + 20).toLong())

        assertEquals(METRICS_HISTORY_SIZE, controller.history.size)
        controller.stop()
        scope.cancel()
    }

    @Test
    fun derives_network_rates_from_the_counter_delta_over_elapsed_time() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val time = TestTimeSource()
        var rx = 1_000_000L
        var tx = 500_000L
        val controller = HostMetricsController(
            exec = {
                val out = output(50, rx, tx)
                rx += 3_000_000 // +3 MB between polls
                tx += 1_000_000
                time += 2_000.milliseconds
                ExecResult(0, out, "")
            },
            scope = scope,
            intervalMs = 1_000,
            timeSource = time,
        )

        controller.start()
        // First poll has no previous counters — rates stay at zero until there's a delta.
        assertEquals(0L, controller.netRxRate)
        testScheduler.advanceTimeBy(1_500)

        assertEquals(1_500_000L, controller.netRxRate) // 3 MB over 2 s
        assertEquals(500_000L, controller.netTxRate)

        controller.stop()
        scope.cancel()
    }

    @Test
    fun counter_reset_after_reboot_does_not_produce_a_negative_rate() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val time = TestTimeSource()
        var rx = 9_000_000L
        val controller = HostMetricsController(
            exec = {
                val out = output(50, rx, 0)
                rx = 1_000 // counters reset
                time += 1_000.milliseconds
                ExecResult(0, out, "")
            },
            scope = scope,
            intervalMs = 1_000,
            timeSource = time,
        )

        controller.start()
        testScheduler.advanceTimeBy(1_500)

        assertEquals(0L, controller.netRxRate)
        controller.stop()
        scope.cancel()
    }

    @Test
    fun a_host_without_exec_channels_is_reported_unsupported_and_polling_stops() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            exec = { calls++; throw UnsupportedOperationException("Telnet does not support exec channels") },
            scope = scope,
            intervalMs = 1_000,
        )

        assertEquals(MetricsAvailability.Probing, controller.availability)
        controller.start()

        assertEquals(MetricsAvailability.Unsupported, controller.availability)
        testScheduler.advanceTimeBy(5_000)
        assertEquals(1, calls) // no point retrying a transport that has no exec channel

        controller.stop()
        scope.cancel()
    }

    @Test
    fun output_that_never_parses_ends_as_unsupported_after_a_few_attempts() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(
            exec = { ExecResult(0, "sh: free: command not found", "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        assertEquals(MetricsAvailability.Probing, controller.availability) // one bad poll isn't a verdict
        testScheduler.advanceTimeBy(5_000)

        assertEquals(MetricsAvailability.Unsupported, controller.availability)
        controller.stop()
        scope.cancel()
    }

    @Test
    fun unparsable_polls_stop_counting_after_a_successful_one() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            // bad, bad, good, bad, bad — never three in a row, so no verdict is reached.
            exec = { ExecResult(0, if (++calls == 3) output(50, 0, 0) else "not linux", "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        testScheduler.advanceTimeBy(4_500)

        assertEquals(MetricsAvailability.Live, controller.availability)
        controller.stop()
        scope.cancel()
    }

    @Test
    fun stop_cancels_a_poll_that_is_still_in_flight() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var finished = false
        val controller = HostMetricsController(
            // A round-trip that outlives the session: stop() must interrupt it, not wait it out.
            exec = { delay(60_000); finished = true; ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        controller.stop()
        testScheduler.advanceTimeBy(120_000)

        assertFalse(finished, "an in-flight exec must be cancelled by stop()")
        assertNull(controller.metrics)
        scope.cancel()
    }

    @Test
    fun a_transient_exec_failure_keeps_the_last_snapshot_and_stays_live() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            exec = {
                calls++
                if (calls == 2) throw RuntimeException("channel dropped")
                ExecResult(0, output(50, 0, 0), "")
            },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        testScheduler.advanceTimeBy(1_500)

        assertEquals(MetricsAvailability.Live, controller.availability)
        assertEquals(50, controller.metrics?.cpuPercent)
        controller.stop()
        scope.cancel()
    }
}
