package app.skerry.ui.metrics

import kotlin.test.Test
import kotlin.test.assertEquals

/** Rolling window backing the sparklines: fixed capacity, oldest sample first. */
class MetricsHistoryTest {

    private fun sample(cpu: Int) = MetricsSample(cpuPercent = cpu, memPercent = 0, rxBytesPerSec = 0, txBytesPerSec = 0)

    @Test
    fun appends_samples_in_arrival_order() {
        val history = listOf(sample(1)).appendCapped(sample(2), capacity = 4).appendCapped(sample(3), capacity = 4)
        assertEquals(listOf(1, 2, 3), history.map { it.cpuPercent })
    }

    @Test
    fun drops_the_oldest_sample_beyond_capacity() {
        var history = emptyList<MetricsSample>()
        repeat(5) { i -> history = history.appendCapped(sample(i), capacity = 3) }
        assertEquals(listOf(2, 3, 4), history.map { it.cpuPercent })
        assertEquals(3, history.size)
    }

    @Test
    fun keeps_only_the_last_sample_for_a_degenerate_capacity() {
        val history = listOf(sample(1), sample(2)).appendCapped(sample(3), capacity = 0)
        assertEquals(listOf(3), history.map { it.cpuPercent })
    }
}
