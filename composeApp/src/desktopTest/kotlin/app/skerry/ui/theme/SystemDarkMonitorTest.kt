package app.skerry.ui.theme

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Pins the refcounting of the shared desktop dark-mode monitor: any number of composables
 * (app theme, terminal twin, theme-picker preview) must share ONE OS watcher — the watcher starts
 * with the first subscriber, stops with the last, and `current()` serves the watcher's cache
 * instead of spawning a fresh detect subprocess while a watcher is live.
 */
class SystemDarkMonitorTest {

    /** Monitor with a scripted detector and a watch hook that records starts/stops. */
    private class Harness(testScope: TestScope) {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val detects = AtomicInteger()
        var detectValue: Boolean? = false
        var onDark: ((Boolean) -> Unit)? = null

        val monitor = SystemDarkMonitor(
            detect = { detects.incrementAndGet(); detectValue },
            watch = { push ->
                starts.incrementAndGet()
                onDark = push
                try {
                    awaitCancellation()
                } finally {
                    stops.incrementAndGet()
                }
            },
            dispatcher = StandardTestDispatcher(testScope.testScheduler),
        )
    }

    @Test
    fun concurrent_subscribers_share_one_watcher() = runTest {
        val h = Harness(this)
        val first = h.monitor.subscribe { }
        val second = h.monitor.subscribe { }
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.starts.get())

        // Dropping one of two subscribers keeps the shared watcher alive.
        first.close()
        testScheduler.advanceUntilIdle()
        assertEquals(0, h.stops.get())
        second.close()
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.stops.get())
    }

    @Test
    fun watcher_restarts_for_a_subscriber_after_full_teardown() = runTest {
        val h = Harness(this)
        h.monitor.subscribe { }.use { testScheduler.advanceUntilIdle() }
        testScheduler.advanceUntilIdle()
        assertEquals(1, h.stops.get())

        val again = h.monitor.subscribe { }
        testScheduler.advanceUntilIdle()
        assertEquals(2, h.starts.get())
        again.close()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun pushed_values_reach_every_listener_and_fill_the_cache() = runTest {
        val h = Harness(this)
        val seenA = mutableListOf<Boolean>()
        val seenB = mutableListOf<Boolean>()
        val a = h.monitor.subscribe { seenA += it }
        val b = h.monitor.subscribe { seenB += it }
        testScheduler.advanceUntilIdle()

        h.onDark!!(true)
        assertEquals(listOf(true), seenA)
        assertEquals(listOf(true), seenB)

        // While the watcher is live, current() serves its cache — no detect subprocess.
        val detectsBefore = h.detects.get()
        assertTrue(h.monitor.current())
        h.onDark!!(false)
        assertFalse(h.monitor.current())
        assertEquals(detectsBefore, h.detects.get())

        a.close()
        b.close()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun current_re_detects_while_idle() = runTest {
        val h = Harness(this)
        h.detectValue = true
        assertTrue(h.monitor.current())
        h.detectValue = false
        assertFalse(h.monitor.current())
        assertEquals(2, h.detects.get())
        // Unknown OS answer falls back to the last known value rather than flipping the theme.
        h.detectValue = null
        assertFalse(h.monitor.current())
    }
}
