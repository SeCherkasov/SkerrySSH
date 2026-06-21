package app.skerry.ui.metrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostMetricsTest {

    private val fullOutput = """
        cpu  100 0 100 800 0 0 0 0
        cpu  150 0 150 900 0 0 0 0
        @MEM
                      total        used        free      shared  buff/cache   available
        Mem:     4000000000  2100000000  1000000000     1000000   900000000  1700000000
        Swap:    2000000000           0  2000000000
        @DISK
        Filesystem     1024-blocks      Used Available Capacity Mounted on
        /dev/sda1         51475068  42000000   6900000      87% /
    """.trimIndent()

    @Test
    fun parses_cpu_by_delta_of_two_proc_stat_samples() {
        val m = parseHostMetrics(fullOutput)!!
        // total 1000→1200 (Δ200), idle 800→900 (Δ100) ⇒ busy 100/200 = 50%
        assertEquals(50, m.cpuPercent)
        assertEquals(0.5f, m.cpuFraction)
    }

    @Test
    fun parses_memory_used_and_total() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(2_100_000_000L, m.memUsedBytes)
        assertEquals(4_000_000_000L, m.memTotalBytes)
        assertEquals(0.525f, m.memFraction, 0.001f)
    }

    @Test
    fun parses_disk_use_percent() {
        val m = parseHostMetrics(fullOutput)!!
        assertEquals(87, m.diskPercent)
        assertEquals(0.87f, m.diskFraction, 0.001f)
    }

    @Test
    fun single_cpu_sample_falls_back_to_instantaneous() {
        val out = """
            cpu  200 0 200 600 0 0 0 0
            @MEM
            Mem:     4000000000  2000000000  2000000000
            @DISK
            /dev/sda1  100 50 50 10% /
        """.trimIndent()
        // total 1000, idle 600 ⇒ busy 400/1000 = 40%
        assertEquals(40, parseHostMetrics(out)!!.cpuPercent)
    }

    @Test
    fun returns_null_when_memory_section_missing() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @DISK
            /dev/sda1  100 87 13 87% /
        """.trimIndent()
        assertNull(parseHostMetrics(out))
    }

    @Test
    fun returns_null_when_disk_section_missing() {
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
        """.trimIndent()
        assertNull(parseHostMetrics(out))
    }

    @Test
    fun disk_percent_taken_only_from_disk_section() {
        // %-токен из соседней (mem) секции не должен подменить метрику диска.
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            Noise   99% ignored
            @DISK
            Filesystem     1024-blocks      Used Available Capacity Mounted on
            /dev/sda1         51475068  42000000   6900000      87% /
        """.trimIndent()
        assertEquals(87, parseHostMetrics(out)!!.diskPercent)
    }

    @Test
    fun disk_takes_root_row_when_multiple_data_rows_present() {
        // На случай нескольких строк df берётся первая строка данных (после заголовка) — корень.
        val out = """
            cpu  100 0 100 800 0 0 0 0
            cpu  150 0 150 900 0 0 0 0
            @MEM
            Mem:  4000000000 2100000000 1000000000
            @DISK
            Filesystem     1024-blocks      Used Available Capacity Mounted on
            /dev/sda1         51475068  42000000   6900000      87% /
            /dev/sda2        209715200 120000000  78000000      62% /var
        """.trimIndent()
        assertEquals(87, parseHostMetrics(out)!!.diskPercent)
    }

    @Test
    fun clamps_fractions_into_unit_range() {
        val m = HostMetrics(cpuPercent = 150, memUsedBytes = 9, memTotalBytes = 4, diskPercent = -5)
        assertEquals(1f, m.cpuFraction)
        assertEquals(1f, m.memFraction)
        assertEquals(0f, m.diskFraction)
        assertTrue(m.cpuFraction in 0f..1f && m.memFraction in 0f..1f && m.diskFraction in 0f..1f)
    }
}
