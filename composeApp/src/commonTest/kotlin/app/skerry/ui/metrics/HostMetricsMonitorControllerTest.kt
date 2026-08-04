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
    fun refresh_polls_without_waiting_out_the_interval() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            exec = { calls++; ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 60_000,
        )

        controller.start()
        try {
            assertEquals(1, calls)
            controller.refreshNow()
            testScheduler.advanceTimeBy(10)

            assertEquals(2, calls, "the refresh button must not wait out the poll interval")
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun a_new_interval_takes_effect_on_the_next_wait() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            exec = { calls++; ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 60_000,
        )

        controller.start()
        try {
            controller.setInterval(1_000)
            assertEquals(1_000, controller.intervalMs)
            // The wait already running was sized by the old interval; the refresh it takes to leave
            // it is what the button does, and every wait after that uses the new one.
            controller.refreshNow()
            testScheduler.advanceTimeBy(2_500)

            assertTrue(calls >= 4, "expected polls at the new 1 s interval, got $calls")
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun records_an_alert_for_a_threshold_the_snapshot_crosses() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var clock = 1_700_000_000_000L
        val controller = HostMetricsController(
            // The shared fixture's root filesystem sits at 87 % — over the disk threshold.
            exec = { ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 1_000,
            now = { clock += 1_000; clock },
        )

        controller.start()
        try {
            val alert = controller.alerts.entries.single()
            assertEquals(AlertKind.DiskFull, alert.kind)
            assertEquals("/", alert.subject)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun asks_for_units_and_containers_only_on_every_nth_poll() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val commands = mutableListOf<String>()
        val controller = HostMetricsController(
            exec = { cmd -> commands += cmd; ExecResult(0, output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        try {
            testScheduler.advanceTimeBy(1_000L * HostMetricsController.FULL_POLL_EVERY + 500)

            // systemctl and docker are the expensive part of the round-trip: the first poll pays for
            // them (the cards must fill immediately), then only every FULL_POLL_EVERY-th one.
            assertEquals(HostMetricsController.FULL_POLL_EVERY + 1, commands.size)
            assertTrue(commands.first().contains("@SERVICES"))
            assertTrue(commands.drop(1).take(HostMetricsController.FULL_POLL_EVERY - 1).none { it.contains("@SERVICES") })
            assertTrue(commands.last().contains("@SERVICES"))
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun carries_units_and_containers_over_the_cheap_polls() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val withUnits = output(50, 0, 0) + """

            @SERVICES
            nginx.service loaded active running Web
            @CONTAINERS
            app-web${'\t'}app:0.2.1${'\t'}Up 3 days
        """.trimIndent()
        var first = true
        val controller = HostMetricsController(
            exec = { ExecResult(0, if (first) withUnits.also { first = false } else output(50, 0, 0), "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        // Stopped in a finally: a failed assertion would otherwise leave the poll loop running on
        // the test scheduler, and runTest would hang instead of reporting the failure.
        try {
            assertEquals(1, controller.metrics?.services?.size)
            testScheduler.advanceTimeBy(1_500) // a cheap poll, with no unit sections at all

            // The lists survive the polls that didn't ask for them — otherwise the two cards would
            // blink out between full polls.
            assertEquals(1, controller.metrics?.services?.size)
            assertEquals(1, controller.metrics?.containers?.size)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun a_full_poll_that_finds_nothing_clears_the_lists() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val withUnits = output(50, 0, 0) + """

            @SERVICES
            nginx.service loaded active running Web
            @CONTAINERS
            app-web${'\t'}app:0.2.1${'\t'}Up 3 days
        """.trimIndent()
        // Empty unit sections, the way a host answers once the container is gone and the unit stopped.
        val withoutUnits = output(50, 0, 0) + "\n@SERVICES\n@CONTAINERS\n@CSTATS"
        var polls = 0
        val controller = HostMetricsController(
            exec = { ExecResult(0, if (polls++ == 0) withUnits else withoutUnits, "") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        try {
            assertEquals(1, controller.metrics?.containers?.size)
            // Far enough for the next full poll (every FULL_POLL_EVERY-th) to land.
            testScheduler.advanceTimeBy(1_000L * HostMetricsController.FULL_POLL_EVERY + 500)

            // The carry-over is for the polls that never asked; a full poll that asked and got
            // nothing is the host saying the container is gone.
            assertEquals(emptyList(), controller.metrics?.containers)
            assertEquals(emptyList(), controller.metrics?.services)
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

    @Test
    fun an_exec_channel_that_only_ever_fails_ends_as_unsupported() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            // A restricted shell that rejects the command every time: not a hiccup, and the screen
            // must say so instead of counting seconds under "waiting for data" forever.
            exec = { calls++; error("command rejected") },
            scope = scope,
            intervalMs = 1_000,
        )

        controller.start()
        try {
            testScheduler.advanceTimeBy(30_000)
            assertEquals(MetricsAvailability.Unsupported, controller.availability)
            assertTrue(calls < 10, "polling must stop after the verdict, ran $calls times")
        } finally {
            controller.stop()
            scope.cancel()
        }
    }

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
