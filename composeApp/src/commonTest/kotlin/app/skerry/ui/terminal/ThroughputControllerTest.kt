package app.skerry.ui.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Поллер скорости терминального канала: по дельте счётчиков байт за период считает байт/с.
 * Время виртуальное (testScheduler): `advanceTimeBy(period)` + `runCurrent()` запускает ровно
 * один опрос (задача delay назначена на конец периода — её выполняет runCurrent).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThroughputControllerTest {

    @Test
    fun computes_rate_from_byte_delta_over_interval() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var up = 0L
        var down = 0L
        val c = ThroughputController({ up }, { down }, scope, pollIntervalMillis = 1000)

        c.start() // снимает базис 0/0
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
        var up = 1000L // уже накоплено до старта поллера
        val c = ThroughputController({ up }, { 0L }, scope, pollIntervalMillis = 1000)

        c.start() // базис = 1000, накопленное НЕ должно сосчитаться как скорость
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
        down = 1024 // 1024 байта за полсекунды → 2048 байт/с
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
        c.start() // второй вызов не должен поднять второй цикл
        polls = 10
        testScheduler.advanceTimeBy(1000); testScheduler.runCurrent()

        // один цикл → один прирост на период; будь циклов два, дельта удвоилась бы при общем счётчике
        assertEquals(10L, c.upRate)
        c.stop()
        scope.cancel()
    }
}
